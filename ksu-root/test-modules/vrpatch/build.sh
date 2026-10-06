#!/bin/bash
# 便捷入口 —— 真正的构建脚本统一在 test-modules/build.sh
# （可移植: 内核树 / 工具链 / 输出目录都可用环境变量覆盖, 不再写死 WSL 路径）。
#
# 用法:
#   bash test-modules/build.sh                    # 默认 profile (15.1.14.7, 内核 5.15.178)
#   FW=14.0.17.2 bash test-modules/build.sh       # OriginOS 4 (内核 5.15.137)
#   MODULE=vrpatch FW=14.0.17.2 bash test-modules/build.sh   # 只编本模块
#
# 产物: test-modules/out/<系统版本>/vrpatch.ko
d="$(cd "$(dirname "$0")" && pwd)"
while [ "$d" != "/" ] && [ ! -f "$d/test-modules/build.sh" ]; do d="$(dirname "$d")"; done
[ -f "$d/test-modules/build.sh" ] || { echo "找不到 test-modules/build.sh"; exit 1; }
exec bash "$d/test-modules/build.sh" "$@"
