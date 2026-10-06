import os
import sys
VRT_ROOT = os.environ.get("VRT_ROOT", "/mnt/d/payload-dumper-go")  # 原分析机为 D:\payload-dumper-go
sys.path.insert(0, os.path.join(VRT_ROOT, "vrtools"))
from elftools.elf.elffile import ELFFile
from capstone import Cs, CS_ARCH_ARM64, CS_MODE_ARM

KO = os.path.join(VRT_ROOT, "VIVO_Neo_9_PD2338C_GKIv1_5.15.178/vendorboot_For_PD2338C_extracted/lib/modules/vr.ko")
f = open(KO, 'rb')
elf = ELFFile(f)
text = elf.get_section_by_name('.text').data()
md = Cs(CS_ARCH_ARM64, CS_MODE_ARM)
md.detail = True

# 输出 .text 全反汇编到文件
out = []
for ins in md.disasm(text, 0):
    out.append(f'0x{ins.address:04x}: {ins.mnemonic:8s} {ins.op_str}')
with open(os.path.join(VRT_ROOT, "vrko_text_disasm.txt"), 'w') as fh:
    fh.write('\n'.join(out))
print('text instructions:', len(out))

# 找有趣模式: mrs sp_el0 / uid / 常量比较 / 循环
pats = ['sp_el0', '#0x3e8', '#0x3e9', 'uid', 'cred']
for i, line in enumerate(out):
    if any(p in line for p in ['sp_el0', 'current']):
        lo = max(0, i - 3)
        hi = min(len(out), i + 4)
        print('--- sp_el0 usage @', line.split(':')[0])
        for j in range(lo, hi):
            print('   ', out[j])
