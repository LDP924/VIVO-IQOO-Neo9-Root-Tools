import os
import sys
VRT_ROOT = os.environ.get("VRT_ROOT", "/mnt/d/payload-dumper-go")  # 原分析机为 D:\payload-dumper-go
sys.path.insert(0, os.path.join(VRT_ROOT, "vrtools"))
from elftools.elf.elffile import ELFFile
from capstone import Cs, CS_ARCH_ARM64, CS_MODE_ARM

KO = os.path.join(VRT_ROOT, "VIVO_Neo_9_PD2338C_GKIv1_5.15.178/vendorboot_For_PD2338C_extracted/lib/modules/vr.ko")
f = open(KO, 'rb')
elf = ELFFile(f)
text = elf.get_section_by_name('.init.text')
data = text.data()
base = 0x2b0  # init_module
size = 0x600
md = Cs(CS_ARCH_ARM64, CS_MODE_ARM)
md.detail = True
code = data[base:base + size]
for ins in md.disasm(code, base):
    op = ins.op_str
    mark = ''
    # 识别 bl/b 目标(相对), adrp+add 组合(字符串/数据引用), 特殊寄存器
    if ins.mnemonic in ('bl', 'b', 'cbz', 'cbnz', 'b.eq', 'b.ne', 'b.lt', 'b.gt', 'b.hi', 'b.ls', 'b.ge', 'b.le', 'tbz', 'tbnz'):
        mark = ' <-- branch'
    print(f'0x{ins.address:05x}: {ins.mnemonic:8s} {op}{mark}')
