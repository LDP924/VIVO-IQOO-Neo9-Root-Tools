#!/bin/bash
set -uo pipefail
REPO="tiann/KernelSU"
RREPO="LDP924/VIVO-IQOO-Neo9-Root-Tools"

SHA=$(git ls-remote "https://github.com/${REPO}.git" refs/heads/main 2>/dev/null | awk '{print $1}')
[ -z "$SHA" ] && sleep 5 && SHA=$(git ls-remote "https://github.com/${REPO}.git" refs/heads/main 2>/dev/null | awk '{print $1}')
OUT="${GITHUB_OUTPUT:-/dev/null}"
if [ -z "$SHA" ]; then
  echo "need_build=false" >> "$OUT"
  exit 0
fi
echo "ksu_sha=${SHA}" >> "$OUT"
echo "ksu_sha8=${SHA:0:8}" >> "$OUT"

# 上次构建的内核: 从最新 Release 的 tag 尾段读 (不依赖本地状态)
LAST=$(curl -sf "https://api.github.com/repos/${RREPO}/releases?per_page=5" 2>/dev/null | python3 -c "
import json, sys
for r in json.load(sys.stdin):
    tag = r.get('tag_name', '')
    parts = tag.split('-')
    if len(parts) >= 2:
        print(parts[-1])
        break
" 2>/dev/null || true)

if [ "${SHA:0:8}" = "$LAST" ]; then
  echo "need_build=false" >> "$OUT"
else
  echo "need_build=true" >> "$OUT"
fi
