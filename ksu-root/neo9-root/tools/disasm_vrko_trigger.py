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

def disasm(start, size, label):
    print(f'===== {label} =====')
    for ins in md.disasm(text[start:start + size], start):
        print(f'0x{ins.address:04x}: {ins.mnemonic:8s} {ins.op_str}')

# 触发路径 0x3100-0x3200
disasm(0x3100, 0x100, 'trigger path 0x3100')

# 找 0x2ecc 的调用者: .text 里所有 b/bl 到 0x2ecc
print('===== callers of 0x2ecc =====')
for ins in md.disasm(text, 0):
    if ins.mnemonic in ('bl', 'b') and ins.op_str.replace('#', '').strip() in ('0x2ecc',):
        print(f'  0x{ins.address:04x}: {ins.mnemonic} {ins.op_str}')
# 更宽松: 解析相对 bl 目标
for i in range(0, len(text) - 4, 4):
    w = int.from_bytes(text[i:i+4], 'little')
    if (w & 0xFC000000) == 0x94000000:  # bl
        imm = w & 0x03FFFFFF
        if imm & 0x2000000:
            imm -= 0x4000000
        tgt = i + imm * 4
        if tgt == 0x2ecc:
            print(f'  bl @ 0x{i:04x} -> 0x2ecc')
    if (w & 0xFC000000) == 0x14000000:  # b
        imm = w & 0x03FFFFFF
        if imm & 0x2000000:
            imm -= 0x4000000
        tgt = i + imm * 4
        if tgt == 0x2ecc:
            print(f'  b  @ 0x{i:04x} -> 0x2ecc')
