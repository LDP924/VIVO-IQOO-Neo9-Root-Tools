#!/bin/bash
set -uo pipefail
REPO="tiann/KernelSU"

SHA=$(git ls-remote "https://github.com/${REPO}.git" refs/heads/main 2>/dev/null | awk '{print $1}')
[ -z "$SHA" ] && sleep 5 && SHA=$(git ls-remote "https://github.com/${REPO}.git" refs/heads/main 2>/dev/null | awk '{print $1}')
if [ -z "$SHA" ]; then
  echo "need_build=false" >> "${GITHUB_OUTPUT:-/dev/null}"
  exit 0
fi

LAST=$(cat "$(dirname "$0")/../../.last-built" 2>/dev/null || true)
OUT="${GITHUB_OUTPUT:-/dev/null}"
echo "ksu_sha=${SHA}" >> "$OUT"

if [ "${SHA:0:10}" = "${LAST:0:10}" ]; then
  echo "need_build=false" >> "$OUT"
else
  echo "need_build=true" >> "$OUT"
fi
