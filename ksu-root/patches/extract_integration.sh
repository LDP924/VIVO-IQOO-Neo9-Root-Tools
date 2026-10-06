#!/bin/bash
# 提取 vivo 内核源码中的 ReSukiSU 集成修改点
cd /root/kernel/source || exit 1
echo "===== [1] vermagic.h VERMAGIC_STRING ====="
grep -n "VERMAGIC_STRING" include/linux/vermagic.h | head -5
echo ""
echo "===== [2] drivers/Makefile KSU 行 ====="
grep -n "kernelsu" drivers/Makefile
echo ""
echo "===== [3] drivers/Kconfig KSU 行 ====="
grep -n "kernelsu" drivers/Kconfig
echo ""
echo "===== [4] drivers/kernelsu symlink ====="
ls -la drivers/kernelsu 2>&1
echo ""
echo "===== [5] .config KSU/LTO/CFI/LOCALVERSION ====="
grep -E '^CONFIG_(KSU|LTO_CLANG_FULL|LTO_NONE|CFI_CLANG|LOCALVERSION|MODVERSIONS)' .config | head -10
echo ""
echo "===== [6] 内核 include/uapi symlink 状态 ====="
ls -la include/uapi 2>&1 | head -2
ls -la include/asm 2>&1 | head -2
