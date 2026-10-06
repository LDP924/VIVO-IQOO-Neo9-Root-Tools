#!/system/bin/sh
# env.sh - 临时把 /data/local/tmp 加入 PATH (当前 shell 会话)
# 用法: . /data/local/tmp/env.sh   (source 加载, 本会话生效)
# 或:   source /data/local/tmp/env.sh
# 效果: su / u0 / busybox 等工具可直接短名调用（rootc 已归档, 见 neo9-root/archive/old-client/）
export PATH=/data/local/tmp:$PATH
echo "PATH updated: /data/local/tmp added (su/u0 可用)"
