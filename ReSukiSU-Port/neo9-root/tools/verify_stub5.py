import os
import sys
VRT_ROOT = os.environ.get("VRT_ROOT", "/mnt/d/payload-dumper-go")  # 原分析机为 D:\payload-dumper-go
sys.path.insert(0, os.path.join(VRT_ROOT, "vrtools"))
from capstone import Cs, CS_ARCH_ARM64, CS_MODE_ARM

words = [
    0xd503233f, 0xa9bf7bfd, 0x910003fd, 0xd5384108,
    0xd296d769, 0xf2815d89, 0xf2bff809, 0xf2dfffe9,
    0x5282000a, 0x72a2000a, 0xb900012a,
    0xf903c909, 0xf903cd09,
    0xa8c17bfd, 0xd50323bf, 0x52800000, 0xd65f03c0,
]
data = b''.join(w.to_bytes(4, 'little') for w in words)
md = Cs(CS_ARCH_ARM64, CS_MODE_ARM)
for i, ins in enumerate(md.disasm(data, 0)):
    print(f'{i*4:04x}: {ins.mnemonic:8s} {ins.op_str}')
print('total bytes:', len(data))
