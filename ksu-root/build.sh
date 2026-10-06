#!/bin/bash
# build.sh - 本项目统一构建入口
#
# 子命令:
#   ./build.sh                  默认 = all (exploit + test + sync + verify)
#   ./build.sh exploit          编 exploit 全部变体 (5 个 NDK 版 + 可选静态版)
#                               默认编**所有已适配的固件 profile**; --fw 只编一个
#   ./build.sh modules          编内核模块 (vrpatch / unpatch) -- 需内核源码树
#   ./build.sh test             只跑离线回归 (不需设备/工具链)
#   ./build.sh sync             校验 assets 打包副本与源目录是否一致 (防"改了源忘了同步")
#   ./build.sh verify           只做产物校验 (需 NDK 的 llvm 工具; 逐个 profile 验)
#   ./build.sh doctor           环境自检 (工具链/设备/关键文件/地址锁)
#   ./build.sh fetch-ndk        下载 NDK 到工作区 (不动系统路径)
#   ./build.sh clean            清理构建日志 (--deep 连二进制一起清)
#   ./build.sh help             本帮助
#
# 选项:
#   --ndk PATH      指定 NDK 根目录 (等价 NDK=PATH)
#   --fw TAG        只处理某一个固件 profile (15.1.14.7 / 14.0.17.2; 见 exploit 构建脚本 --list)
#   --no-static     跳过静态 gcc 版 (默认会自动探测 aarch64-linux-gnu-gcc)
#   --strict        把"[跳过]"项视为失败 (CI 用)
#   --dest PATH     fetch-ndk 的下载目标目录
#
# 目录约定见 docs/INDEX.md
set -u

ROOT="$(cd "$(dirname "$0")" && pwd)"
EXPLOIT_DIR="$ROOT/neo9-root/exploit"
SCRIPTS_DIR="$ROOT/scripts"

CMD="all"
OPT_NDK=""
OPT_NO_STATIC=0
OPT_STRICT=0
OPT_DEST=""
OPT_DEEP=""
OPT_FW=""

# 随包适配的系统版本 (= 设备 Build.DISPLAY, 也就是 assets/ 下的版本目录名)。
# 单一来源是 scripts/check-assets-sync.sh —— 这里不重复写死版本串。
ASSET_VER="$(bash "$(dirname "$0")/scripts/check-assets-sync.sh" --print-ver 2>/dev/null)"

# 已适配的固件 profile 列表 —— 单一来源是 neo9-root/exploit/fw_profile.py
FW_KEYS="$(cd "$EXPLOIT_DIR" && python3 -c 'import fw_profile as p; print(" ".join(p.PROFILE_KEYS))' 2>/dev/null)"
FW_KEYS="${FW_KEYS:-15.1.14.7}"

while [ $# -gt 0 ]; do
    case "$1" in
        --ndk)        OPT_NDK="${2:-}"; shift 2 ;;
        --fw)         OPT_FW="${2:-}"; shift 2 ;;
        --no-static)  OPT_NO_STATIC=1; shift ;;
        --strict)     OPT_STRICT=1; shift ;;
        --dest)       OPT_DEST="${2:-}"; shift 2 ;;
        --deep)       OPT_DEEP="--deep"; shift ;;
        -h|--help)    CMD="help"; shift ;;
        -*)           echo "未知选项: $1"; CMD="help"; shift ;;
        *)            CMD="$1"; shift ;;
    esac
done

[ -n "$OPT_NDK" ] && export NDK="$OPT_NDK"

if [ -t 1 ]; then
    C_OK=$'\033[32m'; C_WARN=$'\033[33m'; C_ERR=$'\033[31m'; C_HDR=$'\033[1m'; C_OFF=$'\033[0m'
else
    C_OK=""; C_WARN=""; C_ERR=""; C_HDR=""; C_OFF=""
fi
ok()   { echo "  ${C_OK}OK${C_OFF}   $*"; }
warn() { echo "  ${C_WARN}跳过${C_OFF} $*"; SKIPPED=$((SKIPPED + 1)); }
err()  { echo "  ${C_ERR}失败${C_OFF} $*"; FAILED=$((FAILED + 1)); }
hdr()  { echo; echo "${C_HDR}=== $* ===${C_OFF}"; }

SKIPPED=0
FAILED=0

usage() {
    awk 'NR>1 && /^#/ {sub(/^# ?/, ""); print; next} NR>1 {exit}' "$0"
    cat <<'TIP'

常见用法:
  ./build.sh                     一把梭: 编译(全部 profile) + 回归 + assets 同步校验 + 产物校验
  ./build.sh doctor              换机器/换设备后先跑这个
  ./build.sh fetch-ndk           本机没有 NDK 时先跑这个
  ./build.sh exploit --no-static 只要 NDK 版 (不需要静态 gcc)
  ./build.sh exploit --fw 14.0.17.2   只编某一个固件 profile
  ./build.sh sync                改了 .ko / su / exploit / ksud 后, 确认 assets 已同步
  ./build.sh verify              逐个 profile 做产物校验 (头文件一致性 + stub 机器码 + 地址闸门)

产物 (neo9-root/exploit/out/<系统版本号>/):
  exploit_vivo_neo9_stable_su_ndk13   ★ 内置 su, STUB_MODE=13 (推荐推这个)
  exploit_vivo_neo9_stable_su_ndk10   内置 su, STUB_MODE=10
  exploit_vivo_neo9_stable_ndk13      无内置 su, STUB_MODE=13
  exploit_vivo_neo9_stable_ndk10      无内置 su, STUB_MODE=10
  exploit_vivo_neo9_stable_ndk1       诊断版 (stub 返回 0x1234)

固件 profile (系统版本绑定的地址/偏移都在 neo9-root/exploit/fw_profile.h):
  15.1.14.7   内核 5.15.178  (完整适配: exploit + 3 个内核模块)
  14.0.17.2   内核 5.15.137  (完整适配: exploit + 3 个内核模块)
TIP
}

# ---- 探测 NDK (与 build_exploit_stable.sh 的候选顺序一致) ----
find_ndk() {
    if [ -n "${NDK:-}" ] && [ -x "$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android21-clang" ]; then
        return 0
    fi
    local c
    for c in "$HOME/.workbuddy/binaries/android-ndk-r29" \
             "$HOME/android-ndk-cache/android-ndk-r29" \
             /mnt/d/android-ndk-r29 /opt/android-ndk-r29 /usr/local/android-ndk-r29; do
        if [ -x "$c/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android21-clang" ]; then
            export NDK="$c"; return 0
        fi
    done
    return 1
}

cmd_exploit() {
    hdr "构建 exploit"
    [ -f "$EXPLOIT_DIR/build_exploit_stable.sh" ] || { err "缺 $EXPLOIT_DIR/build_exploit_stable.sh"; return 1; }
    if ! find_ndk; then
        echo "  NDK: 未找到 -> 先跑 ./build.sh fetch-ndk (或 --ndk PATH)"
    else
        echo "  NDK: $NDK"
    fi
    local sk=0
    [ "$OPT_NO_STATIC" = "1" ] && sk=1
    # 默认编**全部已适配的 profile**（保持 out/ 与源码同步；每个 profile 只有 5 个小产物）
    local keys targets rc=0 k
    if [ -n "$OPT_FW" ]; then
        targets="$OPT_FW"
    else
        targets="$FW_KEYS"
    fi
    for k in $targets; do
        echo "  ---- profile $k ----"
        ( cd "$EXPLOIT_DIR" && FW="$k" SKIP_STATIC=$sk NDK="${NDK:-}" bash build_exploit_stable.sh ) || rc=$?
    done
    [ $rc -eq 0 ] && ok "exploit 构建完成 (profile: $targets)" || err "exploit 构建返回 $rc"
    return $rc
}

cmd_test() {
    hdr "离线回归测试 (不需设备)"
    local t rc=0
    for t in "$EXPLOIT_DIR/test_stub_state.py" \
             "$EXPLOIT_DIR/test_su_protocol.py" \
             "$ROOT/neo9-root/scripts/test_run_su_driver.py"; do
        [ -f "$t" ] || { warn "缺 $(basename "$t")"; continue; }
        if python3 "$t" >/dev/null 2>&1; then
            ok "$(basename "$t")"
        else
            err "$(basename "$t")"; rc=1
        fi
    done
    return $rc
}

# assets 里的 .ko / su / exploit / ksud 都是**别处产出的副本**，改了源忘了同步不会报错，
# 只会表现为"怎么改都没生效"。这道闸门专门盯这个（含 ksud ↔ manager 内部那一份）。
cmd_sync() {
    hdr "assets 打包副本一致性"
    if [ ! -f "$SCRIPTS_DIR/check-assets-sync.sh" ]; then
        warn "缺 $SCRIPTS_DIR/check-assets-sync.sh"
        return 0
    fi
    if bash "$SCRIPTS_DIR/check-assets-sync.sh"; then
        ok "check-assets-sync.sh"
    else
        err "check-assets-sync.sh (打包副本与源目录不同步)"; return 1
    fi
    return 0
}

cmd_verify() {
    hdr "产物校验 (头文件一致性/架构/解释器/依赖库/stub 机器码/地址闸门)"
    if ! find_ndk; then
        warn "无 NDK (需要 llvm-readelf/llvm-objdump) -> ./build.sh fetch-ndk"
        return 0
    fi
    # 逐个 profile 校验；只验 out/ 下真实存在产物的那些（没编过的不算失败）
    local k rc=0 n=0
    for k in ${OPT_FW:-$FW_KEYS}; do
        if ! ( cd "$EXPLOIT_DIR" && python3 -c "
import sys, os, fw_profile as p
sys.exit(0 if os.path.isdir('out/' + p.PROFILE_KEYS['$k']['build']) else 1)" 2>/dev/null ); then
            warn "profile $k 未构建 (跳过) -> ./build.sh exploit --fw $k"
            continue
        fi
        if ( cd "$EXPLOIT_DIR" && NDK="$NDK" FW="$k" python3 verify_bins.py ); then
            ok "verify_bins.py (FW=$k)"; n=$((n+1))
        else
            err "verify_bins.py (FW=$k)"; rc=1
        fi
    done
    [ "$n" -gt 0 ] || warn "没有任何 profile 的产物被校验到"
    return $rc
}

cmd_modules() {
    hdr "构建内核模块 (kernelsu-vivo / vrpatch / unpatch)"
    # 三类模块都必须用目标内核的源码树构建 (LTO+CFI, 且符号需按 .config 解析),
    # 因此只能在有内核树的构建机上做 —— 本仓库不含内核树。
    local tree="${KSU_KERNEL_TREE:-/root/kernel/source}"
    if [ -d "$tree" ]; then
        # 有内核树: 顺带把 KSU 主模块也编了 (可移植脚本, 路径全部参数化)
        if [ -f "$ROOT/scripts/build_ksu_module.sh" ]; then
            echo "  -> kernelsu-vivo.ko (KERNEL_TREE=$tree)"
            bash "$ROOT/scripts/build_ksu_module.sh" || err "kernelsu 模块构建失败"
        fi
    fi
    if [ ! -d "$tree" ]; then
        warn "未找到内核源码树: $tree"
        cat <<'TIP'

  模块构建必须在内核源码树所在机器上做 (见 docs/KERNEL_INTEGRATION.md):
    1) 在内核树机器上:
         export KSU_KERNEL_TREE=/root/kernel/source
         bash scripts/build_ksu_module.sh       # 产出 kernelsu-vivo.ko (可移植, 路径全参数化)
         bash test-modules/vrpatch/build.sh     # 产出 vrpatch.ko
         bash test-modules/unpatch/build.sh     # 产出 unpatch.ko
    2) 回到本项目后重新执行 ./build.sh modules 会把 .ko 收进 test-modules/
  现成产物已随仓库提供: test-modules/vrpatch/vrpatch.ko, test-modules/unpatch/unpatch.ko
TIP
        return 0
    fi
    local d rc=0
    for d in vrpatch unpatch; do
        if [ -x "$ROOT/test-modules/$d/build.sh" ]; then
            echo "  -> $d"
            ( cd "$ROOT/test-modules/$d" && bash build.sh ) || rc=1
        else
            warn "test-modules/$d/build.sh 不可执行"
        fi
    done
    [ $rc -eq 0 ] && ok "内核模块构建完成" || err "内核模块构建有失败"
    return $rc
}

cmd_doctor() {
    hdr "环境自检"
    local b
    for b in bash python3 gcc; do
        if command -v "$b" >/dev/null 2>&1; then ok "$b ($($b --version 2>/dev/null | head -1 | cut -c1-40))"; else warn "$b 未安装"; fi
    done
    if find_ndk; then
        ok "NDK: $NDK"
        local cc="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android21-clang"
        ok "  clang $("$cc" --version 2>/dev/null | head -1 | sed 's/.*version //;s/ .*//')"
    else
        warn "NDK 未找到 -> ./build.sh fetch-ndk"
    fi
    if command -v aarch64-linux-gnu-gcc >/dev/null 2>&1; then
        ok "静态交叉 gcc (可选)"
    else
        warn "无 aarch64-linux-gnu-gcc (只影响静态版, NDK 版不受影响)"
    fi
    if command -v adb >/dev/null 2>&1; then
        local devs
        devs="$(adb devices 2>/dev/null | awk 'NR>1 && $2=="device" {print $1}' | tr '\n' ' ')"
        [ -n "$devs" ] && ok "adb 设备: $devs" || warn "adb 无在线设备 (不要在容器里 kill-server, 复用它已有的 server)"
    else
        warn "adb 未安装 (只影响部署/真机脚本)"
    fi

    echo "  --- 关键文件 ---"
    local f missing=0
    for f in neo9-root/exploit/exploit_vivo.c \
             neo9-root/exploit/fw_profile.h \
             neo9-root/exploit/build_exploit_stable.sh \
             neo9-root/exploit/verify_bins.py \
             neo9-root/tools/fw_kernel_derive.py \
             test-modules/vrpatch/vrpatch.ko \
             test-modules/unpatch/unpatch.ko \
             apk/ksuonetap/assets/$ASSET_VER/kernelsu-vivo.ko \
             source/resukisu/.git \
             README.md docs/INDEX.md; do
        if [ -e "$ROOT/$f" ]; then ok "$f"; else warn "$f 缺失"; missing=1; fi
    done

    echo "  --- ReSukiSU 源码 / 驱动版本 (KSU_VERSION = 30000 + 提交数 + 700) ---"
    local rs="$ROOT/source/resukisu" ksu_expect=35184
    if [ -d "$rs/.git" ]; then
        local c="" sha="" tag="" dirty="" v=""
        c="$(git -C "$rs" rev-list --count HEAD 2>/dev/null)"
        if [ -n "$c" ]; then
            sha="$(git -C "$rs" rev-parse --short=8 HEAD 2>/dev/null)"
            tag="$(git -C "$rs" describe --abbrev=0 --tags 2>/dev/null)"
            git -C "$rs" diff-index --quiet HEAD 2>/dev/null || dirty="-dirty"
            v=$((30000 + c + 700))
            if [ "$v" = "$ksu_expect" ]; then
                ok "source/resukisu: $tag-$sha$dirty -> KSU_VERSION=$v  (目标 $ksu_expect ✓)"
            else
                warn "source/resukisu: $tag-$sha$dirty -> KSU_VERSION=$v  (目标 $ksu_expect ✗)"
                echo "      修复: git -C source/resukisu checkout <目标提交> (版本码只由提交数决定)"
            fi
        else
            warn "source/resukisu 的 git 历史不可用 -> KSU_VERSION 无法推导"
        fi
    else
        warn "source/resukisu/.git 缺失 -> Kbuild 会 \$(error) 拒绝编译, 且版本号算错"
    fi
    local ko_a="$ROOT/apk/ksuonetap/assets/$ASSET_VER/kernelsu-vivo.ko" ko_k="$ROOT/kernel-module/kernelsu-vivo.ko"
    local h_a h_k
    h_a="$(md5sum "$ko_a" 2>/dev/null | cut -c1-12)"; h_k="$(md5sum "$ko_k" 2>/dev/null | cut -c1-12)"
    echo "    随包适配版本: ${ASSET_VER:-!! 取不到 (scripts/check-assets-sync.sh 缺?)}"
    echo "    assets ko   : $(stat -c%s "$ko_a" 2>/dev/null) 字节  md5 ${h_a:-缺失}"
    echo "    kernel-module ko: $(stat -c%s "$ko_k" 2>/dev/null) 字节  md5 ${h_k:-缺失}"
    if [ -n "$h_a" ] && [ -n "$h_k" ] && [ "$h_a" != "$h_k" ]; then
        warn "两处 ko 内容不同 (assets 是设备实际部署的那份, kernel-module 是仓库留存那份)"
    fi
    echo "    (版本码在 .ko 里可定位: .rodata 的 KSU_VERSION_FULL 串含 commit sha,"
    echo "     git rev-list --count 即可反算; 数字码是 mov wN,#imm16 立即数。"
    echo "     只改版本号不重编: python3 scripts/patch_ko_version.py <ko> --to 35184 --dry-run)"
    if command -v strings >/dev/null 2>&1 && [ -f "$ko_a" ]; then
        echo -n "    assets ko vermagic: "
        strings "$ko_a" 2>/dev/null | grep -m1 'vermagic=' || echo "(未找到)"
    fi

    echo "  --- 已适配的系统版本 (assets/ <-> SYSTEM.txt tier <-> DeviceGate 三处互校) ---"
    local v
    for v in $(bash "$SCRIPTS_DIR/check-assets-sync.sh" --print-vers 2>/dev/null); do
        if [ -d "$ROOT/apk/ksuonetap/assets/$v" ]; then
            local t
            t="$(grep -m1 '^tier=' "$ROOT/apk/ksuonetap/assets/$v/SYSTEM.txt" 2>/dev/null | cut -d= -f2)"
            ok "  $v  tier=${t:-?}"
        else
            warn "  版本表里声明了 $v, 但 assets/$v/ 不存在"
        fi
    done
    echo "    完整适配 (tier=full) = exploit + 3 个内核模块; LPE-only (tier=lpe-su) = 只有 exploit"
    echo "    新增一版的做法: 见 neo9-root/tools/README.md「换系统版本怎么重推」"

    echo "  --- 地址锁 (硬约束: 这些值一改, 真机就会 panic/不命中) ---"
    echo "    单一来源: neo9-root/exploit/fw_profile.h (按 profile 分支)"
    ( cd "$EXPLOIT_DIR" && python3 - <<'PY' 2>/dev/null
import fw_profile as p
for k in p.PROFILE_KEYS:
    c = p.constants(k)
    print("      %-10s %s  内核 %s" % (k, p.PROFILE_KEYS[k]['build'], p.PROFILE_KEYS[k]['kernel']))
    for n in ('FW_KERNEL_STEXT_PA', 'NEO9_VHANGUP_OFFSET', 'NEO9_PREP_KRED_VA',
              'NEO9_COMMIT_CREDS_VA', 'NEO9_CAP_BPRM_OFFSET'):
        print("                  %-24s %#x" % (n, c[n]))
PY
    )
    echo "    (编译期 _Static_assert 会再兜一层; 反汇编闸门在 verify_bins.py, 逐 profile 验)"
    return 0
}

cmd_fetch_ndk() {
    hdr "下载 NDK"
    local s="$SCRIPTS_DIR/fetch-ndk.sh"
    [ -f "$s" ] || { err "缺 $s"; return 1; }
    if [ -n "$OPT_DEST" ]; then
        DEST="$OPT_DEST" bash "$s"
    else
        bash "$s"
    fi
    return $?
}

cmd_clean() {
    hdr "清理构建日志"
    local n=0
    if [ -d "$EXPLOIT_DIR/.build-logs" ]; then
        /bin/rm -rf "$EXPLOIT_DIR/.build-logs"; n=1
    fi
    /bin/rm -f "$EXPLOIT_DIR"/*.log 2>/dev/null
    ok "构建日志已清 (.build-logs, *.log)"
    if [ "${1:-}" = "--deep" ]; then
        echo "  --deep: 连编译产物一起清 (只保留源码; 需要时重新 ./build.sh)"
        /bin/rm -rf "$EXPLOIT_DIR/out"
        /bin/rm -f "$EXPLOIT_DIR"/exploit_vivo_neo9_stable_ndk1 \
                   "$EXPLOIT_DIR"/exploit_vivo_neo9_stable_ndk10 \
                   "$EXPLOIT_DIR"/exploit_vivo_neo9_stable_ndk13 \
                   "$EXPLOIT_DIR"/exploit_vivo_neo9_stable_su_ndk10 \
                   "$EXPLOIT_DIR"/exploit_vivo_neo9_stable_su_ndk13 \
                   "$EXPLOIT_DIR"/exploit_vivo_neo9_stable \
                   "$EXPLOIT_DIR"/exploit_vivo_neo9_stable_su
        ok "编译产物已清 (out/ + 历史扁平路径)"
    fi
    [ "$n" = "1" ] || ok "(本就没有日志)"
    return 0
}

case "$CMD" in
    help)      usage ;;
    doctor)    cmd_doctor ;;
    fetch-ndk) cmd_fetch_ndk ;;
    exploit)   cmd_exploit ;;
    modules)   cmd_modules ;;
    test)      cmd_test ;;
    sync)      cmd_sync ;;
    verify)    cmd_verify ;;
    clean)     cmd_clean "${OPT_DEEP:-}" ;;
    all)
        cmd_exploit
        cmd_test
        cmd_sync
        cmd_verify
        ;;
    *)
        echo "未知子命令: $CMD"; usage; exit 2 ;;
esac
rc=0

hdr "汇总"
if [ "$FAILED" -gt 0 ]; then
    echo "  ${C_ERR}$FAILED 项失败${C_OFF}, $SKIPPED 项跳过"
    rc=1
elif [ "$SKIPPED" -gt 0 ]; then
    echo "  ${C_WARN}$SKIPPED 项跳过${C_OFF} (不一定是问题; --strict 可让其失败)"
    [ "$OPT_STRICT" = "1" ] && rc=1
else
    echo "  ${C_OK}全部通过${C_OFF}"
fi
exit $rc
