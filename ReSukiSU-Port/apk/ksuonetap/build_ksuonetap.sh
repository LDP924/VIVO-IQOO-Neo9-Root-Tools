#!/bin/bash
# build_ksuonetap.sh - 构建 KSUOneTap APK (Kotlin, 无 gradle)
#
# 与旧的 build_ksuonetap.ps1 等价, 但改成 Linux/bash 且路径可探测:
#   aapt2 compile/link -> kotlinc -> d8 -> 打包 dex -> zipalign -> apksigner
#
# 依赖 (可用环境变量覆盖):
#   ANDROID_SDK_ROOT  默认探测 ~/Android/Sdk
#   JAVA_HOME         默认探测 ~/.workbuddy/binaries/jdk21
#   KOTLIN_HOME       默认探测 ~/.workbuddy/binaries/dl/kotlinc-dist/kotlinc
#   OUT               默认 ./out
#
# 用法: bash build_ksuonetap.sh [--clean]
set -e

cd "$(dirname "$0")"
PROJ="$PWD"
OUT="${OUT:-$PROJ/out}"

# ---------- 工具链探测 ----------
detect_sdk() {
    local c
    for c in "${ANDROID_SDK_ROOT:-}" "$HOME/Android/Sdk" "$HOME/android-sdk" /opt/android-sdk; do
        [ -n "$c" ] && [ -d "$c/build-tools" ] && { echo "$c"; return 0; }
    done
    return 1
}
SDK="$(detect_sdk)" || { echo "找不到 Android SDK (设 ANDROID_SDK_ROOT)"; exit 1; }
BT_DIR="$(ls -d "$SDK"/build-tools/* 2>/dev/null | sort -V | tail -1)"
PLATFORM_DIR="$(ls -d "$SDK"/platforms/* 2>/dev/null | sort -V | tail -1)"
PLAT="$PLATFORM_DIR/android.jar"
[ -f "$PLAT" ] || { echo "找不到 android.jar: $PLAT"; exit 1; }

JAVA_HOME="${JAVA_HOME:-$HOME/.workbuddy/binaries/jdk21}"
[ -x "$JAVA_HOME/bin/java" ] || { echo "找不到 JDK (设 JAVA_HOME)"; exit 1; }
export JAVA_HOME
export PATH="$JAVA_HOME/bin:$PATH"

KOTLIN_HOME="${KOTLIN_HOME:-$HOME/.workbuddy/binaries/dl/kotlinc-dist/kotlinc}"
[ -d "$KOTLIN_HOME/lib" ] || { echo "找不到 kotlinc (设 KOTLIN_HOME)"; exit 1; }

VERSION="$(grep -oE 'android:versionName="[^"]+"' AndroidManifest.xml | head -1 | sed 's/.*="//;s/"//')"
VERSION_CODE="$(grep -oE 'android:versionCode="[^"]+"' AndroidManifest.xml | head -1 | sed 's/.*="//;s/"//')"
APK_NAME="KSUOneTap-$VERSION.apk"

echo "=== 工具链 ==="
echo "  SDK      : $SDK"
echo "  build-tools: $(basename "$BT_DIR")"
echo "  platform : $(basename "$PLATFORM_DIR")"
echo "  JDK      : $($JAVA_HOME/bin/java -version 2>&1 | head -1)"
echo "  kotlinc  : $(ls "$KOTLIN_HOME"/lib/kotlin-compiler*.jar 2>/dev/null | head -1)"
echo "  版本     : $VERSION (code $VERSION_CODE)"

if [ "${1:-}" = "--clean" ]; then
    /bin/rm -rf "$OUT"
    echo "  已清理 $OUT"
fi
mkdir -p "$OUT/obj" "$OUT/gen" "$OUT/classes" "$OUT/shizuku"

# ---------- 0. 管理器 APK 接入 ----------
# KSUOneTap 部署最后一步免 root 唤起系统安装器装 KernelSU 管理器 (官方 v3.3.0)。
# 2026-10-06 换原版: assets/KernelSU.apk = 官方 KernelSU_v3.3.0-release.apk
# (原版 ko 内嵌的管理器签名白名单只认官方签名 —— 不能用重打包版)。
echo "=== [0] 管理器 APK ==="
MG="${MANAGER_APK:-}"
if [ -z "$MG" ]; then
    MG="$(ls -t "$PROJ"/ReSukiSU-manager-*.apk "$PROJ/.."/ReSukiSU-manager-*.apk \
             "$PROJ/../.."/*manager*.apk 2>/dev/null | head -1)"
fi
if [ -n "$MG" ] && [ -f "$MG" ]; then
    cp -f "$MG" assets/KernelSU.apk
    echo "    已接入: $MG"
else
    echo "    未指定 MANAGER_APK -> 沿用 assets/KernelSU.apk 现有那份 (官方 v3.3.0)"
fi
MGVER="$("$BT_DIR/aapt2" dump badging assets/KernelSU.apk 2>/dev/null | grep -m1 '^package' | sed 's/.*versionCode=.\([0-9]*\).*/\1/')"
echo "    当前管理器 versionCode: $MGVER"
# 管理器的 versionCode 与驱动的 KSU_VERSION 是**同一套版本号**，应当一致。
# 期望值从 assets/<版本>/SYSTEM.txt 的 `kernelsu=` 行取（驱动的单一来源），不写死。
EXPECT_MGVER="$(grep -hoE '^kernelsu=[0-9]+$' assets/*/SYSTEM.txt 2>/dev/null | head -1 | cut -d= -f2)"
EXPECT_MGVER="${EXPECT_MGVER:-32601}"
if [ "$MGVER" = "$EXPECT_MGVER" ]; then
    echo "    ✓ 与驱动 KSU_VERSION 一致 ($MGVER)"
    echo "      (驱动 $EXPECT_MGVER / ksud $(strings assets/ksud 2>/dev/null | grep -oE '[0-9]+\.[0-9]+\.[0-9]+(-rc[0-9]+)?(-[0-9]+-g[0-9a-f]+)?' | sort -u | head -1))"
else
    echo "    ! 管理器 versionCode=$MGVER, 但驱动 KSU_VERSION=$EXPECT_MGVER"
    echo "      同步: bash scripts/fetch-manager.sh --to-assets（会一并重提 ksud）"
fi
echo

# ---------- 1. aapt2 compile ----------
echo "=== [1] aapt2 compile res ==="
"$BT_DIR/aapt2" compile --dir res -o "$OUT/obj/res.zip"

# ---------- 2. aapt2 link ----------
echo "=== [2] aapt2 link ==="
"$BT_DIR/aapt2" link \
    -o "$OUT/base.apk" \
    -I "$PLAT" \
    --manifest AndroidManifest.xml \
    --min-sdk-version 28 --target-sdk-version 34 \
    --java "$OUT/gen" \
    "$OUT/obj/res.zip" \
    -A assets --auto-add-overlay

# ---------- 3. kotlinc ----------
echo "=== [3] kotlinc (Kotlin -> JVM class) ==="
# 源码按职责分三个包: core/ (执行原语 / 设备判定 / 设置) + deploy/ (部署流程 + 终端)
#                    + ui/ (三个 Activity + UiKit)
# 0 字节的 .kt 会被跳过 (防御性): 源码迁移时被沙箱锁住的旧路径会留下空占位, 空文件对
# 编译无害, 但排除掉更干净, 也能顺带确认迁移已完成。2026-09-26 清理时那些空壳已全删,
# 这段留着防将来再出现 —— 真出现时构建日志会点名, 不会静默混进编译。
EMPTY_KT="$(find src -name '*.kt' ! -size +0c | sort)"
if [ -n "$EMPTY_KT" ]; then
    echo "    跳过 0 字节空壳: $(echo "$EMPTY_KT" | tr '\n' ' ')"
fi
# 不用 < <(...) 进程替换 (部分精简环境没有 /dev/fd)
KT_SRC=()
while IFS= read -r f; do
    [ -n "$f" ] && KT_SRC+=("$f")
done <<EOF
$(find src -name '*.kt' -size +0c | sort)
EOF
[ "${#KT_SRC[@]}" -gt 0 ] || { echo "  没有找到 .kt 源文件"; exit 1; }
echo "    源文件: ${#KT_SRC[@]} 个"
# JVM 参数: 本机（沙箱）有两道硬限制，默认值都过不去 ——
#   ① ulimit -v = 4GB: JVM 默认按物理内存 1/4 预留堆，且 CompressedOops 把堆钉在低 4GB
#      → 之后原生堆长不动，报 "failed to reserve memory for metaspace"。
#      解法 = 降 Xmx + `-XX:-UseCompressedOops`（JVM 崩溃日志自己给的建议）。
#   ② ulimit -u = 4096 且是**整机同 uid 共享**（本机常年已有 ~2500 线程，浏览器/编辑器都算），
#      实测单进程只能再开 ~60 个线程 → 按核数开线程的组件会报
#      "unable to create native thread" / "Failed to start the native thread"。
#      解法 = `-XX:ActiveProcessorCount=1`（同时压掉并行度），
#      外加把 JDK 21 的**虚拟线程调度器**载体线程数也钉到 1
#      （R8/d8 的失败栈里就是 jdk.internal.vm.SharedThreadContainer，它按处理器数开载体线程）。
# 需要不同取值时用 KOTLINC_JAVA_OPTS= / D8_JAVA_OPTS= 覆盖。
JVM_FRUGAL="-Xmx1500m -XX:-UseCompressedOops -XX:MaxMetaspaceSize=512m -XX:ReservedCodeCacheSize=64m -XX:+UseSerialGC -XX:ActiveProcessorCount=1 -Xss512k"
JVM_VT="-Djdk.virtualThreadScheduler.parallelism=1 -Djdk.virtualThreadScheduler.maxPoolSize=1 -Djava.util.concurrent.ForkJoinPool.common.parallelism=1"
# 给 d8 / apksigner 这类 wrapper 用的 -J 前缀版（wrapper 自己补那个 '-'，所以这里去掉前导 '-'）
JAVA_OPTS_J=""
for o in $JVM_FRUGAL $JVM_VT; do JAVA_OPTS_J="$JAVA_OPTS_J -J${o#-}"; done
KOTLINC_JAVA_OPTS="${KOTLINC_JAVA_OPTS:-$JVM_FRUGAL $JVM_VT}"
# shellcheck disable=SC2086
"$JAVA_HOME/bin/java" $KOTLINC_JAVA_OPTS -cp "$KOTLIN_HOME/lib/*" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
    -kotlin-home "$KOTLIN_HOME" \
    -jvm-target 1.8 \
    -nowarn \
    -classpath "$PLAT" \
    -d "$OUT/classes" \
    "$OUT/gen/com/neoroot/ksuonetap/R.java" "${KT_SRC[@]}"

# ---------- 4. 解出 shizuku 依赖的 class (编译期不依赖, 运行期需要) ----------
echo "=== [4] 解出 shizuku deps/*.aar ==="
# d8 对目录里的 .kotlin_module 等非 class 文件不友好, 统一打成 jar 再喂给它
( cd "$OUT/classes" && zip -q -r "$OUT/obj/classes.jar" . )
D8_IN=("$OUT/obj/classes.jar")

for aar in deps/*.aar; do
    [ -f "$aar" ] || continue
    n="$(basename "$aar" .aar)"
    mkdir -p "$OUT/shizuku/$n"
    ( cd "$OUT/shizuku/$n" && unzip -q -o "$PROJ/$aar" 'classes.jar' && mkdir -p cls && \
      cd cls && unzip -q -o ../classes.jar && /bin/rm -f ../classes.jar && \
      zip -q -r "../$n.jar" . )
    echo "    $n"
    D8_IN+=("$OUT/shizuku/$n/$n.jar")
done

# ---------- 5. d8 -> classes.dex ----------
echo "=== [5] d8 (class -> dex) ==="
# 同 kotlinc: d8 默认 -Xmx2G, 且会按核数开工作线程 —— 本机的两道限制都过不去。
# d8 的 wrapper 用 `-J<opt>` 透传（注意：J 后面直接跟选项正文，wrapper 自己补那个 '-'；
# 写成 -J-Xmx… 会变成 --Xmx… 而被 JVM 拒绝）。所以这里把 JVM_FRUGAL 每项去掉前导 '-'
# 再加 -J 前缀。D8_JAVA_OPTS= 可覆盖。
D8_JAVA_OPTS="${D8_JAVA_OPTS:-$JAVA_OPTS_J}"
# Kotlin 标准库必须一起进 dex (Android 平台没有 kotlin.*)
STDLIB="$KOTLIN_HOME/lib/kotlin-stdlib.jar"
[ -f "$STDLIB" ] && D8_IN+=("$STDLIB")
# shellcheck disable=SC2086
"$BT_DIR/d8" $D8_JAVA_OPTS --lib "$PLAT" --min-api 28 --output "$OUT/obj" "${D8_IN[@]}"

# ---------- 6. 把 classes.dex 塞进 base.apk ----------
echo "=== [6] 打包 dex ==="
cp -f "$OUT/base.apk" "$OUT/unsigned.apk"
( cd "$OUT" && zip -q -j unsigned.apk obj/classes.dex )

# ---------- 6b. GhostLock exploit 二进制 (lib/arm64-v8a) ----------
# GhostLock 版本 (CVE-2026-43499) 的 exploit 随 APK 打包, 安装后从
# nativeLibraryDir 直接执行 —— 无需 Shizuku 部署。前提: manifest 未设
# extractNativeLibs=false (默认 true, PackageManager 会提取到 nativeLibraryDir)。
# ⚠️ APK 内路径必须是 lib/<abi>/ —— PackageManager 只认这个前缀
#    (jniLibs/arm64-v8a 会被无视, primaryCpuAbi=null, so 一个都不提取)。
echo "=== [6b] 打包 lib/arm64-v8a (GhostLock exploit) ==="
if [ -d "$PROJ/jniLibs/arm64-v8a" ]; then
    mkdir -p "$OUT/lib/arm64-v8a"
    cp -f "$PROJ"/jniLibs/arm64-v8a/*.so "$OUT/lib/arm64-v8a/"
    ( cd "$OUT" && zip -q unsigned.apk lib/arm64-v8a/*.so )
    echo "    已打包: $(ls "$OUT/lib/arm64-v8a/" | tr '\n' ' ')"
else
    echo "    (无 jniLibs/arm64-v8a/, 跳过)"
fi

# ---------- 7. zipalign ----------
echo "=== [7] zipalign ==="
"$BT_DIR/zipalign" -f 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

# ---------- 8. 签名 ----------
# 签名一致性很重要: 设备上已装的包若与新包签名不同, `adb install -r` 会被拒
# (INSTALL_FAILED_UPDATE_INCOMPATIBLE), 只能卸载重装。
# 证书 SHA-256: 本工程 .keystore 产的 = 94122eb6... (设备上现装的 v1.0.5 / v1.0.6 都是它);
# 早期 v1.0.4 用的是 64572e28...
# 对应的 keystore 就是当年 PS1 在 out-onetap 目录生成的那个。
# 用 KEYSTORE=/path/to/ksuonetap.keystore 指定它即可产出可原地升级的包。
echo "=== [8] 签名 ==="
# keystore 放在 out/ 之外: `--clean` 不该把签名密钥删掉 (否则每次重建都换签名,
# 就永远无法 adb install -r 原地升级)。
KS="${KEYSTORE:-$PROJ/.keystore/ksuonetap.keystore}"
KS_PASS="${KS_PASS:-ksupass}"
KEY_PASS="${KEY_PASS:-ksupass}"
mkdir -p "$(dirname "$KS")"
if [ ! -f "$KS" ]; then
    "$JAVA_HOME/bin/keytool" -genkeypair -keystore "$KS" -alias ksu -keyalg RSA -keysize 2048 \
        -validity 10000 -storepass "$KS_PASS" -keypass "$KEY_PASS" \
        -dname "CN=KSUOneTap" -noprompt
    echo "    !! 未找到既有 keystore, 已新生成一个: $KS"
    echo "    !! 新 keystore 的证书与设备上已装的包**不同**, adb install -r 会被拒。"
    echo "    !! 想原地升级请用 KEYSTORE=<原 keystore> 重新构建。"
fi
echo "    keystore: $KS"
# apksigner 也是 JVM 工具 (默认 -Xmx1G), 同样要吃那套省线程参数 (见上面 JVM_FRUGAL 的说明)
APKSIGNER_JAVA_OPTS="${APKSIGNER_JAVA_OPTS:-$JAVA_OPTS_J}"
# shellcheck disable=SC2086
"$BT_DIR/apksigner" $APKSIGNER_JAVA_OPTS sign --ks "$KS" --ks-pass "pass:$KS_PASS" --key-pass "pass:$KEY_PASS" \
    --out "$OUT/$APK_NAME" "$OUT/aligned.apk"

# ---------- 9. 校验 ----------
echo "=== [9] 校验 ==="
# shellcheck disable=SC2086
CERT="$("$BT_DIR/apksigner" $APKSIGNER_JAVA_OPTS verify --print-certs "$OUT/$APK_NAME" 2>/dev/null | grep -i 'SHA-256 digest' | head -1 | sed 's/.*digest: *//')"
echo "    证书 SHA-256: $CERT"
# 目标证书: 默认是本工程 .keystore/ksuonetap.keystore 的证书 (设备上当前装的 v1.0.5 / v1.0.6 都是它)。
# 换过 keystore 或设备上装的是别的签名 (例如旧 v1.0.4 = 64572e28...) 时用 EXPECT_SIG= 覆盖:
#   EXPECT_SIG=$(adb shell pm path com.neoroot.ksuonetap | sed 's/package://' | tr -d '\r' \
#                | xargs -I{} sh -c 'adb pull {} /tmp/x.apk >/dev/null && apksigner verify --print-certs /tmp/x.apk')
EXPECT_SIG="${EXPECT_SIG:-94122eb62b635a29653dd03baf2a50b167b46879656c65505fcb6d30c31101bb}"
if [ "$CERT" = "$EXPECT_SIG" ]; then
    echo "    ✓ 与目标证书一致 (设备上若是同一签名, 可直接 adb install -r 原地升级)"
else
    echo "    ✗ 与目标证书不同 (装了不同签名的包时会 INSTALL_FAILED_UPDATE_INCOMPATIBLE)"
    echo "      目标证书: $EXPECT_SIG"
fi
echo
echo "  产物: $OUT/$APK_NAME  ($(stat -c%s "$OUT/$APK_NAME") 字节, sha256 $(sha256sum "$OUT/$APK_NAME" | cut -c1-16))"
"$BT_DIR/aapt2" dump badging "$OUT/$APK_NAME" 2>/dev/null | grep -E "^package|launchable-activity" | head -3

# ---------- 10. 清理中间产物 (省约 60MB; 设 KEEP_INTERMEDIATE=1 可保留) ----------
if [ "${KEEP_INTERMEDIATE:-0}" != "1" ]; then
    echo
    echo "=== [10] 清理中间产物 ==="
    # 用真实 rm: 有些环境把 `rm` 包装成"移到回收站"(既占空间又不释放磁盘)
    RM=/bin/rm; [ -x "$RM" ] || RM=rm
    "$RM" -rf "$OUT/base.apk" "$OUT/unsigned.apk" "$OUT/aligned.apk" \
           "$OUT/obj" "$OUT/shizuku" "$OUT/classes" "$OUT/gen" "$OUT/$APK_NAME.idsig"
    echo "    已清中间产物, 仅保留 $APK_NAME (重编会重新生成; KEEP_INTERMEDIATE=1 可保留)"
fi
