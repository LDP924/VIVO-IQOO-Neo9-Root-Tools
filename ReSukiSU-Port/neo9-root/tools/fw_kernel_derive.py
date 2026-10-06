#!/usr/bin/env python3
# 换系统版本时重推 exploit 常量的工具。
#
# 输入 = 从 boot.img 抽出的内核 Image (或已重建的 vmlinux ELF)。
# 没有 BTF 也没关系: 结构体偏移从**反汇编**里读 —— 内核里到处都在访问
# current->cred / cred->cap_effective 这类字段, 哪个函数读哪个偏移是确定的。
#
# 依赖: pip install vmlinux-to-elf pyelftools capstone
#
# 用法:
#   python3 fw_kernel_derive.py <kernel.img> sym _stext prepare_kernel_cred ...
#   python3 fw_kernel_derive.py <kernel.img> dis __arm64_sys_getuid:20 ...
#   python3 fw_kernel_derive.py <kernel.img> find-imm 0x798      # 谁在访问 [reg,#0x798]
#   python3 fw_kernel_derive.py <kernel.img> offsets             # 跑内置推导清单
#
# 首次运行会在 <kernel.img> 旁生成 <kernel.img>.vmlinux (缓存, 约 24s)。

import os
import re
import struct
import subprocess
import sys

from elftools.elf.elffile import ELFFile
from capstone import Cs, CS_ARCH_ARM64, CS_MODE_LITTLE_ENDIAN

STEXT_VA_DEFAULT = 0xFFFFFFC008010000  # vivo SM8550 KIMAGE + 0x10000
KERNEL_VA_BASE = 0xFFFFFFC008000000


def reconstruct_elf(img):
    """用 vmlinux-to-elf 把 Image 重建成带 kallsyms 符号的 ELF (带缓存)。"""
    out = img + ".vmlinux"
    if os.path.exists(out) and os.path.getmtime(out) >= os.path.getmtime(img):
        return out
    exe = os.path.join(os.path.dirname(sys.executable), "vmlinux-to-elf")
    if not os.path.exists(exe):
        exe = "vmlinux-to-elf"
    print(f"[*] 重建符号: {img} -> {out}", file=sys.stderr)
    r = subprocess.run([exe, img, out], capture_output=True, text=True)
    if r.returncode:
        sys.exit(f"vmlinux-to-elf 失败:\n{r.stdout}\n{r.stderr}")
    return out


class K:
    def __init__(self, img):
        self.elf = ELFFile(open(reconstruct_elf(img), "rb"))
        self.sym = {}
        for s in self.elf.get_section_by_name(".symtab").iter_symbols():
            if s.name and s["st_value"]:
                self.sym.setdefault(s.name, s["st_value"])
        self._by_sec = {}
        self.md = Cs(CS_ARCH_ARM64, CS_MODE_LITTLE_ENDIAN)
        self.stext = self.sym.get("_stext", STEXT_VA_DEFAULT)

    # ---- 地址 -> 数据 ----
    def sec_for(self, va):
        for s in self.elf.iter_sections():
            if s["sh_addr"] and s["sh_addr"] <= va < s["sh_addr"] + s["sh_size"] \
               and s["sh_type"] == "SHT_PROGBITS":
                return s
        return None

    def read(self, va, n):
        s = self.sec_for(va)
        if not s:
            return None
        d = s.data()
        return d[va - s["sh_addr"]:va - s["sh_addr"] + n]

    def closest_sym(self, va):
        best = None
        for name, v in self.sym.items():
            if v <= va and (best is None or v > best[1]):
                best = (name, v)
        return best[0] if best else "?"

    def off(self, name):
        """符号相对 _stext 的偏移。"""
        v = self.sym.get(name)
        return None if v is None else v - self.stext

    # ---- 反汇编 ----
    def callers(self, target, back=6):
        """找出所有 `bl <target>` 的位置, 并打印其前 back 条指令 (看参数怎么装的)。"""
        tva = self.sym.get(target)
        if tva is None:
            sys.exit(f"缺少符号 {target}")
        out = []
        for s in self.elf.iter_sections():
            if s.name != ".kernel" or s["sh_type"] != "SHT_PROGBITS":
                continue
            d = s.data()
            base = s["sh_addr"]
            for i in range(0, len(d) - 4, 4):
                w = struct.unpack_from("<I", d, i)[0]
                if w & 0xFC000000 != 0x94000000:  # BL
                    continue
                off = w & 0x03FFFFFF
                if off & 0x02000000:
                    off -= 0x04000000
                if base + i + off * 4 != tva:
                    continue
                out.append(base + i)
        print(f"[callers {target}] 调用点 {len(out)} 处")
        for va in out:
            print(f"  --- {self.closest_sym(va)}+{va - self.sym[self.closest_sym(va)]:#x} @ {va:#x}")
            st = va - back * 4
            for ins in self.md.disasm(self.read(st, back * 4), st):
                print(f"      {ins.address:#012x}  {ins.mnemonic:9s} {ins.op_str}")
        return out

    def selfrefs(self, name):
        """在某个全局对象里找"指向自身"的 list_head —— 静态初始化的 LIST_HEAD_INIT
        就是 self->next = self->prev = &self[fld], 于是能直接读出字段偏移。"""
        va = self.sym.get(name)
        if va is None:
            sys.exit(f"缺少符号 {name}")
        blk = self.read(va, 0x1000)
        hits = []
        for o in range(0, len(blk) - 16, 8):
            nxt, prv = struct.unpack_from("<QQ", blk, o)
            if nxt == prv == va + o:
                hits.append(o)
        print(f"[selfrefs {name}] 自指 list_head 偏移: {[hex(h) for h in hits]}")
        return hits

    def dis(self, name, n=None):
        va = self.sym.get(name)
        if va is None:
            print(f"### {name}: <无此符号>")
            return
        s = self.sec_for(va)
        nxt = min([v for v in self.sym.values() if v > va and self.sec_for(v) is s]
                  or [va + 400])
        cnt = (nxt - va) // 4
        if n:
            cnt = min(cnt, n)
        print(f"### {name} @ {va:#x}  ({cnt} insns, stext+{va - self.stext:#x})")
        for i in self.md.disasm(self.read(va, cnt * 4), va):
            note = ""
            if i.mnemonic in ("bl", "b") and i.op_str.startswith("#"):
                t = int(i.op_str[1:], 0)
                note = f"   ; -> {self.closest_sym(t)}+{t - self.sym[self.closest_sym(t)]:#x}" \
                    if self.closest_sym(t) in self.sym else ""
            print(f"  {i.address:#012x}  {i.mnemonic:9s} {i.op_str}{note}")

    # ---- 立即数扫描: 谁在访问 [reg, #imm] ----
    def find_imm(self, imm, window=None):
        """扫 .text, 找所有 (LDR/STR, 无符号偏移) 命中该偏移的指令。"""
        hits = []
        for s in self.elf.iter_sections():
            if s.name != ".kernel" or s["sh_type"] != "SHT_PROGBITS":
                continue
            d = s.data()
            base = s["sh_addr"]
            for i in range(0, len(d) - 4, 4):
                w = struct.unpack_from("<I", d, i)[0]
                m = w & 0xFFC00000
                if m == 0xF9400000:      # LDR 64-bit unsigned offset
                    scale, shift = 8, 10
                elif m == 0xB9400000:    # LDR 32-bit
                    scale, shift = 4, 10
                elif m == 0xF9000000:    # STR 64-bit
                    scale, shift = 8, 10
                elif m == 0xB9000000:    # STR 32-bit
                    scale, shift = 4, 10
                else:
                    continue
                if ((w >> shift) & 0xFFF) * scale != imm:
                    continue
                va = base + i
                hits.append((va, self.closest_sym(va), w))
        if window:
            hits = [h for h in hits if window[0] <= h[0] < window[1]]
        print(f"[find-imm {imm:#x}] 命中 {len(hits)} 条")
        for va, name, w in hits[:60]:
            print(f"  {va:#012x}  {name}+{va - self.sym.get(name, va):#x}  ({w:#010x})")
        return hits


# --------------------------------------------------------------------------
# 内置推导清单: 每个值都要求能被"读某个已知内核函数"直接证明
# --------------------------------------------------------------------------
def derive_offsets(k):
    st = k.stext
    rep = []

    def need(sym):
        v = k.sym.get(sym)
        if v is None:
            sys.exit(f"缺少符号 {sym}")
        return v

    def ldr_imm(name, n, rd=None, rn=None, width=64):
        """在函数 name 的前 n 条指令里找一条加载 [rn,#off], 返回 off。"""
        va = need(name)
        for i in k.md.disasm(k.read(va, n * 4), va):
            if i.mnemonic != "ldr":
                continue
            m = re.fullmatch(r"([xw]\d+), \[([xw]\d+)(?:, #(0x[0-9a-f]+|\d+))?\]", i.op_str)
            if not m:
                continue
            if rd and m.group(1) != rd:
                continue
            if rn and m.group(2) != rn:
                continue
            return int(m.group(3), 0) if m.group(3) else 0
        return None

    # --- 符号偏移 ---
    vals = {}
    for sym in ("_stext", "prepare_kernel_cred", "commit_creds", "__arm64_sys_vhangup",
                "selinux_state", "swapper_pg_dir", "init_task", "init_cred",
                "cap_bprm_creds_from_file", "sys_call_table"):
        v = need(sym)
        vals[sym] = v
        rep.append((sym, f"{v:#018x}", f"stext+{v - st:#x}"))

    vals["KERNEL_PHYS_BASE"] = 0xA8000000
    vals["FW_STEXT_VA"] = st

    # --- 结构体偏移: 每个都由"某函数里的访存立即数"作证 ---
    ev = []
    # task_struct.cred / real_cred: commit_creds 开头读 current->{real_cred,cred}
    va = need("commit_creds")
    ins = list(k.md.disasm(k.read(va, 32 * 4), va))
    cr = [i for i in ins if i.mnemonic == "ldr" and ", [x20, #" in i.op_str][:2]
    ev.append(("task_struct.real_cred", int(cr[0].op_str.split("#")[1].rstrip("]"), 16), "commit_creds"))
    ev.append(("task_struct.cred", int(cr[1].op_str.split("#")[1].rstrip("]"), 16), "commit_creds"))
    # cred.uid / euid
    for f, s in (("cred.uid", "__arm64_sys_getuid"), ("cred.euid", "__arm64_sys_geteuid")):
        ev.append((f, ldr_imm(s, 12, "w8", "x8"), s))
    # cred.fsuid / fsgid / securebits
    ev.append(("cred.fsuid", ldr_imm("__sys_setfsuid", 24, "w25", "x19"), "__sys_setfsuid"))
    ev.append(("cred.fsgid", ldr_imm("__sys_setfsgid", 24, "w25", "x19"), "__sys_setfsgid"))
    ev.append(("cred.security", ldr_imm("selinux_cred_getsecid", 8, "x9", "x0"), "selinux_cred_getsecid"))
    # cap_effective: cap_capable 里 cred->cap_effective 的位测试
    va = need("cap_capable")
    ce = None
    for i in k.md.disasm(k.read(va, 30 * 4), va):
        if i.mnemonic == "ldr" and i.op_str.startswith("w8, [x8, #"):
            ce = int(i.op_str.split("#")[1].rstrip("]"), 16)
            break
    ev.append(("cred.cap_effective", ce, "cap_capable"))
    # 其余 cap: cap_bprm_creds_from_file 里从 cred(x21) 读 cap_inheritable/permitted/ambient
    va = need("cap_bprm_creds_from_file")
    caps = set()
    for i in k.md.disasm(k.read(va, 80 * 4), va):
        for m in re.finditer(r"\[(x\d+), #(0x[0-9a-fA-F]+|\d+)\]", i.op_str):
            if m.group(1) == "x21" and i.mnemonic in ("ldr", "ldp"):
                caps.add(int(m.group(2), 0))
    caps = [o for o in sorted(caps) if 0x28 <= o <= 0x48]
    for f, o in zip(("cred.cap_inheritable", "cred.cap_permitted", "cred.cap_bset", "cred.cap_ambient"),
                    caps):
        ev.append((f, o, "cap_bprm_creds_from_file"))
    # cred.securebits: 同一个函数里 `ldrb w?, [cred, #0x24]`
    sb = None
    for i in k.md.disasm(k.read(va, 80 * 4), va):
        m = re.search(r"\[(x\d+), #(0x[0-9a-fA-F]+|\d+)\]", i.op_str)
        if i.mnemonic == "ldrb" and m and m.group(1) == "x9":
            sb = int(m.group(2), 0)
            break
    ev.append(("cred.securebits", sb, "cap_bprm_creds_from_file"))
    # task_struct.mm: get_task_mm
    va = need("get_task_mm")
    mm = None
    for i in k.md.disasm(k.read(va, 30 * 4), va):
        if i.mnemonic == "ldr" and i.op_str.startswith("x20, [x21, #"):
            mm = int(i.op_str.split("#")[1].rstrip("]"), 0)
            break
    ev.append(("task_struct.mm", mm, "get_task_mm"))
    # mm_struct.pgd: pgd_free(mm, mm->pgd) 的调用点里 x1 的装载偏移
    pgd = None
    for s in k.elf.iter_sections():
        if s.name != ".kernel":
            continue
        d, base = s.data(), s["sh_addr"]
        tva = k.sym["pgd_free"]
        for i in range(0, len(d) - 4, 4):
            w = struct.unpack_from("<I", d, i)[0]
            if w & 0xFC000000 != 0x94000000:
                continue
            off = w & 0x03FFFFFF
            if off & 0x02000000:
                off -= 0x04000000
            if base + i + off * 4 != tva:
                continue
            for back in k.md.disasm(k.read(base + i - 24, 24), base + i - 24):
                m = re.search(r"x1, \[(x\d+), #(0x[0-9a-fA-F]+|\d+)\]", back.op_str)
                if back.mnemonic == "ldr" and m:
                    pgd = int(m.group(2), 0)
            if pgd:
                break
    ev.append(("mm_struct.pgd", pgd, "pgd_free 调用点"))
    # mm_struct.start_code / end_code: do_task_stat 里成对出现
    for sym in ("do_task_stat", "show_map_vma", "smaps_pte_range"):
        if sym not in k.sym:
            continue
        va = k.sym[sym]
        found = {}
        for i in k.md.disasm(k.read(va, 900 * 4), va):
            for o in (0x110, 0x118):
                if i.mnemonic == "ldr" and f", #{hex(o)}]" in i.op_str and o not in found:
                    found[o] = i.op_str.split(",")[0].strip()
        if 0x110 in found and 0x118 in found:
            ev.append(("mm_struct.start_code", 0x110, f"{sym} (成对)"))
            ev.append(("mm_struct.end_code", 0x118, f"{sym} (成对)"))
            break
    # FW_STEXT_WORDS: _stext 头 6 个字
    words = struct.unpack("<6I", k.read(st, 24))
    vals["FW_STEXT_WORDS"] = list(words)

    print("=" * 74)
    print(f"{'项':34s} {'值':>22s}   证据")
    print("-" * 74)
    for n, v, src in rep:
        print(f"{n:34s} {v:>22s}   {src}")
    for n, v, src in ev:
        vs = "<未推出>" if v is None else f"{v:#x}"
        print(f"{n:34s} {vs:>22s}   {src}")
    print(f"{'FW_STEXT_WORDS':34s} {' '.join(f'{w:#010x}' for w in words)}   _stext 头 6 字")
    print("=" * 74)
    return vals, ev


if __name__ == "__main__":
    if len(sys.argv) < 3:
        sys.exit(__doc__)
    img, cmd, rest = sys.argv[1], sys.argv[2], sys.argv[3:]
    k = K(img)
    if cmd == "sym":
        print(f"_stext @ {k.stext:#x}")
        for n in rest:
            v = k.sym.get(n)
            print(f"  {n:34s} {v:#018x}  stext+{v - k.stext:#x}" if v
                  else f"  {n:34s} <无此符号>")
    elif cmd == "dis":
        for a in rest:
            nm, _, n = a.partition(":")
            k.dis(nm, int(n) if n else None)
    elif cmd == "find-imm":
        k.find_imm(int(rest[0], 0))
    elif cmd == "callers":
        k.callers(rest[0], int(rest[1]) if len(rest) > 1 else 6)
    elif cmd == "selfrefs":
        for n in rest:
            k.selfrefs(n)
    elif cmd == "offsets":
        derive_offsets(k)
    else:
        sys.exit(f"未知子命令 {cmd}")
