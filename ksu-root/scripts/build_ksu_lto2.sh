#!/bin/bash
# build_ksu_lto2.sh - LTO+CFI kernelsu build (config already set)
#
# ⚙️ 设备适配 (WSL 构建环境, 移植到其他设备/环境时修改):
#   - 路径: /root/kernel/source (内核源码树) / /root/kernel/resukisu (ReSukiSU 源码)
#           /mnt/d/... (Windows 挂载, 产物输出) —— 按实际环境改
#   - 内核版本: CONFIG_LOCALVERSION="-gaacdc35637c4-dirty" 与 vermagic "vivo" 补丁
#           按目标设备内核的 vermagic 修改 (见 docs/KERNEL_INTEGRATION.md §1.1/1.3)
#   - 工具链: clang (本机 apt clang 18.1.3; 若设备内核由其他版本 clang 构建,
#           建议使用相同版本避免 ABI/CFI 差异)
#   - 配置来源: 第 [1] 步用 vivo.config 为基础 (设备 /proc/config.gz),
#           其他设备请替换为对应 config
#   - 关键: LTO_CLANG_FULL + CFI_CLANG 必须与设备内核一致 (struct module 布局!)
export ARCH=arm64 LLVM=1 LLVM_IAS=1
cd /root/kernel/source || exit 1

echo "== [1] verify config =="
grep -E '^CONFIG_(KSU=|LTO_CLANG_FULL|CFI_CLANG)' .config
grep -E '^CONFIG_LTO_CLANG_FULL' include/config/auto.conf || { touch .config; make ARCH=arm64 LLVM=1 LLVM_IAS=1 syncconfig > /tmp/s2.log 2>&1; grep -E '^CONFIG_LTO_CLANG_FULL' include/config/auto.conf; }

echo "== [2] prepare =="
make ARCH=arm64 LLVM=1 LLVM_IAS=1 prepare modules_prepare > /tmp/prep_l2.log 2>&1
echo "prepare rc=$?"
tail -5 /tmp/prep_l2.log

echo "== [3] selinux headers =="
mkdir -p security/selinux/include/generated
scripts/selinux/genheaders/genheaders \
    security/selinux/include/generated/flask.h \
    security/selinux/include/generated/av_permissions.h 2>/dev/null || echo "WARN genheaders"
ls security/selinux/include/generated/flask.h 2>/dev/null || true

echo "== [4] clean KSU objs =="
cd /root/kernel/resukisu/kernel
make ARCH=arm64 LLVM=1 LLVM_IAS=1 M=/root/kernel/resukisu/kernel src=/root/kernel/resukisu/kernel clean > /tmp/clean_l2.log 2>&1 || true

echo "== [5] build =="
cd /root/kernel/source
make ARCH=arm64 LLVM=1 LLVM_IAS=1 \
    KCFLAGS="-I/root/kernel/source/security/selinux/include/generated" \
    M=/root/kernel/resukisu/kernel src=/root/kernel/resukisu/kernel modules -j24 > /tmp/build_l2.log 2>&1
echo "BUILD RC=$?"
grep -cE '^  CC ' /tmp/build_l2.log || true
tail -12 /tmp/build_l2.log

echo "== [6] product =="
ls -la /root/kernel/resukisu/kernel/kernelsu.ko 2>&1
if [ -f /root/kernel/resukisu/kernel/kernelsu.ko ]; then
    strings /root/kernel/resukisu/kernel/kernelsu.ko | grep vermagic=
    cp /root/kernel/resukisu/kernel/kernelsu.ko /mnt/d/payload-dumper-go/_ksu_build/kernelsu-vivo-lto.ko
    echo "COPIED kernelsu-vivo-lto.ko"
fi
