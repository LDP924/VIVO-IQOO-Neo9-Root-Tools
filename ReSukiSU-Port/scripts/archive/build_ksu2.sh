#!/bin/bash
# build_ksu2.sh - 完整配置 + 编译 (Kconfig 已集成后)
set -e
export ARCH=arm64 LLVM=1 LLVM_IAS=1
cd /root/kernel/source

echo "== [1] 恢复设备 config =="
cp /mnt/d/payload-dumper-go/_ksu_build/vivo.config .config
make ARCH=arm64 LLVM=1 LLVM_IAS=1 olddefconfig > /tmp/olddef.log 2>&1 || true

echo "== [2] KSU 开关 (olddefconfig 之后, 不重跑 olddefconfig) =="
./scripts/config --module KSU
./scripts/config --enable KSU_TRACEPOINT_HOOK
./scripts/config --disable KSU_DEBUG
./scripts/config --disable LOCALVERSION_AUTO
./scripts/config --set-str LOCALVERSION "-gaacdc35637c4-dirty"
./scripts/config --disable LTO_CLANG_FULL 2>/dev/null || true
./scripts/config --enable LTO_NONE 2>/dev/null || true
echo "--- CONFIG_KSU 检查 ---"
grep '^CONFIG_KSU' .config || echo "!! CONFIG_KSU 缺失"
grep '^CONFIG_LOCALVERSION' .config

echo "== [3] prepare =="
make ARCH=arm64 LLVM=1 LLVM_IAS=1 prepare modules_prepare > /tmp/prepare2.log 2>&1 || { tail -20 /tmp/prepare2.log; exit 1; }
echo "prepare OK"

echo "== [4] SELinux headers =="
mkdir -p security/selinux/include/generated
scripts/selinux/genheaders/genheaders \
    security/selinux/include/generated/flask.h \
    security/selinux/include/generated/av_permissions.h 2>/dev/null || echo "WARN: genheaders"
ls -la security/selinux/include/generated/flask.h 2>/dev/null || true

echo "== [5] 编译 =="
make ARCH=arm64 LLVM=1 LLVM_IAS=1 \
    KCFLAGS="-I/root/kernel/source/security/selinux/include/generated" \
    M=/root/kernel/resukisu/kernel src=/root/kernel/resukisu/kernel modules -j24 > /tmp/build3.log 2>&1
echo "BUILD RC=$?"
grep -cE '^  CC ' /tmp/build3.log || true
tail -15 /tmp/build3.log

echo "== [6] 产物 =="
ls -la /root/kernel/resukisu/kernel/kernelsu.ko 2>&1
if [ -f /root/kernel/resukisu/kernel/kernelsu.ko ]; then
    strings /root/kernel/resukisu/kernel/kernelsu.ko | grep vermagic=
    cp /root/kernel/resukisu/kernel/kernelsu.ko /mnt/d/payload-dumper-go/_ksu_build/kernelsu-vivo.ko
    echo "已复制: kernelsu-vivo.ko"
fi
