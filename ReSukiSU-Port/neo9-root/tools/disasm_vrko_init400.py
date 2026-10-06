import os
import sys
VRT_ROOT = os.environ.get("VRT_ROOT", "/mnt/d/payload-dumper-go")  # 原分析机为 D:\payload-dumper-go
sys.path.insert(0, os.path.join(VRT_ROOT, "vrtools"))
from elftools.elf.elffile import ELFFile
from capstone import Cs, CS_ARCH_ARM64, CS_MODE_ARM

KO = os.path.join(VRT_ROOT, "VIVO_Neo_9_PD2338C_GKIv1_5.15.178/vendorboot_For_PD2338C_extracted/lib/modules/vr.ko")
f = open(KO, 'rb')
elf = ELFFile(f)
itext = elf.get_section_by_name('.init.text').data()
text = elf.get_section_by_name('.text').data()
md = Cs(CS_ARCH_ARM64, CS_MODE_ARM)

def disasm(buf, start, size, label):
    print(f'===== {label} =====')
    for ins in md.disasm(buf[start:start + size], start):
        print(f'0x{ins.address:04x}: {ins.mnemonic:8s} {ins.op_str}')

# init 0x400 任务遍历函数 (完整, 直到下一个 paciasp)
head = 0x400
end = 0x400
for i in range(0x400 + 4, min(len(itext), 0x400 + 0x200), 4):
    w = int.from_bytes(itext[i:i+4], 'little')
    if w == 0xd503233f:
        end = i
        break
disasm(itext, head, min(end - head, 0x1fc), f'init 0x400 task-walk (to 0x{end:x})')
