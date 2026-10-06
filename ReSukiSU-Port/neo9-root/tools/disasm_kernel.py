# 反汇编 kernel.img 中指定偏移的代码, 用于分析 prepare_kernel_cred / commit_creds
import os
import sys
VRT_ROOT = os.environ.get("VRT_ROOT", "/mnt/d/payload-dumper-go")  # 原分析机为 D:\payload-dumper-go
sys.path.insert(0, os.path.join(VRT_ROOT, "vrtools"))
from capstone import Cs, CS_ARCH_ARM64, CS_MODE_ARM, CS_OP_IMM, CS_OP_REG

IMG = os.path.join(VRT_ROOT, "VIVO_Neo_9_PD2338C_GKIv1_5.15.178/For_PD2338C_Kernel/kernel.img")

# 符号: (名字, 偏移, 长度)
SYMS = [
    ('prepare_kernel_cred', 0x1ae31c, 0x300),
    ('commit_creds',        0x1aef14, 0x200),
    ('__arm64_sys_vhangup', 0x5dc898, 0x80),
]

data = open(IMG, 'rb').read()
md = Cs(CS_ARCH_ARM64, CS_MODE_ARM)
md.detail = True

# 先收集函数边界内所有 bl/b 目标(相对) 用于注释
for name, off, size in SYMS:
    print('=' * 80)
    print(f'{name} @ 0x{off:x} (+0x{off-0x1ae31c:x} rel prepare)')
    print('=' * 80)
    code = data[off:off + size]
    insns = list(md.disasm(code, off))
    # 打印
    for ins in insns:
        txt = f'  0x{ins.address - off:04x}: {ins.mnemonic:8s} {ins.op_str}'
        print(txt)
    print()
