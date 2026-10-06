import os
import sys
VRT_ROOT = os.environ.get("VRT_ROOT", "/mnt/d/payload-dumper-go")  # 原分析机为 D:\payload-dumper-go
sys.path.insert(0, os.path.join(VRT_ROOT, "vrtools"))
from elftools.elf.elffile import ELFFile

KO = os.path.join(VRT_ROOT, "VIVO_Neo_9_PD2338C_GKIv1_5.15.178/vendorboot_For_PD2338C_extracted/lib/modules/vr.ko")
f = open(KO, 'rb')
elf = ELFFile(f)
initdata = elf.get_section_by_name('.init.data').data()
initrodata = elf.get_section_by_name('.init.rodata').data()
rodata = elf.get_section_by_name('.rodata').data()
data = elf.get_section_by_name('.data').data()

def cstr(buf, off, maxlen=64):
    s = buf[off:off + maxlen]
    z = s.find(b'\x00')
    if z >= 0:
        s = s[:z]
    return s.decode('latin1', 'replace')

# 0x3c20 解密: x9 = 加密串(20字节, xor 0xb9 递减), x20 = 解密缓冲
print('=== XOR 解密候选 (0x3c20) ===')
# 在 .init.data 中找 20 字节的加密串(非可打印, 与 0xb9 递减 xor 后可打印)
def try_xor(buf, label):
    for off in range(0, len(buf) - 20, 4):
        b = buf[off:off + 20]
        dec = bytes([x ^ (0xb9 - i) for i, x in enumerate(b)])
        if all(32 <= c < 127 for c in dec):
            print(f'  {label}+0x{off:x}: {dec.decode("latin1")!r}')
try_xor(initdata, 'init.data')
try_xor(initrodata, 'init.rodata')
try_xor(rodata, 'rodata')

# 0x11c0 kprobe 目标: 字符串 "module_memfree?" 在 .init.rodata/.rodata
print('=== module_mem* 字符串 ===')
for label, buf in [('init.rodata', initrodata), ('rodata', rodata), ('init.data', initdata)]:
    idx = 0
    while True:
        idx = buf.find(b'module_mem', idx)
        if idx < 0:
            break
        print(f'  {label}+0x{idx:x}: {cstr(buf, idx)!r}')
        idx += 1

print('=== 0x21d0 "avpec..." 字符串 ===')
for label, buf in [('init.rodata', initrodata), ('rodata', rodata), ('data', data)]:
    idx = 0
    while True:
        idx = buf.find(b'av', idx)
        if idx < 0:
            break
        s = cstr(buf, idx)
        if len(s) >= 6:
            print(f'  {label}+0x{idx:x}: {s!r}')
        idx += 1

print('=== 所有看起来像内核符号名的字符串 ===')
import re

for label, buf in [('init.rodata', initrodata), ('rodata', rodata)]:
    for m in re.finditer(rb'[a-z_][a-z0-9_]{5,}', buf):
        s = m.group().decode()
        if any(k in s for k in ['cred', 'task', 'sys_', 'commit', 'uid', 'cap', 'selinux', 'enforce', 'root', 'panic', 'kthread', 'ftrace', 'kprobe', 'vfs', 'exec', 'mm_', 'security']):
            print(f'  {label}+0x{m.start():x}: {s}')
