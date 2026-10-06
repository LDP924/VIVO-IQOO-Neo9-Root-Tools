import os
import sys
VRT_ROOT = os.environ.get("VRT_ROOT", "/mnt/d/payload-dumper-go")  # 原分析机为 D:\payload-dumper-go
sys.path.insert(0, os.path.join(VRT_ROOT, "vrtools"))
from elftools.elf.elffile import ELFFile

KO = os.path.join(VRT_ROOT, "VIVO_Neo_9_PD2338C_GKIv1_5.15.178/vendorboot_For_PD2338C_extracted/lib/modules/vr.ko")
f = open(KO, 'rb')
elf = ELFFile(f)

# 符号名表
symtab = elf.get_section_by_name('.symtab')
syms = {}
for s in symtab.iter_symbols():
    syms[s['st_value']] = s.name

# 需要关注的导入符号
watch = ['register_kprobe', 'unregister_kprobe', 'tracepoint_probe_register_prio',
         'tracepoint_probe_unregister', 'ics_register_ftrace_function',
         'ics_ftrace_set_filter_ip', 'init_task', 'memremap', 'blkdev_get_by_dev',
         'kthread_create_on_node', 'proc_create', 'memstart_addr', 'kimage_voffset']

for secname in ['.rela.init.text', '.rela.text']:
    sec = elf.get_section_by_name(secname)
    if not sec:
        continue
    print(f'===== {secname} =====')
    for rel in sec.iter_relocations():
        symidx = rel['r_info_sym']
        sym = symtab.get_symbol(symidx)
        name = sym.name if sym else '?'
        if name in watch:
            print(f'  offset 0x{rel["r_offset"]:05x}: {name} ({rel["r_info_type"]})')
