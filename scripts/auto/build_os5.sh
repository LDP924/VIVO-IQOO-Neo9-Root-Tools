#!/bin/bash
# 原版 KernelSU ko (流程照 ksu-root/scripts/build_ksu_module.sh) + v1.2.0 原版底包组装
set -uxo pipefail

SHA="${1:?build_os5.sh <sha>}"
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
WORK="$HOME/kbuild"
OUT="$ROOT/out"
VER_DIR="PD2338_A_15.1.14.7.W10.V000L1"
mkdir -p "$WORK" "$OUT"
fail() { echo "FAIL: $1"; exit 1; }
HOSTVARS="HOSTCC=gcc HOSTCXX=g++ HOSTAR=ar HOSTLD=ld"
J="-j$(nproc)"

[ -f "$WORK/android13-5.15/Makefile" ] || \
  git clone --depth 1 -b android13-5.15 \
    https://github.com/aosp-mirror/kernel_common.git "$WORK/android13-5.15" || fail "内核树clone"
cd "$WORK/android13-5.15"

sed -i "s/^SUBLEVEL = .*/SUBLEVEL = 178/" Makefile
echo "" > .scmversion
rm -f include/generated/utsrelease.h include/config/kernel.release .version

grep -q 'vivo ' include/linux/vermagic.h || \
  patch -p1 < "$ROOT/ksu-root/patches/kernel-integration.patch" || fail "补丁"

[ -d "$WORK/KernelSU/.git" ] || \
  git clone https://github.com/tiann/KernelSU.git "$WORK/KernelSU" || fail "KSU clone"
git -C "$WORK/KernelSU" fetch origin main --tags || fail "fetch"
git -C "$WORK/KernelSU" checkout "$SHA" || fail "checkout"
git -C "$WORK/KernelSU" update-index --refresh 2>/dev/null || true
KSU_KERNEL="$WORK/KernelSU/kernel"
KSU_VER=$((30000 + $(git -C "$WORK/KernelSU" rev-list --count HEAD)))
echo "${KSU_VER}" > "$OUT/.expected-ksu-version"
ln -sfn "$KSU_KERNEL" drivers/kernelsu

cp "$ROOT/ksu-root/kernel-module/vivo.config" .config
scripts/config -m KSU -d LOCALVERSION_AUTO --set-str LOCALVERSION "-gaacdc35637c4-dirty"

PAHOLE="$PWD/scripts/dummy-tools/pahole"
if ! command -v pahole >/dev/null 2>&1; then
  mkdir -p "$(dirname "$PAHOLE")"
  printf '#!/bin/sh\necho v99.99\n' > "$PAHOLE"; chmod +x "$PAHOLE"
  HOSTVARS="$HOSTVARS PAHOLE=$PAHOLE"
fi

clang --target=aarch64-linux-gnu -flto=full -fsanitize=cfi -fvisibility=hidden \
  -c -o /dev/null -x c <(printf 'int p(void){return 1;}\n') 2>/dev/null \
  || fail "clang不支持CFI+LTO(需NDK r27)"

make ARCH=arm64 LLVM=1 LLVM_IAS=1 $HOSTVARS olddefconfig >>"$OUT/.build.log" 2>&1 || { tail -15 "$OUT/.build.log"; fail "olddefconfig"; }
make ARCH=arm64 LLVM=1 LLVM_IAS=1 $HOSTVARS syncconfig >>"$OUT/.build.log" 2>&1 || fail "syncconfig"
grep '^CONFIG_LTO_CLANG_FULL=y' include/config/auto.conf || fail "LTO被关(布局必错)"

make ARCH=arm64 LLVM=1 LLVM_IAS=1 $HOSTVARS prepare modules_prepare $J >>"$OUT/.build.log" 2>&1 || fail "prepare"

make ARCH=arm64 LLVM=1 LLVM_IAS=1 $HOSTVARS scripts $J >>"$OUT/.build.log" 2>&1 || true
GEN="$PWD/scripts/selinux/genheaders/genheaders"
mkdir -p security/selinux/include/generated
[ -x "$GEN" ] && "$GEN" security/selinux/include/generated/flask.h \
  security/selinux/include/generated/av_permissions.h 2>/dev/null || true

make ARCH=arm64 LLVM=1 LLVM_IAS=1 $HOSTVARS M="$KSU_KERNEL" src="$KSU_KERNEL" clean >>"$OUT/.build.log" 2>&1 || true
rm -f "$KSU_KERNEL/kernelsu.ko"
make ARCH=arm64 LLVM=1 LLVM_IAS=1 $HOSTVARS \
  KCFLAGS="-I$PWD/security/selinux/include/generated" \
  M="$KSU_KERNEL" src="$KSU_KERNEL" modules $J >>"$OUT/.build.log" 2>&1 || fail "模块编译"
KO="$KSU_KERNEL/kernelsu.ko"
[ -f "$KO" ] || { tail -15 "$OUT/.build.log"; fail "ko未产出"; }
cp "$KO" "$OUT/kernelsu-vanilla-197.ko"

IOV=$(readelf -r "$KO" 2>/dev/null | awk '/rela\.gnu\.linkonce\.this_module/{f=1;next} f&&/^[0-9a-f]{12,16} /{print $1; exit}')
IOV=$((16#${IOV:-0}))
[ "$IOV" = "$((0x178))" ] || fail "init偏移0x$(printf %x $IOV)≠0x178(布局错)"

ACTUAL=$(grep -oE 'KernelSU version: [0-9]+' "$OUT/.build.log" | tail -1 | grep -oE '[0-9]+')
[ "${ACTUAL:-0}" = "$KSU_VER" ] || fail "版本码${ACTUAL:-?}≠${KSU_VER}"

# 底包自动发现: 全仓找 KSUOneTap-v*.apk, 取最大版本, 同版本取最新提交
BASE_CANDS=$(find "$ROOT" -name "KSUOneTap-v*.apk" -not -path "*/.git/*" 2>/dev/null)
[ -n "$BASE_CANDS" ] || fail "仓库里找不到KSUOneTap底包"
BASE_APK=$(echo "$BASE_CANDS" | while read -r f; do
  v=$(basename "$f" | grep -oE 'v[0-9]+(\.[0-9]+)+' | head -1 | tr -d 'v')
  rel=$(realpath --relative-to="$ROOT" "$f" 2>/dev/null || echo "$f")
  t=$(git -C "$ROOT" log -1 --format=%ct -- "$rel" 2>/dev/null || echo 0)
  echo "$v|$t|$f"
done | sort -t'|' -k1,1rV -k2,2rn | head -1 | cut -d'|' -f3)
[ -f "$BASE_APK" ] || fail "底包探测失败"
BASE_VER=$(basename "$BASE_APK" | grep -oE '[0-9]+\.[0-9]+\.[0-9]+' | head -1)
echo "底包: $(basename "$BASE_APK") (v${BASE_VER})"
# 版本计数: Release 历史推导 (同底包最大尾号+1, 换底包归1)
CNT=$(curl -sf "https://api.github.com/repos/LDP924/VIVO-IQOO-Neo9-Root-Tools/releases?per_page=30" 2>/dev/null | python3 -c "
import json, sys
base = 'v${BASE_VER}.'
best = 0
for r in json.load(sys.stdin):
    tag = r.get('tag_name', '')
    if tag.startswith(base):
        try: best = max(best, int(tag.split('.')[3].split('-')[0]))
        except (ValueError, IndexError): pass
print(best)
" 2>/dev/null || echo 0)
CNT=$((CNT + 1))
VER_NAME="v${BASE_VER}.${CNT}"
OUT_APK="KSUOneTap-${VER_NAME}-kernelsu-original.apk"
echo "ver=${VER_NAME}" >> "${GITHUB_OUTPUT:-/dev/null}"
echo "本次版本: ${VER_NAME} (内核迭代 #${CNT})"

D="$WORK/onetap"
rm -rf "$D"; mkdir -p "$D"
cp "$BASE_APK" "$D/base.apk" || fail "底包"
cd "$D" && unzip -o -q base.apk || fail "底包解包"

python3 - AndroidManifest.xml "$VER_NAME" "v${BASE_VER}-kernelsu-original" << 'PYEOF' || fail "Manifest版本名"
import struct, sys
p, name, anchor = sys.argv[1], sys.argv[2], sys.argv[3]
data = bytearray(open(p, 'rb').read())
old = anchor.encode('utf-16-le')
j = data.find(old)
assert j > 0, f"底包versionName锚 {anchor} 未找到"
new = name.encode('utf-16-le')
data[j-2:j] = struct.pack('<H', len(name))
data[j:j+len(new)] = new
data[j+len(new):j+len(old)] = b'\x00' * (len(old) - len(new))
open(p, 'wb').write(bytes(data))
print("versionName:", name)
PYEOF

curl -sfL "https://github.com/linux-tools/vivo_iqoo_neo_9_root_research_on_CVE-2025-21479/releases/download/v1.0.6/KSUOneTap-v1.0.6.apk" -o "$WORK/v106.apk" || fail "v1.0.6下载"
unzip -o -q "$WORK/v106.apk" "assets/${VER_DIR}/*" -d "$WORK/v106x" || fail "v1.0.6解包"
mkdir -p "assets/$VER_DIR"
for f in exploit_vivo_neo9 unpatch.ko vrpatch.ko; do
  cp "$WORK/v106x/assets/$VER_DIR/$f" "assets/$VER_DIR/$f" || fail "借件:$f"
done
cp "$OUT/kernelsu-vanilla-197.ko" "assets/$VER_DIR/kernelsu-vanilla-197.ko"

M5_KO=$(md5sum "$OUT/kernelsu-vanilla-197.ko" | awk '{print $1}')
M5_UN=$(md5sum "assets/$VER_DIR/unpatch.ko" | awk '{print $1}')
M5_VR=$(md5sum "assets/$VER_DIR/vrpatch.ko" | awk '{print $1}')
M5_EX=$(md5sum "assets/$VER_DIR/exploit_vivo_neo9" | awk '{print $1}')
SHORT=$(echo "$SHA" | cut -c1-8)
cat > "assets/$VER_DIR/SYSTEM.txt" << TXT
# 系统版本绑定产物目录 — ${VER_DIR}
# 自动构建: tiann/KernelSU main @ ${SHORT}

build=${VER_DIR}
device=PD2338
model=V2338A
originos=5
android=15
kernel=5.15.178-gaacdc35637c4-dirty

tier=full
exploit=gpu-afpacket (v1.0.6 原版件, 真机验证 2026-09-27)
shizuku=required
kernelsu=${KSU_VER}
ksumodule=kernelsu-vanilla-197.ko (原版 ${SHORT} 自动构建) + unpatch.ko + vrpatch.ko
manager=KernelSU.apk (官方, 底包自带)
ksud=nightly 同源, 失败用底包自带 v3.3.0

md5.kernelsu-vanilla-197.ko=${M5_KO}
md5.unpatch.ko=${M5_UN}
md5.vrpatch.ko=${M5_VR}
md5.exploit_vivo_neo9=${M5_EX}
TXT

# 管理器+ksud: nightly 同源优先, 底包自带回退
MGR_APK=""; KSUD_BIN=""
if curl -sfL "https://nightly.link/tiann/KernelSU/workflows/build-manager/main/Manager-release.zip" -o "$WORK/mgr.zip" 2>/dev/null \
   && unzip -o "$WORK/mgr.zip" -d "$WORK/mgr/" >/dev/null 2>&1; then
  MGR_APK=$(find "$WORK/mgr" -name '*.apk' 2>/dev/null | grep arm64 | head -1)
  KSUD_BIN=$(find "$WORK/mgr" -name 'ksud-aarch64*' 2>/dev/null | head -1)
fi
if [ -n "$MGR_APK" ] && [ -n "$KSUD_BIN" ]; then
  cp "$MGR_APK" assets/KernelSU.apk
  cp "$KSUD_BIN" assets/ksud
  echo "管理器+ksud: nightly 同源"
else
  echo "管理器+ksud: 底包自带(v3.3.0)"
fi

rm -f ../KSUOneTap-auto.apk ../base-aligned.apk
zip -q -r ../KSUOneTap-auto.apk . -x "base.apk"
zip -q -d ../KSUOneTap-auto.apk resources.arsc 2>/dev/null || true
zip -q -0 ../KSUOneTap-auto.apk resources.arsc
zipalign -f -p 4 ../KSUOneTap-auto.apk ../base-aligned.apk || fail "zipalign"
KS="$ROOT/ksuonetap/ksuonetap/.keystore/ksuonetap.keystore"
[ -f "$KS" ] || fail "官方keystore缺失"
apksigner sign --ks "$KS" --ks-key-alias ksu --ks-pass pass:ksupass --key-pass pass:ksupass \
  --out "$OUT/$OUT_APK" ../base-aligned.apk || fail "签名"
[ -f "$OUT/$OUT_APK" ] || fail "APK未产出"

CM="$(curl -sf "https://api.github.com/repos/tiann/KernelSU/commits/${SHA}" | python3 -c "
import json, re, sys
d = json.load(sys.stdin)
msg = (d.get('commit') or {}).get('message', '').split(chr(10))[0][:70]
# (#NNNN) 引用的上游 PR 编号转超链接（截断后不完整的不动，纯文本降级）
print(re.sub(r'\(#([0-9]+)\)', r'[#\1](https://github.com/tiann/KernelSU/pull/\1)', msg))
" 2>/dev/null || true)"
cat > "$OUT/.release-notes.md" << NOTES
iQOO Neo9 · OriginOS 5 · 15.1.14.7 · 5.15.178

部署内核: [tiann/KernelSU @ ${SHORT}](https://github.com/tiann/KernelSU/commit/${SHA})
${CM:-}

NOTES

echo "BUILD OK: KernelSU_VERSION=${KSU_VER} @${SHORT} init=0x178"
