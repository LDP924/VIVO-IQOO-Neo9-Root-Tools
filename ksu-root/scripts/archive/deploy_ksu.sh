#!/system/bin/sh
# deploy_ksu.sh - 设备端一键加载 KernelSU (LTO+CFI 构建, 已验证可用)
#
# ⚙️ 设备适配 (移植到其他设备时需修改):
#   - 文件名/路径约定: KO/KSUD/U0/SU 指向 /data/local/tmp 下的二进制,
#     若你的文件名不同 (如 kernelsu.ko), 改下方变量即可。
#   - 加载参数 allow_shell=1: 允许 adb shell (uid 2000) 直接 su;
#     不需要 shell 提权可去掉 (但 Manager 授权流程不受影响)。
#   - kptr_restrict 行为: vivo 会在开机后几分钟把 kptr_restrict 设为 2,
#     导致 ksud 读 kallsyms 全为 0 地址 -> 加载失败。
#     若目标设备无此行为, 此检查仅作提示, 无副作用。
#   - 依赖的二进制 (由主机侧 deploy_all.ps1 推送, 或手动放置):
#       /data/local/tmp/kernelsu-vivo.ko  (LTO+CFI 构建的 KSU 模块)
#       /data/local/tmp/ksud              (KSU 用户态)
#       /data/local/tmp/u0                (setuid(0) 工具, 提供真实 uid 0)
#       /data/local/tmp/su_ksu            (su 客户端, 用于自检)
#
# 用法: 在 temp root (rootd ready) 下: sh /data/local/tmp/deploy_ksu.sh
# 注意: 必须在 kptr_restrict 变 2 之前加载 (开机后 ~5 分钟内)

export PATH=/data/local/tmp:/system/bin:$PATH

# ---- 设备适配区: 按需修改 ----
KO=/data/local/tmp/kernelsu-vivo.ko
KSUD=/data/local/tmp/ksud
U0=/data/local/tmp/u0
SU=/data/local/tmp/su_ksu
LOAD_PARAMS="allow_shell=1"     # 模块加载参数 (shell uid 提权)
# -----------------------------

echo "== [1] kptr_restrict = $(cat /proc/sys/kernel/kptr_restrict 2>&1) =="
[ "$(cat /proc/sys/kernel/kptr_restrict 2>/dev/null)" = "2" ] && echo "WARN: kptr=2, ksud 将读到 0 地址, 加载会失败!"

echo "== [2] 已加载? =="
grep kernelsu /proc/modules && { echo "已加载, 跳过"; exit 0; }

echo "== [3] ksud insmod (u0 真 root, $LOAD_PARAMS) =="
$U0 $KSUD insmod $KO $LOAD_PARAMS
echo "insmod rc=$?"

echo "== [4] 验证 =="
grep kernelsu /proc/modules

echo "== [5] su 自检 =="
$SU -c id
