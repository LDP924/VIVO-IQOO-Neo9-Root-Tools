#!/bin/bash
# $1 = 检查项: vermagic|offset|version|apk
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
OUT="$ROOT/out"
VER_DIR="PD2338_A_15.1.14.7.W10.V000L1"
KO="$OUT/kernelsu-vanilla-197.ko"
APK="$OUT/KSUOneTap-auto.apk"

case "${1:-all}" in
vermagic)
  VM=$(readelf -p .modinfo "$KO" 2>/dev/null | grep -o 'vermagic=[^ ]*' | head -1)
  echo "vermagic: $VM"
  case "$VM" in
    vermagic=5.15.178-gaacdc35637c4-dirty*) echo PASS ;;
    *) echo FAIL; exit 1 ;;
  esac
  ;;
offset)
  OFF=$(readelf -r "$KO" 2>/dev/null | awk '/rela\.gnu\.linkonce\.this_module/{f=1;next} f&&/^[0-9a-f]{12,16} /{print $1; exit}')
  echo "init offset: $OFF"
  [ -n "$OFF" ] && [ $((16#$OFF)) -eq $((0x178)) ] || { echo FAIL; exit 1; }
  echo PASS
  ;;
version)
  EXPECT=$(cat "$OUT/.expected-ksu-version")
  python3 - "$KO" "$EXPECT" << 'PYEOF'
import struct, sys
data = open(sys.argv[1], 'rb').read()
t = struct.pack('<I', int(sys.argv[2]))
sys.exit(0 if sum(1 for i in range(len(data)-4) if data[i:i+4] == t) else 1)
PYEOF
  echo "PASS: KernelSU_VERSION=${EXPECT}"
  ;;
apk)
  apksigner verify "$APK" >/dev/null 2>&1 || { echo "FAIL: 签名"; exit 1; }
  M5=$(md5sum "$KO" | awk '{print $1}')
  unzip -p "$APK" "assets/$VER_DIR/kernelsu-vanilla-197.ko" 2>/dev/null | md5sum | grep -q "$M5" \
    || { echo "FAIL: 内嵌ko不一致"; exit 1; }
  unzip -p "$APK" "assets/$VER_DIR/SYSTEM.txt" 2>/dev/null | grep -q "^md5.kernelsu-vanilla-197.ko=${M5}" \
    || { echo "FAIL: SYSTEM.txt md5行未同步"; exit 1; }
  echo PASS
  ;;
*)
  echo "用法: verify_artifacts.sh vermagic|offset|version|apk"
  exit 1
  ;;
esac
