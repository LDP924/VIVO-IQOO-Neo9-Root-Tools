#!/usr/bin/env python3
# verify_patches.py — 对 16.2.13.2 的 kernel.bin 核验 2026-07 批次 LPE 是否被 vivo cherry-pick
# 判据（来自上游补丁 diff）：
#   43499  remove_waiter:           patched 函数内零 current（无 mrs sp_el0）
#   64560  posix_cpu_timer_del:     kallsyms 出现 timer_lock_sighand；del 内有 sighand 重试环
#   64468  binder_free_transaction: patched 内含 binder_thread_dec_tmpref 调用（to_thread pin）
import sys
from elftools.elf.elffile import ELFFile
from capstone import Cs, CS_ARCH_ARM64, CS_MODE_LITTLE_ENDIAN

funcs = {}   # name -> va
with open('kernel.elf', 'rb') as f:
    elf = ELFFile(f)
    st = elf.get_section_by_name('.symtab')
    for s in st.iter_symbols():
        if s['st_info']['type'] == 'STT_FUNC' and s.name:
            funcs[s.name] = s['st_value']
    seg = next(p for p in elf.iter_segments() if p['p_type'] == 'PT_LOAD')
    blob = seg.data()
    vaddr = seg['p_vaddr']

rev = {v: n for n, v in funcs.items()}

def fsize(va):
    # 函数结束 = 所有符号里下一个更大 VA（kallsyms 按地址排序）
    nxt = min((v for v in funcs.values() if v > va), default=va + 0x1000)
    return min(nxt - va, 0x8000)

md = Cs(CS_ARCH_ARM64, CS_MODE_LITTLE_ENDIAN)

def dis(name):
    va = funcs[name]
    off = va - vaddr
    return list(md.disasm(blob[off:off+fsize(va)], va))

def bls(insns):
    r = []
    for ins in insns:
        if ins.mnemonic == 'bl':
            try:
                r.append(rev.get(int(ins.op_str.replace('#', ''), 16), '?'))
            except ValueError:
                pass
    return r

# --- 43499 ---
insns = dis('remove_waiter')
sp0 = [f'{hex(i.address)}  {i.mnemonic} {i.op_str}' for i in insns if 'sp_el0' in i.op_str]
print(f"== remove_waiter @ {hex(funcs['remove_waiter'])} insns={len(insns)}  mrs-sp_el0 = {len(sp0)}")
for l in sp0: print('  ', l)
print('   CVE-2026-43499:', 'UNPATCHED' if sp0 else 'PATCHED')
print()

# --- 64560 ---
print('kallsyms timer_lock_sighand:', 'PRESENT' if 'timer_lock_sighand' in funcs else 'ABSENT')
insns = dis('posix_cpu_timer_del')
calls = bls(insns)
back = 0
for ins in insns:
    if ins.mnemonic.startswith('b.') or ins.mnemonic.startswith('cb') or ins.mnemonic.startswith('tb'):
        try:
            tv = int(ins.op_str.split('#')[-1].strip(), 16)
            if tv < ins.address: back += 1
        except (ValueError, IndexError): pass
print(f"== posix_cpu_timer_del @ {hex(funcs['posix_cpu_timer_del'])} insns={len(insns)} backward={back}")
print('   calls:', ', '.join(calls))
print('   CVE-2026-64560:', 'PATCHED(have helper)' if 'timer_lock_sighand' in funcs else
      ('LIKELY UNPATCHED (no helper, no retry-loop)' if back == 0 else 'INCONCLUSIVE'))
print()

# --- 64468 ---
insns = dis('binder_free_transaction')
calls = bls(insns)
print(f"== binder_free_transaction @ {hex(funcs['binder_free_transaction'])} insns={len(insns)}")
print('   calls:', ', '.join(calls))
dec = any('binder_thread_dec_tmpref' in c for c in calls)
print('   CVE-2026-64468:', 'PATCHED' if dec else 'UNPATCHED')
