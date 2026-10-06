#!/bin/bash
# check-assets-sync.sh - 校验「源目录产物」与「apk/ksuonetap/assets/ 打包副本」是否一致
#
# 为什么需要它：
#   APK 打包直接读 apk/ksuonetap/assets/，但那些文件的**产出地**在别的目录
#   （neo9-root/、kernel-module/、test-modules/、userspace/）。同一份文件存在两处，
#   于是就有了"改了源、忘了同步 assets"的风险 —— 而它**不会以任何形式报错**，
#   只会在真机上表现为"怎么改都没生效"。这个脚本就是那道闸门。
#
# 多版本 + 档位：
#   assets/ 下可以有多个系统版本目录。每个目录的**适配档位**写在它自己的 SYSTEM.txt
#   的 `tier=` 行：
#     tier=full     绑定产物必须齐备（exploit + kernelsu-vivo.ko + unpatch.ko + vrpatch.ko）
#     tier=lpe-su   只适配 LPE + 临时 root：**只有** exploit_vivo_neo9
#   本脚本核对三件事互相覆盖：assets 目录 ↔ SYSTEM.txt 的 tier ↔ DeviceGate 的两个清单
#   （ADAPTED_BUILDS = full，LPE_ONLY_BUILDS = lpe-su）。
#
# 用法：
#   bash scripts/check-assets-sync.sh          # 校验（不一致 => 退出码 1）
#   bash scripts/check-assets-sync.sh -v       # 打印全部 md5
#   ./build.sh sync                            # 同上（build.sh 的入口）
#   bash scripts/check-assets-sync.sh --print-ver    # 主适配版本（有完整模块的那一版）
#   bash scripts/check-assets-sync.sh --print-vers   # 全部适配版本（每行一个）
set -u

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
A="$ROOT/apk/ksuonetap/assets"
RF="$ROOT/apk/ksuonetap/src/root_files"

# 「主适配版本」= 有完整内核模块的那一版（build.sh 的 doctor/modules 取它）
PRIMARY_VER="PD2338_A_15.1.14.7.W10.V000L1"

# 版本绑定件表（必须在下面 --print-vers 之前定义）:
#   <版本目录>|<assets 内文件名>|<仓库内源路径>|<说明>
# 每个版本目录**必须**与 SYSTEM.txt 的 tier 声明的产物集合完全一致（多一件少一件都报）。
# 同一版本的四件允许来自不同位置：**新固件的产物落 `<组件>/out/<版本>/`**，
# 已固化的（无对应源码树可重编、只能沿用旧产物）就直接指向模块目录里的正本。
VERSIONED="
PD2338_A_15.1.14.7.W10.V000L1|exploit_vivo_neo9|neo9-root/exploit/out/PD2338_A_15.1.14.7.W10.V000L1/exploit_vivo_neo9_stable_su_ndk13|临时 root exploit (neo9-root/exploit/out/, 系统版本绑定)
PD2338_A_15.1.14.7.W10.V000L1|kernelsu-vivo.ko|kernel-module/kernelsu-vivo.ko|KSU 驱动 (kernel-module/, 系统版本绑定)
PD2338_A_15.1.14.7.W10.V000L1|unpatch.ko|test-modules/unpatch/unpatch.ko|恢复 cap_bprm (test-modules/unpatch/, 系统版本绑定)
PD2338_A_15.1.14.7.W10.V000L1|vrpatch.ko|test-modules/vrpatch/vrpatch.ko|中和 vr.ko (test-modules/vrpatch/, 系统版本绑定)
PD2338_A_14.0.17.2.W10.V000L1|exploit_vivo_neo9|neo9-root/exploit/out/PD2338_A_14.0.17.2.W10.V000L1/exploit_vivo_neo9_stable_su_ndk13|临时 root exploit (neo9-root/exploit/out/, 系统版本绑定)
PD2338_A_14.0.17.2.W10.V000L1|kernelsu-vivo.ko|kernel-module/out/PD2338_A_14.0.17.2.W10.V000L1/kernelsu-vivo.ko|KSU 驱动 (vermagic 等长对齐到 137; kernel-module/out/)
PD2338_A_14.0.17.2.W10.V000L1|unpatch.ko|test-modules/out/PD2338_A_14.0.17.2.W10.V000L1/unpatch.ko|恢复 cap_bprm (test-modules/out/, 137 profile)
PD2338_A_14.0.17.2.W10.V000L1|vrpatch.ko|test-modules/out/PD2338_A_14.0.17.2.W10.V000L1/vrpatch.ko|中和 vr.ko (test-modules/out/, 137 profile)
PD2338_A_14.0.17.6.W10.V000L1|exploit_vivo_neo9|neo9-root/exploit/out/PD2338_A_14.0.17.6.W10.V000L1/exploit_vivo_neo9_stable_su_ndk13|临时 root exploit (neo9-root/exploit/out/, 系统版本绑定)
PD2338_A_14.0.17.6.W10.V000L1|kernelsu-vivo.ko|kernel-module/out/PD2338_A_14.0.17.6.W10.V000L1/kernelsu-vivo.ko|KSU 驱动 (vermagic 等长对齐到 137-g7cb3e06b062c; kernel-module/out/)
PD2338_A_14.0.17.6.W10.V000L1|unpatch.ko|test-modules/out/PD2338_A_14.0.17.6.W10.V000L1/unpatch.ko|恢复 cap_bprm (test-modules/out/, 137/17.6 profile)
PD2338_A_14.0.17.6.W10.V000L1|vrpatch.ko|test-modules/out/PD2338_A_14.0.17.6.W10.V000L1/vrpatch.ko|中和 vr.ko (test-modules/out/, 137/17.6 profile)
"

VERBOSE=0
[ "${1:-}" = "-v" ] && VERBOSE=1
if [ "${1:-}" = "--print-ver" ]; then echo "$PRIMARY_VER"; exit 0; fi
if [ "${1:-}" = "--print-vers" ]; then
    printf '%s\n' "$VERSIONED" | awk -F'|' 'NF>1 && $1!="" {print $1}' | sort -u
    exit 0
fi
if [ "${1:-}" = "-h" ] || [ "${1:-}" = "--help" ]; then
    sed -n '2,20p' "$0" | sed 's/^# \{0,1\}//'
    echo "  --print-ver   只打印主适配版本号（= 有完整内核模块的那一版）"
    echo "  --print-vers  打印全部适配版本（每行一个）"
    exit 0
fi

ok()   { printf '  \033[32mOK  \033[0m %s\n' "$*"; }
bad()  { printf '  \033[31m差异\033[0m %s\n' "$*"; }
warn() { printf '  \033[33m警告\033[0m %s\n' "$*"; }

# ---- 通用件（不随系统版本走，放 assets 顶层）----
COMMON="
neo9-root/client/u0:u0:提权辅助 (neo9-root/client/, 通用)
userspace/su_ksu:su_ksu:KSU su 客户端 (userspace/, 通用)
userspace/ksud:ksud:KSU 用户态 (userspace/, 源自 manager APK, 通用)
"

echo "=== assets 同步校验 (源目录 <-> apk/ksuonetap/assets/) ==="
n=0; miss=0; diff=0
while IFS= read -r line; do
    [ -z "$line" ] && continue
    src="${line%%:*}"; rest="${line#*:}"
    name="${rest%%:*}"; desc="${rest#*:}"
    if [ ! -f "$src" ]; then
        warn "源文件不存在: ${src#$ROOT/}   ($desc)"; miss=$((miss+1)); continue
    fi
    if [ ! -f "$A/$name" ]; then
        bad "assets/$name 缺失   ($desc)"; diff=$((diff+1)); continue
    fi
    m1="$(md5sum "$src" | cut -d' ' -f1)"
    m2="$(md5sum "$A/$name" | cut -d' ' -f1)"
    if [ "$m1" = "$m2" ]; then
        n=$((n+1))
        [ "$VERBOSE" = 1 ] && ok "$name  ${m1:0:12}  ($desc)" || true
    else
        bad "$name"
        echo "        源:      ${src#$ROOT/}  ${m1:0:12}"
        echo "        assets:  apk/ksuonetap/assets/$name  ${m2:0:12}"
        echo "        修法:    cp -f '${src#$ROOT/}' apk/ksuonetap/assets/$name"
        diff=$((diff+1))
    fi
done <<EOF
$COMMON
EOF
while IFS='|' read -r ver name rsrc desc; do
    [ -z "${ver:-}" ] && continue
    src="$ROOT/$rsrc"
    full="$ver/$name"
    if [ ! -f "$src" ]; then
        warn "源文件不存在: ${rsrc}   ($desc)"; miss=$((miss+1)); continue
    fi
    if [ ! -f "$A/$full" ]; then
        bad "assets/$full 缺失   ($desc)"; diff=$((diff+1)); continue
    fi
    m1="$(md5sum "$src" | cut -d' ' -f1)"
    m2="$(md5sum "$A/$full" | cut -d' ' -f1)"
    if [ "$m1" = "$m2" ]; then
        n=$((n+1))
        [ "$VERBOSE" = 1 ] && ok "$full  ${m1:0:12}" || true
    else
        bad "$full"
        echo "        源:      ${rsrc}  ${m1:0:12}"
        echo "        assets:  apk/ksuonetap/assets/$full  ${m2:0:12}"
        echo "        修法:    cp -f '$rsrc' apk/ksuonetap/assets/$full"
        diff=$((diff+1))
    fi
done <<EOF
$VERSIONED
EOF

# ---- 每个版本目录: SYSTEM.txt 的 tier ↔ 目录里实际有哪些文件 ↔ 版本表 ----
declare -A TIER_OF
for vdir in $(find "$A" -mindepth 1 -maxdepth 1 -type d -printf '%f\n' 2>/dev/null | sort); do
    VROOT="$A/$vdir"
    if [ ! -f "$VROOT/SYSTEM.txt" ]; then
        bad "assets/$vdir/SYSTEM.txt 缺失（系统版本标注 + 适配档位）"
        diff=$((diff+1)); continue
    fi
    tier="$(grep -m1 '^tier=' "$VROOT/SYSTEM.txt" | cut -d= -f2)"
    case "$tier" in
        full)   want="exploit_vivo_neo9 kernelsu-vivo.ko unpatch.ko vrpatch.ko" ;;
        lpe-su) want="exploit_vivo_neo9" ;;
        *)      bad "assets/$vdir/SYSTEM.txt 的 tier 非法: '${tier:-空}'（只允许 full / lpe-su）"
                diff=$((diff+1)); continue ;;
    esac
    TIER_OF[$vdir]="$tier"
    # 目录内容必须与 tier 的产物集合**恰好一致**
    have="$(find "$VROOT" -mindepth 1 -maxdepth 1 -type f ! -name SYSTEM.txt -printf '%f\n' | sort | tr '\n' ' ')"
    want_s="$(printf '%s\n' $want | sort | tr '\n' ' ')"
    if [ "$have" = "$want_s" ]; then
        [ "$VERBOSE" = 1 ] && ok "assets/$vdir/ 内容与 tier=$tier 一致 ($want_s)"
    else
        bad "assets/$vdir/ 内容与 tier=$tier 不符"
        echo "        期望: $want_s"
        echo "        实际: $have"
        echo "        修法: 删掉多余文件, 或补上缺的产物并更新 SYSTEM.txt 的 tier"
        diff=$((diff+1))
    fi
    # SYSTEM.txt 里声明的 md5 必须与目录内实际文件一致
    vn=0
    while read -r ln; do
        case "$ln" in md5.*) ;; *) continue ;; esac
        fname="${ln#md5.}"; fname="${fname%%=*}"; wantmd5="${ln#*=}"
        got="$(md5sum "$VROOT/$fname" 2>/dev/null | cut -d' ' -f1)"
        if [ -z "$got" ]; then
            bad "SYSTEM.txt 声明了 md5.$fname, 但 assets/$vdir/$fname 不存在"
            diff=$((diff+1)); continue
        fi
        if [ "$got" = "$wantmd5" ]; then
            vn=$((vn+1)); n=$((n+1))
            [ "$VERBOSE" = 1 ] && ok "版本标注 md5.$fname 一致"
        else
            bad "SYSTEM.txt 的 md5.$fname 与实际文件不符"
            echo "        实际:   $got"
            echo "        标注:   $wantmd5"
            echo "        修法:   更新 apk/ksuonetap/assets/$vdir/SYSTEM.txt"
            diff=$((diff+1))
        fi
    done < "$VROOT/SYSTEM.txt"
    # tier 要求的每一件都必须在 SYSTEM.txt 里被 md5 声明（防"放了文件但没标注"）
    for fname in $want; do
        grep -q "^md5\.$fname=" "$VROOT/SYSTEM.txt" || {
            bad "assets/$vdir/SYSTEM.txt 缺 md5.$fname 声明"
            diff=$((diff+1)); }
    done
    echo "  ---- assets/$vdir/: tier=$tier, 标注核对 $vn 项"
done

# ---- DeviceGate 的两个清单与 assets 目录必须互相覆盖 ----
KTF="$ROOT/apk/ksuonetap/src/com/neoroot/ksuonetap/core/DeviceGate.kt"
if [ -f "$KTF" ]; then
    parse_list() {  # $1 = 清单常量名
        awk -v nm="$1" '$0 ~ ("val " nm " *= *listOf") {f=1} f{print} f&&/\)/{exit}' "$KTF" \
            | grep -oE '"[A-Za-z0-9_.]+"' | tr -d '"' | sort -u
    }
    FULL_LIST="$(parse_list ADAPTED_BUILDS)"
    LPE_LIST="$(parse_list LPE_ONLY_BUILDS)"
    check_list() {  # $1=清单 $2=应有 tier
        local b tn=0
        while read -r b; do
            [ -z "$b" ] && continue
            if [ ! -d "$A/$b" ]; then
                bad "DeviceGate 声明 $b, 但 assets/$b/ 不存在"; diff=$((diff+1)); continue
            fi
            if [ "${TIER_OF[$b]:-}" != "$2" ]; then
                bad "DeviceGate 把 $b 列为 $2 档, 但 SYSTEM.txt 写的是 tier=${TIER_OF[$b]:-缺}"
                diff=$((diff+1)); continue
            fi
            tn=$((tn+1))
        done <<EOF
$1
EOF
        n=$((n+tn)); echo "  ---- DeviceGate 清单 ($2): $tn 项对齐"
    }
    check_list "$FULL_LIST" full
    check_list "$LPE_LIST" lpe-su
    # 反向: assets 下每个目录都必须在某个清单里
    for vdir in $(find "$A" -mindepth 1 -maxdepth 1 -type d -printf '%f\n' 2>/dev/null | sort -u); do
        if ! printf '%s\n%s\n' "$FULL_LIST" "$LPE_LIST" | grep -qx "$vdir"; then
            bad "assets/$vdir/ 存在, 但 DeviceGate 的 ADAPTED_BUILDS / LPE_ONLY_BUILDS 里都没有"
            diff=$((diff+1))
        fi
    done
    # 同一版本不许同时出现在两个清单
    for b in $LPE_LIST; do
        printf '%s\n' "$FULL_LIST" | grep -qx "$b" && {
            bad "$b 同时出现在 ADAPTED_BUILDS 与 LPE_ONLY_BUILDS 里"; diff=$((diff+1)); }
    done
fi

# ksud 的额外闸门: 它必须**等于 manager APK 里那一份**（提取而来的, 不是独立编译的）。
# 只比 assets 与 userspace 不够 —— 两者可能一起停在旧版（2026-09-27 踩过一次）。
MGR="$A/resukisu-manager.apk"
if [ -f "$MGR" ] && [ -f "$A/ksud" ]; then
    # 临时目录放 repo 内（apk/ 下）：不用 /tmp —— 本项目开发机的 /tmp 是 10MB tmpfs，
    # 而这要落一个 5.6MB 的 libksud.so；也不假定 /var/tmp 存在（沙箱里就没有）。
    TMP="$(mktemp -d 2>/dev/null || true)"
    [ -n "${TMP:-}" ] || { TMP="$ROOT/apk/.ksudsync.$$"; mkdir -p "$TMP" 2>/dev/null || TMP=""; }
    if [ -z "${TMP:-}" ]; then
        warn "无法创建临时目录，跳过 ksud ↔ manager 检查"
    elif unzip -o -j "$MGR" "lib/arm64-v8a/libksud.so" -d "$TMP" >/dev/null 2>&1 && [ -f "$TMP/libksud.so" ]; then
        if cmp -s "$TMP/libksud.so" "$A/ksud"; then
            mv="$(strings "$A/ksud" 2>/dev/null | grep -oE '4\.[0-9]+\.[0-9]+-rc[0-9]+-[0-9]+-g[0-9a-f]+' | sort -u | head -1)"
            n=$((n+1))
            [ "$VERBOSE" = 1 ] && ok "ksud == manager 内 libksud.so (${mv:-版本未解析})" || true
        else
            bad "ksud 与 manager APK 内的 libksud.so 不一致（版本漂移）"
            echo "        assets/ksud md5      $(md5sum "$A/ksud" | cut -c1-12)"
            echo "        manager 内那一份 md5 $(md5sum "$TMP/libksud.so" | cut -c1-12)"
            echo "        修法:    bash scripts/fetch-manager.sh --ksud"
            diff=$((diff+1))
        fi
    else
        warn "manager APK 里没有 lib/arm64-v8a/libksud.so，跳过该检查"
    fi
    [ -n "${TMP:-}" ] && /bin/rm -rf "$TMP"
fi

# manager 的版本提示（不参与判定，只是让人一眼看到随包的是哪个版本）
if [ -f "$MGR" ]; then
    BT="$(ls -d "${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"/build-tools/* 2>/dev/null | sort -V | tail -1)"
    if [ -n "$BT" ] && [ -x "$BT/aapt2" ]; then
        echo "  ---- 随包 manager: $("$BT/aapt2" dump badging "$MGR" 2>/dev/null | grep -m1 '^package' | grep -oE "versionCode='[0-9]+' versionName='[^']+'")"
    fi
fi

echo "=== 汇总 ==="
echo "  assets 一致 $n 项 · 缺失 $miss 项 · 不一致 $diff 项"

# ---- src/root_files 是"随包二进制源码"的镜像, 同样要防漂移 ----
echo
echo "=== root_files 源码镜像校验 (原处 <-> apk/ksuonetap/src/root_files/) ==="
MIRROR="
$ROOT/neo9-root/exploit/exploit_vivo.c:exploit_vivo_neo9/exploit_vivo.c
$ROOT/neo9-root/exploit/fw_profile.h:exploit_vivo_neo9/fw_profile.h
$ROOT/neo9-root/exploit/fw_profile.py:exploit_vivo_neo9/fw_profile.py
$ROOT/neo9-root/exploit/adrenaline.h:exploit_vivo_neo9/adrenaline.h
$ROOT/neo9-root/exploit/stubs/stub5.s:exploit_vivo_neo9/stubs/stub5.s
$ROOT/neo9-root/exploit/stubs/stub10.s:exploit_vivo_neo9/stubs/stub10.s
$ROOT/neo9-root/exploit/stubs/stub12.s:exploit_vivo_neo9/stubs/stub12.s
$ROOT/neo9-root/exploit/build_exploit_stable.sh:exploit_vivo_neo9/build_exploit_stable.sh
$ROOT/neo9-root/exploit/verify_bins.py:exploit_vivo_neo9/verify_bins.py
$ROOT/neo9-root/tools/fw_kernel_derive.py:exploit_vivo_neo9/fw_kernel_derive.py
$ROOT/neo9-root/tools/init_hook_derive.py:exploit_vivo_neo9/init_hook_derive.py
$ROOT/neo9-root/tools/erofs_extract_one.py:exploit_vivo_neo9/erofs_extract_one.py
$ROOT/neo9-root/client/u0.c:u0/u0.c
$ROOT/test-modules/unpatch/unpatch.c:unpatch.ko/unpatch.c
$ROOT/test-modules/unpatch/Makefile:unpatch.ko/Makefile
$ROOT/test-modules/unpatch/build.sh:unpatch.ko/build.sh
$ROOT/test-modules/vrpatch/vrpatch.c:vrpatch.ko/vrpatch.c
$ROOT/test-modules/vrpatch/Makefile:vrpatch.ko/Makefile
$ROOT/test-modules/vrpatch/build.sh:vrpatch.ko/build.sh
$ROOT/kernel-module/vivo.config:kernelsu-vivo.ko/vivo.config
$ROOT/scripts/build_ksu_module.sh:kernelsu-vivo.ko/build_ksu_module.sh
$ROOT/scripts/build_ksu_lto2.sh:kernelsu-vivo.ko/build_ksu_lto2.sh
$ROOT/scripts/patch_ko_version.py:kernelsu-vivo.ko/patch_ko_version.py
$ROOT/scripts/patch_ko_vermagic.py:kernelsu-vivo.ko/patch_ko_vermagic.py
$ROOT/patches/extract_integration.sh:kernelsu-vivo.ko/extract_integration.sh
$ROOT/patches/kernel-integration.patch:kernelsu-vivo.ko/kernel-integration.patch
$ROOT/userspace/su_patched.c:su_ksu/su_patched.c
"
mn=0; mdiff=0
while IFS= read -r line; do
    [ -z "$line" ] && continue
    src="${line%%:*}"; dst="${line#*:}"
    if [ ! -f "$src" ] || [ ! -f "$RF/$dst" ]; then
        bad "$dst  （原处或镜像缺文件）"; mdiff=$((mdiff+1)); continue
    fi
    if cmp -s "$src" "$RF/$dst"; then
        mn=$((mn+1))
        [ "$VERBOSE" = 1 ] && ok "$dst"
    else
        bad "$dst  镜像与源不一致"
        echo "        源:   ${src#$ROOT/}"
        echo "        镜像: apk/ksuonetap/src/root_files/$dst"
        echo "        修法: cp -f '${src#$ROOT/}' 'apk/ksuonetap/src/root_files/$dst'"
        mdiff=$((mdiff+1))
    fi
done <<EOF
$MIRROR
EOF
echo "  一致 $mn 项 · 不一致 $mdiff 项"
diff=$((diff + mdiff))

echo "=== 总汇总 ==="
if [ "$diff" -gt 0 ] || [ "$miss" -gt 0 ]; then
    echo "  !! 打包副本 / 源码镜像与源目录不同步 —— APK 里会是旧文件, 或 root_files 的说明与实际不符"
    exit 1
fi
echo "  全部一致"
exit 0
