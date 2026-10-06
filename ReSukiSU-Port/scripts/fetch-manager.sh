#!/bin/bash
# fetch-manager.sh - 获取 ReSukiSU 管理器 APK（main 分支最新构建）
#
# 为什么需要这个脚本：
#   官方**不在 GitHub Release 发布管理器**（原文：「ReSukiSU 暂时不会发布至 GitHub Release」），
#   所以 `git ls-remote` / Releases API 里能看到的 tag 资产总是滞后的
#   （实测发布 tag v4.2.0-rc3 的资产只到 35171，而 main 上已有 35184）。
#   main 分支的最新构建走这两条渠道：
#     nightly.link（无需登录）   https://nightly.link/ReSukiSU/ReSukiSU/workflows/build-manager/main/Manager-release.zip
#     GitHub Actions（需登录）   https://github.com/ReSukiSU/ReSukiSU/actions/workflows/build-manager.yml?query=branch%3Amain
#   文档：https://resukisu.org/zh-Hans/guide/install.html
#
# 用法：
#   bash scripts/fetch-manager.sh                    # 下载并解出 arm64-v8a -> apk/
#   ARCH=universal bash scripts/fetch-manager.sh     # 换架构（arm64-v8a/armeabi-v7a/x86_64/riscv64/universal）
#   bash scripts/fetch-manager.sh --to-assets        # 顺带替换 assets/ 的 manager **并重新提取 ksud**
#   bash scripts/fetch-manager.sh --ksud             # 只从现有 manager APK 重新提取 ksud（修版本漂移）
#   bash scripts/fetch-manager.sh --from-zip FILE    # 用已有的 Manager-release.zip（nightly.link 404 时）
#
# 为什么顺带管 ksud：ksud 不在本仓库里编译，它是**从 manager APK 的
#   lib/<arch>/libksud.so 提取**出来的，却有自己独立的版本号（KSU_VERSION 里的
#   `<n>-g<sha>`）。所以一旦只换 manager 不重提 ksud，就会留下"manager 是新的、
#   ksud 是旧的"这种漂移 —— 2026-09-27 整理时就发现过一次（manager/ko = 35179，
#   assets/ksud 还停在 35140）。ksud 的 UAPI 校验是严格 `!=`，真踩上就全部操作被拒。
#   提取结果会与 `userspace/ksud` 一起更新，可用 ./build.sh sync 核验。
#
# 产物文件名自带版本号，形如 ReSukiSU_v4.2.0-rc3_35179-arm64-v8a-release.apk
set -u

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ARCH="${ARCH:-arm64-v8a}"
OUT="${OUT:-$ROOT/apk}"
URL="${URL:-https://nightly.link/ReSukiSU/ReSukiSU/workflows/build-manager/main/Manager-release.zip}"
ZIP=""
TO_ASSETS=0
KS_ONLY=0

# ---------------- ksud 提取 ----------------
# ksud 不在本仓库编译, 它是 manager APK 里的 lib/<arch>/libksud.so（管理器安装时也
# 就是把它复制成 /data/adb/ksu/bin/ksud）。因为带独立版本号, 换 manager 必须重提一次,
# 否则会留下 "manager 是新的 / ksud 是旧的" 这种漂移。
extract_ksud() {
    local apk="$1" libdir="lib/${ARCH}"
    local dst_a="$ROOT/apk/ksuonetap/assets/ksud"
    local dst_u="$ROOT/userspace/ksud"

    echo
    echo "=== 提取 ksud ==="
    echo "  来源: $apk  ($libdir/libksud.so)"
    command -v unzip >/dev/null 2>&1 || { echo "  !! 缺 unzip, 跳过" >&2; return 1; }
    if ! unzip -l "$apk" "$libdir/libksud.so" >/dev/null 2>&1; then
        echo "  !! 该 APK 里没有 $libdir/libksud.so（架构不对? 试 ARCH=universal）" >&2
        return 1
    fi

    local tmp="$OUT/.ksud.tmp.$$"
    /bin/rm -rf "$tmp"; mkdir -p "$tmp" || return 1
    if ! unzip -o -j "$apk" "$libdir/libksud.so" -d "$tmp" >/dev/null 2>&1; then
        echo "  !! 解包失败" >&2; /bin/rm -rf "$tmp"; return 1
    fi
    chmod 644 "$tmp/libksud.so"

    if [ -f "$dst_a" ] && ! cmp -s "$tmp/libksud.so" "$dst_a"; then
        echo "  注意: assets/ksud 将被覆盖（旧版 md5 $(md5sum "$dst_a" | cut -c1-12)）"
        echo "        要保留旧版先把它 cp 到 apk/archive/ksud-<版本串>（命名见该目录 README）"
    fi

    mkdir -p "$(dirname "$dst_u")"
    cp -f "$tmp/libksud.so" "$dst_a"
    cp -f "$tmp/libksud.so" "$dst_u"
    /bin/rm -rf "$tmp"

    local ver uapi
    ver="$(strings "$dst_a" 2>/dev/null | grep -oE '4\.[0-9]+\.[0-9]+-rc[0-9]+-[0-9]+-g[0-9a-f]+' | sort -u | head -1)"
    uapi="$(strings "$dst_a" 2>/dev/null | grep -oE '\(uapi: [0-9]+\)' | sort -u | head -1)"
    echo "  -> $dst_a"
    echo "     $dst_u"
    echo "     md5 $(md5sum "$dst_a" | cut -d' ' -f1)"
    echo "     版本 ${ver:-（未解析到）}  ${uapi:-}"
    echo
    echo "  提醒: 驱动侧 KERNEL_SU_UAPI_VERSION 见 source/resukisu/uapi/supercall.h,"
    echo "        ksud 的校验是严格 != —— 两者不等会拒绝所有操作。核验: ./build.sh sync"
    return 0
}

while [ $# -gt 0 ]; do
    case "$1" in
        --from-zip) ZIP="${2:-}"; shift 2 ;;
        --to-assets) TO_ASSETS=1; shift ;;
        --ksud) KS_ONLY=1; shift ;;
        -h|--help) sed -n '2,30p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *) echo "未知参数: $1（-h 看用法）" >&2; exit 2 ;;
    esac
done

if [ "$KS_ONLY" = 1 ]; then
    KS_APK="$ROOT/apk/ksuonetap/assets/resukisu-manager.apk"
    if [ ! -f "$KS_APK" ]; then
        KS_APK="$(ls -t "$ROOT"/apk/ReSukiSU_*.apk 2>/dev/null | head -1)"
    fi
    if [ -z "${KS_APK:-}" ] || [ ! -f "$KS_APK" ]; then
        echo "!! 找不到 manager APK（先 --to-assets 拉一份, 或把 APK 放到 apk/）" >&2
        exit 1
    fi
    extract_ksud "$KS_APK" || exit 1
    exit 0
fi

TMP="${WRK:-$OUT/.fetch-manager.tmp}"
/bin/rm -rf "$TMP"
mkdir -p "$TMP" || { echo "!! 无法创建临时目录 $TMP" >&2; exit 1; }
trap '/bin/rm -rf "$TMP"' EXIT
# 注意：不要用 /tmp —— Manager-release.zip 有 40MB+，很多环境（含本项目开发机）
#       的 /tmp 是 10MB tmpfs，解包会 "write error (disk full?)"。

if [ -z "$ZIP" ]; then
    ZIP="$TMP/Manager-release.zip"
    echo "=== 下载 main 分支最新构建 ==="
    echo "  $URL"
    if ! curl -sSL --retry 4 --retry-delay 3 -o "$ZIP" "$URL"; then
        echo "!! 下载失败。nightly.link 返回 404 通常意味着最近没有成功的 CI run。" >&2
        echo "   改用 GitHub Actions 页面手动下载 Manager-release.zip，然后：" >&2
        echo "     bash scripts/fetch-manager.sh --from-zip /path/to/Manager-release.zip" >&2
        exit 1
    fi
    # nightly.link 在无产物时会回一个 HTML 404 页，这里挡一下
    if ! unzip -tq "$ZIP" >/dev/null 2>&1; then
        echo "!! 下载到的不是有效 zip（多半是 404 页面）。改用 --from-zip 手动提供。" >&2
        exit 1
    fi
fi

echo "=== 解包 ==="
unzip -q -o "$ZIP" -d "$TMP/x" || { echo "!! 解包失败" >&2; exit 1; }
echo "  zip 内 APK:"
find "$TMP/x" -name '*.apk' -printf '    %f  (%s 字节)\n' | sort

SRC="$(find "$TMP/x" -name "*-${ARCH}-release.apk" | head -1)"
if [ -z "$SRC" ]; then
    echo "!! 没找到 *-${ARCH}-release.apk" >&2
    echo "   可选: arm64-v8a armeabi-v7a x86_64 riscv64 universal（用 ARCH= 指定）" >&2
    exit 1
fi

BASE="$(basename "$SRC")"
VER="$(echo "$BASE" | grep -oE '_[0-9]{4,6}-' | tr -d '_' | tr -d '-')"
mkdir -p "$OUT"
cp -f "$SRC" "$OUT/$BASE"

echo
echo "=== 结果 ==="
echo "  -> $OUT/$BASE"
echo "     $([ -n "$VER" ] && echo "versionCode $VER（从文件名解析）" || echo "版本号未从文件名解析到")"
echo "     md5 $(md5sum "$OUT/$BASE" | cut -d' ' -f1)"
AS="$(command -v apksigner 2>/dev/null || ls -d "${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"/build-tools/*/apksigner 2>/dev/null | sort -V | tail -1)"
if [ -n "$AS" ] && [ -x "$AS" ]; then
    SIG="$("$AS" verify --print-certs "$OUT/$BASE" 2>/dev/null | grep -i 'SHA-256' | head -1 | sed 's/.*: *//')"
    echo "     签名 ${SIG:-（读取失败：apksigner 需要 JDK，试试 export JAVA_HOME=...）}"
else
    echo "     签名 （未找到 apksigner，跳过）"
fi

if [ "$TO_ASSETS" = 1 ]; then
    DEST="$ROOT/apk/ksuonetap/assets/resukisu-manager.apk"
    cp -f "$OUT/$BASE" "$DEST"
    echo "  已替换 $DEST"
    extract_ksud "$DEST" || echo "  !! ksud 提取失败 —— manager 与 ksud 现在可能版本不一致, 跑 ./build.sh sync 确认" >&2
    echo "  接着重新打包: bash apk/ksuonetap/build_ksuonetap.sh"
fi
