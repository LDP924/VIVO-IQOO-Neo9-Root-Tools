#!/bin/bash
# setup_kernel_env.sh - WSL Ubuntu 内核编译环境搭建 (Type010 方法)
# 用法: bash setup_kernel_env.sh
# 前置: WSL Ubuntu 已安装; 源码已复制到 /mnt/d 或本脚本旁
set -e

KERNEL_DIR="$HOME/kernel"
SRC_DIR="$KERNEL_DIR/source"
TC_DIR="$KERNEL_DIR/toolchains"
KSU_DIR="$KERNEL_DIR/resukisu"

echo "== [1/7] 安装系统依赖 =="
sudo apt update -y
sudo apt install -y \
    git make flex bison bc cpio libssl-dev libelf-dev \
    libncurses-dev python3 python3-pip p7zip zip unzip \
    build-essential dwarves zstd xz-utils wget curl file \
    kmod lz4 2>/dev/null || true

echo "== [2/7] 创建目录结构 =="
mkdir -p "$SRC_DIR" "$TC_DIR" "$KSU_DIR"

echo "== [3/7] 复制/链接源码 =="
# 源码已解压到 /mnt/d/payload-dumper-go/_ksu_build/vivo_kernel_src (5.15.178)
VIVO_SRC="/mnt/d/payload-dumper-go/_ksu_build/vivo_kernel_src"
if [ -f "$VIVO_SRC/Makefile" ] && [ -z "$(ls -A "$SRC_DIR" 2>/dev/null)" ]; then
    echo "  从 $VIVO_SRC 复制 (约 1GB, 较慢)..."
    cp -a "$VIVO_SRC/." "$SRC_DIR/"
fi
if [ ! -f "$SRC_DIR/Makefile" ]; then
    echo "ERROR: 源码未就位 ($SRC_DIR/Makefile 不存在)"
    exit 1
fi
echo "  源码 OK: $SRC_DIR"

echo "== [4/7] 克隆 ReSukiSU =="
if [ ! -d "$KSU_DIR/.git" ]; then
    git clone --depth 1 https://github.com/ReSukiSU/ReSukiSU.git "$KSU_DIR" || {
        # 从 Windows 拷贝已有克隆
        echo "  git clone 失败, 尝试从 /mnt/d 拷贝..."
        if [ -d /mnt/d/payload-dumper-go/_ksu_build/ReSukiSU ]; then
            cp -a /mnt/d/payload-dumper-go/_ksu_build/ReSukiSU/. "$KSU_DIR/"
        fi
    }
fi
ls "$KSU_DIR/kernel/Makefile" >/dev/null && echo "  ReSukiSU OK: $KSU_DIR"

echo "== [5/7] 获取 clang 工具链 =="
# 优先用 WSL 已装 clang; 否则下载 Android prebuilt
if ! command -v clang >/dev/null 2>&1 || ! clang --version 2>/dev/null | grep -q "Android"; then
    echo "  尝试 apt 安装 clang (备选)..."
    sudo apt install -y clang lld llvm 2>/dev/null || true
fi
CLANG_BIN="$TC_DIR/clang-r450784c/bin"
if [ ! -x "$CLANG_BIN/clang" ]; then
    echo "  尝试下载 clang-r450784c (若网络允许)..."
    ( cd "$TC_DIR" && git clone --depth 1 \
        https://android.googlesource.com/platform/prebuilts/clang/host/linux-x86 \
        -b main clang-prebuilt 2>/dev/null && \
        cp -a clang-prebuilt/clang-r450784c . ) || \
    echo "  WARN: prebuilt 下载失败, 将使用 apt clang (vermagic 不受影响, 但需验证)"
fi
clang --version 2>/dev/null | head -1 || true

echo "== [6/7] 设备 config =="
# 设备 config 需从手机获取: zcat /proc/config.gz > /sdcard/vivo.config
VIVO_CFG=$(find /mnt/d/payload-dumper-go -maxdepth 4 -name "vivo.config" -o -name "config.gz" 2>/dev/null | head -1)
if [ -n "$VIVO_CFG" ]; then
    echo "  找到设备 config: $VIVO_CFG"
else
    echo "  WARN: 未找到设备 config。请先从手机获取:"
    echo "    su -c 'zcat /proc/config.gz > /sdcard/vivo.config'"
    echo "    然后放到 /mnt/d/payload-dumper-go/_ksu_build/vivo.config"
fi

echo "== [7/7] 环境就绪 =="
echo "  源码: $SRC_DIR"
echo "  工具链: $(command -v clang || echo '待定')"
echo "  ReSukiSU: $KSU_DIR"
echo "  下一步: 复制设备 config 到 \$SRC_DIR/.config 后运行 build_ksu.sh"
