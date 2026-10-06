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

# 找 0x2eec 所在函数起点(往回找 paciasp/bti c 函数头) 并反汇编到下一个函数头
def disasm_range(start, end, label):
    print(f'===== {label} 0x{start:x}-0x{end:x} =====')
    for ins in md.disasm(text[start:end], start):
        print(f'0x{ins.address:04x}: {ins.mnemonic:8s} {ins.op_str}')

# 0x2eec 前推找函数头
head = 0x2eec
for i in range(0x2eec, 0x2c00, -4):
    w = int.from_bytes(text[i:i+4], 'little')
    if w == 0xd503233f:  # paciasp
        head = i
        break
disasm_range(head, min(head + 0x400, len(text)), 'uid-check function')
