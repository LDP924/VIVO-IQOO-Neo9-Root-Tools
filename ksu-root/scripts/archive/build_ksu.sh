#!/bin/bash
# build_ksu.sh - 编译 kernelsu.ko (Type010 方法, ReSukiSU)
# 用法: bash build_ksu.sh
# 前置: setup_kernel_env.sh 已运行; 设备 config 已放入 $SRC_DIR/.config
set -e

KERNEL_DIR="$HOME/kernel"
SRC_DIR="$KERNEL_DIR/source"
KSU_DIR="$KERNEL_DIR/resukisu"
TC_DIR="$KERNEL_DIR/toolchains"

# 工具链 PATH (prebuilt 优先, 回退 apt clang)
if [ -x "$TC_DIR/clang-r450784c/bin/clang" ]; then
    export PATH="$TC_DIR/clang-r450784c/bin:$PATH"
fi
export ARCH=arm64 LLVM=1 LLVM_IAS=1

cd "$SRC_DIR"

echo "== [1/6] 设备 config =="
VIVO_CFG=/mnt/d/payload-dumper-go/_ksu_build/vivo.config
if [ ! -f .config ]; then
    if [ -f "$VIVO_CFG" ]; then
        cp "$VIVO_CFG" .config
        echo "  使用 $VIVO_CFG"
    else
        echo "ERROR: 无 .config (先从手机: su -c 'zcat /proc/config.gz > /sdcard/vivo.config')"
        exit 1
    fi
fi
grep '^CONFIG_LOCALVERSION=' .config || echo "  (无 CONFIG_LOCALVERSION)"

echo "== [2/6] ReSukiSU setup.sh =="
# LKM 外部编译模式 (M=... modules) 不需要 setup.sh (那是内建模式用的, 会改内核树)
# 若需要内建集成可取消注释:
# if [ -f "$KSU_DIR/kernel/setup.sh" ]; then
#     bash "$KSU_DIR/kernel/setup.sh" 2>/dev/null || echo "  setup.sh 返回非零 (继续)"
# fi
echo "  (跳过: LKM 模式无需 setup.sh)"

echo "== [3/6] 关键开关 (KSU=m + MODVERSIONS + LOCALVERSION) =="
./scripts/config --module KSU
./scripts/config --enable MODVERSIONS
./scripts/config --enable KSU_TRACEPOINT_HOOK
./scripts/config --disable KSU_DEBUG
# 设备 vermagic: 5.15.178-gaacdc35637c4-dirty -> 手动设 LOCALVERSION (源码无 git)
./scripts/config --disable LOCALVERSION_AUTO
./scripts/config --set-str LOCALVERSION "-gaacdc35637c4-dirty"
# LTO: 设备 config 开 LTO_CLANG_FULL, 外部模块编译关闭 (编完恢复不影响 CRC/vermagic)
./scripts/config --disable LTO_CLANG_FULL 2>/dev/null || true
./scripts/config --enable LTO_NONE 2>/dev/null || true

echo "== [4/6] olddefconfig + 准备 =="
make ARCH=arm64 LLVM=1 LLVM_IAS=1 olddefconfig
grep '^CONFIG_KSU=' .config || true
make ARCH=arm64 LLVM=1 LLVM_IAS=1 prepare modules_prepare

echo "== [5/6] SELinux 头文件 =="
mkdir -p security/selinux/include/generated
scripts/selinux/genheaders/genheaders \
    security/selinux/include/generated/flask.h \
    security/selinux/include/generated/av_permissions.h 2>/dev/null || \
    echo "  WARN: genheaders 失败 (若编译报 flask.h 缺失再处理)"

echo "== [6/6] 编译 ReSukiSU 模块 =="
# 注意: ReSukiSU 需要 src= 参数 (其 Makefile 内部用 src 定位源文件)
make ARCH=arm64 LLVM=1 LLVM_IAS=1 \
    KCFLAGS="-I$SRC_DIR/security/selinux/include/generated" \
    M="$KSU_DIR/kernel" src="$KSU_DIR/kernel" modules

echo ""
echo "=== 产物 ==="
ls -la "$KSU_DIR/kernel/kernelsu.ko"
strings "$KSU_DIR/kernel/kernelsu.ko" | grep vermagic=
llvm-readelf -S "$KSU_DIR/kernel/kernelsu.ko" 2>/dev/null | grep __versions || true
echo "=== 复制到 Windows ==="
cp "$KSU_DIR/kernel/kernelsu.ko" /mnt/d/payload-dumper-go/_ksu_build/kernelsu-vivo.ko && \
    echo "已复制: D:\\payload-dumper-go\\_ksu_build\\kernelsu-vivo.ko"
