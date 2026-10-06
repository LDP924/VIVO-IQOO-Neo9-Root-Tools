import os
import sys
VRT_ROOT = os.environ.get("VRT_ROOT", "/mnt/d/payload-dumper-go")  # 原分析机为 D:\payload-dumper-go
sys.path.insert(0, os.path.join(VRT_ROOT, "vrtools"))
from elftools.elf.elffile import ELFFile
from capstone import Cs, CS_ARCH_ARM64, CS_MODE_ARM

KO = os.path.join(VRT_ROOT, "VIVO_Neo_9_PD2338C_GKIv1_5.15.178/vendorboot_For_PD2338C_extracted/lib/modules/vr.ko")
f = open(KO, 'rb')
elf = ELFFile(f)

def disasm(secname, start, size, label):
    sec = elf.get_section_by_name(secname)
    data = sec.data()
    md = Cs(CS_ARCH_ARM64, CS_MODE_ARM)
    print(f'===== {label} ({secname} 0x{start:x}) =====')
    for ins in md.disasm(data[start:start + size], start):
        print(f'0x{ins.address:05x}: {ins.mnemonic:8s} {ins.op_str}')

# kprobe 注册调用点附近 (x0 = kprobe 结构)
disasm('.init.text', 0x1150, 0xB0, 'init 0x11c0 register_kprobe')
disasm('.init.text', 0x1200, 0x80, 'init 0x125c register_kprobe')
disasm('.init.text', 0x3c20, 0x80, 'init 0x3c78 register_kprobe')
disasm('.init.text', 0x1600, 0x70, 'init 0x1644 tracepoint reg')
disasm('.text', 0x2150, 0x90, 'text 0x21ac register_kprobe')
