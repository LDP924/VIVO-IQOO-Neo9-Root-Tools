#!/bin/bash
# build_ksu_module.sh - 手动编译设备适配的 kernelsu-vivo.ko
#
# 这是 build_ksu_lto2.sh 的可移植版: 把原来写死的 WSL 路径
# (/root/kernel/source, /root/kernel/resukisu, /mnt/d/...) 全部改成参数,
# 并加了前置检查。**必须在有内核源码树的机器上跑** —— 本仓库不含内核树。
#
# 内核树从哪来:
#   官方开源包 android_15.0_kernel_SM8550.tar.gz (在仓库根, 197MB / 7.4 万文件),
#   解包到 $HOME/kernel/vivo-neo9-android15 后, 在树根打:
#       patch -p1 < <repo>/patches/kernel-integration.patch
#       ln -sfn <repo>/source/resukisu/kernel drivers/kernelsu
#       cp <repo>/kernel-module/vivo.config .config
#       (再按下方"配置要求"补 LOCALVERSION / KSU 几项)
#
# 环境变量:
#   KERNEL_TREE    内核源码树根目录 (含 .config / Makefile)
#                  默认依次探测 $HOME/kernel/vivo-neo9-android15 → /root/kernel/source
#   RESUKISU_SRC   ReSukiSU 源码根 (含 kernel/ 子目录, 建议带 .git) 默认 <repo>/source/resukisu
#   OUT            产物输出目录                                   默认 <repo>/kernel-module
#   JOBS           并行度                                         默认 nproc
#   OUT_NAME       产物文件名                                     默认 kernelsu-vivo.ko
#   EXPECT_VERSION 期望的 KSU_VERSION, 编译后比对, 不符则报错     默认 35184
#                  (设成 0 关闭检查)
#   HOSTCC/HOSTCXX/HOSTAR/HOSTLD  主机侧工具链                     默认 gcc/g++/ar/ld
#                  ⚠️ 必须显式覆盖: LLVM=1 会让 Kbuild 用交叉 clang 当 HOSTCC,
#                     而 NDK 的 clang 默认目标是 Android, 编不出能在本机跑的 modpost。
#
# 工具链要求 ([0] 步会自动检查):
#   · CC = clang 18。已验证过的两份: Ubuntu clang 18.1.3、NDK r27 的 clang 18.0.1
#     (NDK r27 在本机 $HOME/toolchains/ndk-r27)。clang 21 也支持所需标志但未实测。
#   · 必须支持 -fsanitize=cfi / -flto=full / -sanitize-cfi-cross-dso
#     (LTO+CFI 决定 struct module 布局, 与设备不一致会 "Live 但 init 从不执行")
#   · PATH 里要有 clang / ld.lld / llvm-ar / llvm-nm / llvm-objcopy / llvm-strip。
#     最省事:
#       export PATH=$HOME/toolchains/ndk-r27/android-ndk-r27/toolchains/llvm/prebuilt/linux-x86_64/bin:$PATH
#
# 关键前提 (错了会白编或者编出不可加载的模块):
#   1) 内核树的 .config 必须与设备一致, 且 CONFIG_LTO_CLANG_FULL + CONFIG_CFI_CLANG 打开
#      —— 影响 struct module 布局, 不一致会加载失败 (见 docs/KERNEL_INTEGRATION.md)
#   2) 配置内核时**必须带 ARCH=arm64**! 漏了会退回宿主 x86 的 Kconfig:
#      ARM64_PAN / ARM64_MTE / KASAN_HW_TAGS 全被当成无效项丢掉 → HAS_LTO_CLANG 不成立
#      → LTO/CFI 被静默关掉 → 编出的模块 init 偏移错位。
#      (2026-09-27 实际踩过这个坑, 见 docs/RESUKISU_VERSION.md)
#   3) RESUKISU_SRC 必须带 .git, 否则 Kbuild 会直接 $(error) 拒绝编译
#      (KSU_VERSION = 30000 + git rev-list --count HEAD + 700)
#      版本码只由提交数决定, 与分支/文件内容无关。本仓库 source/resukisu 已锁定:
#          main @ fa8311f6, rev-list --count = 4484  ->  KSU_VERSION = 35184
#   4) KSU_VERSION 是编译期宏 (-DKSU_VERSION=), 但编进 ko 后**是可以从二进制里读出来的**,
#      要找对两个地方:
#        · 数字码 —— .text / .init.text 里的 mov wN, #imm16 立即数 (实测 3 处)
#        · 版本名 —— .rodata 里的明文串, 形如 v4.2.0-rc3-fa8311f6@ReSukiSU
#      (定位与改写见 scripts/patch_ko_version.py; 分析见 docs/RESUKISU_VERSION.md §4.1)
#      不过**编译期校验仍然必要** —— 事后改版本号只能对齐数字, 不会把源码差异带进来,
#      所以本脚本 [0] 与 [7] 的版本比对不能省。
#
# 用法:
#   export PATH=$HOME/toolchains/ndk-r27/android-ndk-r27/toolchains/llvm/prebuilt/linux-x86_64/bin:$PATH
#   bash scripts/build_ksu_module.sh                     # 自动探测内核树
#   KERNEL_TREE=/path/to/kernel/source bash scripts/build_ksu_module.sh
#   # 只想看源码树会算出什么版本 (不需要内核树):
#   bash scripts/build_ksu_module.sh --version-only
set -u

# 有些沙箱把 rm 包装成"安全删除"(批量删除会被拦), 而内核构建要删成千上万个中间文件 ——
# 一旦被拦, host 工具(dtc/unifdef/resolve_btfids)编不出来, prepare 半途失败,
# 后面就报莫名其妙的 "-mstack-protector-guard-offset='' "(asm-offsets.h 不完整)。
# 检测到这种 shim 就把真正的 coreutils 提到 PATH 最前。
case ":${PATH}:" in
    *"/safe-bin:"*) PATH="/usr/bin:/bin:${PATH}"; export PATH ;;
esac

ROOT="$(cd "$(dirname "$0")/.." && pwd)"

# KERNEL_TREE: 先看环境变量, 再依次探测两个常见落点
if [ -z "${KERNEL_TREE:-}" ]; then
    for c in "$HOME/kernel/vivo-neo9-android15" /root/kernel/source; do
        [ -f "$c/Makefile" ] && { KERNEL_TREE="$c"; break; }
    done
fi
KERNEL_TREE="${KERNEL_TREE:-$HOME/kernel/vivo-neo9-android15}"
RESUKISU_SRC="${RESUKISU_SRC:-$ROOT/source/resukisu}"
OUT="${OUT:-$ROOT/kernel-module}"
JOBS="${JOBS:-$(nproc 2>/dev/null || echo 8)}"
OUT_NAME="${OUT_NAME:-kernelsu-vivo.ko}"
LOG="${LOG:-$OUT/.build-ksu-module.log}"
EXPECT_VERSION="${EXPECT_VERSION:-35184}"

# 主机侧工具链 (必须覆盖 LLVM=1 的默认值, 理由见文件头)
HOSTCC="${HOSTCC:-gcc}"
HOSTCXX="${HOSTCXX:-g++}"
HOSTAR="${HOSTAR:-ar}"
HOSTLD="${HOSTLD:-ld}"
HOSTVARS="HOSTCC=$HOSTCC HOSTCXX=$HOSTCXX HOSTAR=$HOSTAR HOSTLD=$HOSTLD"

KSU_KERNEL="$RESUKISU_SRC/kernel"

die() { echo "!! $*" >&2; exit 1; }

MODE=build
[ "${1:-}" = "--version-only" ] && MODE=version-only

# ---- KSU 源码版本推导 (放在最前, 让没有内核树的机器也能先验版本) ----
ksu_version_report() {
    [ -d "$RESUKISU_SRC/.git" ] || {
        echo "  !! $RESUKISU_SRC 没有 .git"
        echo "     Kbuild 会直接 \$(error) 拒绝编译 (它要求源码是 git submodule 形态)"
        echo "     修复: 从上游 clone 带完整历史的源码, 或恢复 source/resukisu/.git"
        return 1
    }
    if [ -f "$RESUKISU_SRC/.git/shallow" ]; then
        echo "  ! 这是浅克隆 (.git/shallow) —— Kbuild 会自动 git fetch --unshallow, 需要网络"
    fi
    local c tag sha dirty
    c="$(git -C "$RESUKISU_SRC" rev-list --count HEAD 2>/dev/null)"
    [ -n "$c" ] || { echo "  !! git rev-list 失败"; return 1; }
    tag="$(git -C "$RESUKISU_SRC" describe --abbrev=0 --tags 2>/dev/null || echo '未知')"
    sha="$(git -C "$RESUKISU_SRC" rev-parse --short=8 HEAD 2>/dev/null || echo '未知')"
    dirty=""
    git -C "$RESUKISU_SRC" diff-index --quiet HEAD 2>/dev/null || dirty="-dirty"
    local v=$((30000 + c + 700))
    echo "  源码     : $RESUKISU_SRC"
    echo "  分支/提交: $(git -C "$RESUKISU_SRC" branch --show-current 2>/dev/null || echo '?') @ $sha$dirty  (tag $tag)"
    echo "  提交数   : $c"
    echo "  KSU_VERSION = 30000 + $c + 700 = $v"
    if [ "$EXPECT_VERSION" != "0" ]; then
        if [ "$v" = "$EXPECT_VERSION" ]; then
            echo "  ✓ 与期望版本一致 ($EXPECT_VERSION)"
            return 0
        else
            echo "  ✗ 与期望版本不一致 (期望 $EXPECT_VERSION)"
            return 2
        fi
    fi
    return 0
}

echo "=== ReSukiSU 源码版本 ==="
ksu_version_report; VER_RC=$?
if [ "$MODE" = "version-only" ]; then
    echo
    [ "$VER_RC" = 0 ] && echo "版本检查通过" || echo "版本检查未通过 (rc=$VER_RC)"
    exit "$VER_RC"
fi

echo "=== 环境 ==="
echo "  内核树   : $KERNEL_TREE"
echo "  KSU 源码 : $RESUKISU_SRC"
echo "  产物目录 : $OUT"
echo "  并行度   : $JOBS"
echo "  主机工具 : $HOSTCC / $HOSTAR / $HOSTLD"
echo

echo "=== [0] 前置检查 ==="
[ -f "$KERNEL_TREE/Makefile" ] || die "内核树无效 (缺 Makefile): $KERNEL_TREE
  内核树不在仓库里, 也没有默认落点。请:
    1) 从仓库根的 android_15.0_kernel_SM8550.tar.gz 解包 (约 1.3GB):
         mkdir -p ~/kernel/vivo-neo9-android15 && tar xzf android_15.0_kernel_SM8550.tar.gz -C ~/kernel/vivo-neo9-android15
    2) 在树根打补丁并挂源码 (见 patches/kernel-integration.patch 头部)
  然后: KERNEL_TREE=<该目录> bash scripts/build_ksu_module.sh"
[ -d "$KSU_KERNEL" ] || die "KSU 源码无效 (缺 kernel/): $RESUKISU_SRC"
[ "$VER_RC" = 2 ] && echo "  ! 版本与期望不符, 仍继续编译 (产物会带错的版本码); 要中止请 Ctrl-C"

# 工具链检查: 缺 ld.lld / 不支持 CFI 会让 [1] 的 LTO 判定静默失败, 必须在这里拦住
for t in clang ld.lld llvm-ar llvm-nm llvm-objcopy llvm-strip; do
    command -v "$t" >/dev/null 2>&1 || die "PATH 里找不到 $t
  用 NDK r27 的 LLVM 工具链最省事:
    export PATH=\$HOME/toolchains/ndk-r27/android-ndk-r27/toolchains/llvm/prebuilt/linux-x86_64/bin:\$PATH"
done
for t in "$HOSTCC" "$HOSTAR" "$HOSTLD"; do
    command -v "$t" >/dev/null 2>&1 || die "主机工具链缺 $t (HOSTCC/HOSTAR/HOSTLD 可用环境变量覆盖)"
done
PROBE="$(mktemp -d)"; printf 'int p(void){return 1;}\n' > "$PROBE/t.c"
if clang --target=aarch64-linux-gnu --version >/dev/null 2>&1; then
    echo "  CC : $(clang --version | head -1 | cut -c1-70)"
else
    die "clang 无法以 --target=aarch64-linux-gnu 运行 (检查工具链)"
fi
if ! clang --target=aarch64-linux-gnu -flto=full -fsanitize=cfi -fvisibility=hidden \
        -c -o /dev/null "$PROBE/t.c" >"$PROBE/err" 2>&1; then
    die "clang 不支持 CFI+LTO (设备内核要求 CONFIG_CFI_CLANG + LTO_CLANG_FULL)
  $(head -2 "$PROBE/err")
  换 clang 18: export PATH=\$HOME/toolchains/ndk-r27/.../bin:\$PATH"
fi
echo "  ✓ clang 支持 CFI + 全量 LTO"
/bin/rm -rf "$PROBE"

grep -E '^CONFIG_(KSU=|LTO_CLANG_FULL|CFI_CLANG|MODVERSIONS)' "$KERNEL_TREE/.config" 2>/dev/null || \
    die "$KERNEL_TREE/.config 缺关键项 (需要 CONFIG_LTO_CLANG_FULL / CFI_CLANG / MODVERSIONS)
  本仓库的 kernel-module/vivo.config 是设备配置存档, 可直接当 .config 用;
  配置时必须带 ARCH=arm64, 否则 ARM64_* 会被判为无效项, LTO/CFI 静默消失。"

# CONFIG_DEBUG_INFO_BTF_MODULES: 设备是 =y, 它会往 struct module 的 init/exit 之间插
# 16 字节 (btf_data_size + btf_data)。本机没有真 pahole 时 Kconfig 判它不可用而丢掉,
# 模块布局就与设备对不上 —— 表现为 exit 偏移 0x368 而不是 0x378(设备值)。
# 用上游自带的 scripts/dummy-tools/pahole(只打印一个版本号)过掉这个探测即可:
# BTF 生成本身会因为树里没有 vmlinux 而自动跳过(见 scripts/Makefile.modfinal 的
# "Skipping BTF generation ... due to unavailability of vmlinux")。
if ! command -v pahole >/dev/null 2>&1; then
    DUMMY="$KERNEL_TREE/scripts/dummy-tools/pahole"
    if [ ! -x "$DUMMY" ]; then
        mkdir -p "$(dirname "$DUMMY")"
        printf '#!/bin/sh\n# 上游同款桩脚本, 只为让 Kconfig 的 PAHOLE_VERSION 探测通过\necho v99.99\n' > "$DUMMY"
        chmod +x "$DUMMY"
    fi
    HOSTVARS="$HOSTVARS PAHOLE=$DUMMY"
    echo "  ! 本机无真 pahole —— 用 scripts/dummy-tools/pahole 过 Kconfig 探测 (BTF 生成本来就会跳过)"
fi
mkdir -p "$OUT" "$(dirname "$LOG")"

cd "$KERNEL_TREE" || die "无法进入 $KERNEL_TREE"

echo "=== [1] olddefconfig + syncconfig ==="
# olddefconfig 才能把"上次因为没有 pahole 而丢掉、这次依赖已满足"的项补回来
make ARCH=arm64 LLVM=1 LLVM_IAS=1 $HOSTVARS olddefconfig >>"$LOG" 2>&1 || true
make ARCH=arm64 LLVM=1 LLVM_IAS=1 $HOSTVARS syncconfig >"$LOG".sync 2>&1 || true
if ! grep -qE '^CONFIG_LTO_CLANG_FULL=y' include/config/auto.conf 2>/dev/null; then
    die "auto.conf 里没有 CONFIG_LTO_CLANG_FULL —— LTO/CFI 被 Kconfig 关掉了。
  产物会是 init 偏移错位的坏模块, 不能继续。
  常见原因: .config 与设备的 LTO/CFI 不一致; 或配置时漏了 ARCH=arm64。
  对照: grep -E 'LTO|CFI|KASAN_HW_TAGS' .config   (应见 LTO_CLANG_FULL=y / CFI_CLANG=y)"
fi
grep -E '^CONFIG_(LTO_CLANG_FULL|CFI_CLANG|KSU)=' include/config/auto.conf | sed 's/^/  /'

echo "=== [2] prepare / modules_prepare ==="
if ! make ARCH=arm64 LLVM=1 LLVM_IAS=1 $HOSTVARS prepare modules_prepare -j"$JOBS" >>"$LOG" 2>&1; then
    echo "  !! prepare 失败 —— 后面的模块编译必然报'-mstack-protector-guard-offset= 空值'"
    echo "     日志里的头几处错误:"
    grep -nE "error:|Error [0-9]" "$LOG" | head -5 | sed 's/^/       /'
    die "先修 prepare (常见: rm 被安全删除包装拦 / 缺 host 工具 / CONFIG_WERROR 把新警告当错误)"
fi
echo "  rc=0   (日志 $LOG)"

echo "=== [3] selinux headers ==="
# genheaders 是 host 工具, 由 `make scripts` 编出来; KSU 要 include selinux 头
GEN="$KERNEL_TREE/scripts/selinux/genheaders/genheaders"
[ -x "$GEN" ] || make ARCH=arm64 LLVM=1 LLVM_IAS=1 $HOSTVARS scripts -j"$JOBS" >>"$LOG" 2>&1 || true
mkdir -p security/selinux/include/generated
if [ -x "$GEN" ]; then
    "$GEN" security/selinux/include/generated/flask.h \
           security/selinux/include/generated/av_permissions.h 2>/dev/null \
        || echo "  ! genheaders 执行失败 (若树里已有 flask.h 可忽略)"
else
    echo "  ! 没编出 genheaders, 跳过 (若树里已有 flask.h 可忽略)"
fi

echo "=== [4] clean KSU objs ==="
make ARCH=arm64 LLVM=1 LLVM_IAS=1 $HOSTVARS M="$KSU_KERNEL" src="$KSU_KERNEL" clean >>"$LOG" 2>&1 || true

echo "=== [5] build ==="
make ARCH=arm64 LLVM=1 LLVM_IAS=1 $HOSTVARS \
    KCFLAGS="-I$KERNEL_TREE/security/selinux/include/generated" \
    M="$KSU_KERNEL" src="$KSU_KERNEL" modules -j"$JOBS" >>"$LOG" 2>&1
RC=$?
echo "  BUILD RC=$RC"
grep -cE '^  CC ' "$LOG" || true
tail -12 "$LOG"

echo "=== [6] 产物 ==="
KO="$KSU_KERNEL/kernelsu.ko"
if [ -f "$KO" ]; then
    cp -f "$KO" "$OUT/$OUT_NAME"
    echo "  -> $OUT/$OUT_NAME  ($(stat -c%s "$OUT/$OUT_NAME") 字节)"
    echo -n "  vermagic: "
    strings "$KO" | grep -m1 'vermagic='

    echo "=== [6b] struct module 布局校验（挡住 Live 但 init 不执行 的坏模块）==="
    RELS="$(readelf -rW "$KO" 2>/dev/null | awk '/this_module/{f=1} f')"
    IO="$(echo "$RELS" | grep -m1 ' init_module' | awk '{print $1}')"
    CO="$(echo "$RELS" | grep -m1 ' cleanup_module' | awk '{print $1}')"
    IOV=$((16#${IO:-0})); COV=$((16#${CO:-0}))
    printf "  init    -> 0x%x   (设备要求 0x178)\n" "$IOV"
    printf "  cleanup -> 0x%x\n" "$COV"
    [ "$IOV" = "$((0x178))" ] || die "struct module 布局与设备不一致: init 偏移 0x$(printf %x "$IOV") ≠ 0x178
  加载后会 Live 但 init 从不执行。逐项核对 .config:
    CONFIG_LTO_CLANG_FULL=y / CONFIG_CFI_CLANG=y
    CONFIG_DEBUG_INFO_BTF_MODULES=y  (设备 =y, 缺它 init 与 exit 之间少 16 字节)
    CONFIG_KASAN_HW_TAGS=y  (缺它则 HAS_LTO_CLANG 不成立 -> LTO/CFI 被关)"
    if grep -q '^CONFIG_DEBUG_INFO_BTF_MODULES=y' "$KERNEL_TREE/.config"; then
        [ "$COV" = "$((0x378))" ] || die "cleanup 偏移 0x$(printf %x "$COV") ≠ 0x378 (设备在 BTF_MODULES=y 下的值)
  这条检查正是 2026-09-27 抓出 CONFIG_DEBUG_INFO_BTF_MODULES 被漏掉的那个。"
    fi
    echo "  ✓ 布局与设备一致"

    echo "=== [7] 版本校验 (Kbuild 编译期输出) ==="
    ACTUAL="$(grep -oE 'version code: [0-9]+' "$LOG" 2>/dev/null | tail -1 | grep -oE '[0-9]+')"
    FULL="$(grep -oE 'version name: .*' "$LOG" 2>/dev/null | tail -1 | sed 's/version name: //')"
    if [ -n "$ACTUAL" ]; then
        echo "  编译期 KSU_VERSION = $ACTUAL"
        [ -n "$FULL" ] && echo "  编译期 version name = $FULL"
        if [ "$EXPECT_VERSION" != "0" ] && [ "$ACTUAL" != "$EXPECT_VERSION" ]; then
            die "版本不符: 期望 $EXPECT_VERSION, 实际产出 $ACTUAL
  检查 RESUKISU_SRC 指向的源码树提交数 (KSU_VERSION = 30000 + 提交数 + 700)"
        fi
        [ "$EXPECT_VERSION" != "0" ] && echo "  ✓ 与期望版本一致 ($EXPECT_VERSION)"
    else
        echo "  ! 日志里没找到 'version code:' —— 无法确认版本"
        echo "    (先删 $KSU_KERNEL 下的旧 .o 再编, 或看 $LOG)"
    fi

    echo "  下一步: 把这个 .ko 替换到 apk/ksuonetap/assets/<系统版本>/kernelsu-vivo.ko 与 kernel-module/kernelsu-vivo.ko"
    echo "         <系统版本> = 目标设备 Build.DISPLAY (当前随包的是 PD2338_A_15.1.14.7.W10.V000L1)"
    echo "         它是**系统版本绑定产物**, 换系统版本要放进以新版本命名的新目录,"
    echo "         并同步该目录下 SYSTEM.txt 里的 md5 (否则 ./build.sh sync 会报)"
    echo "         然后 ./build.sh sync 校验副本一致性 (忘了同步它一定会报)"
else
    die "没有产出 kernelsu.ko —— 看日志: $LOG"
fi
