#!/usr/bin/env python3
# 改写 kernelsu .ko 里的 KSU_VERSION (数字码) 与 KSU_VERSION_FULL (版本名字符串)
#
# 为什么可以改:
#   kernel/core/init.c        pr_info("...driver version: %u...", KSU_VERSION, ...)
#   kernel/supercall/dispatch.c  cmd.version = KSU_VERSION; strscpy(cmd.version_full, ...)
#   -> 数字码编译成 mov wN, #<版本码> 立即数; 版本名是 .rodata 里的明文串。
#   两者都是纯数据, 改它们不动任何逻辑。
#
# 定位旧版本码的两条路:
#   1) 从 FULL 串里取 commit sha, 用 git rev-list --count 算 (需要本仓库 git 历史)
#   2) 扫描所有 mov wN,#imm 形式, 取落在 KernelsU 版本码区间的候选
#
# 等长约束: 新串长度必须等于旧串长度 (多退少补会破坏 .rodata 布局)
#
# 用法:
#   python3 patch_ko_version.py <in.ko> --to 35184 [--from 35179] [--full-name NAME]
#                               [--out out.ko] [--dry-run]
import argparse
import hashlib
import os
import re
import struct
import subprocess
import sys

MOVZ_W = 0x52800000          # movz wN, #imm16 / mov wN, #imm16
BASE = 30000 + 700           # KSU_VERSION = 30000 + 提交数 + 700
FULL_RE = re.compile(rb'[A-Za-z0-9._@/-]{6,64}@ReSukiSU')


def load(path):
    with open(path, 'rb') as f:
        return bytearray(f.read())


def find_full(d):
    # KSU_VERSION_FULL 形如 v4.2.0-rc1-c04159fc@ReSukiSU
    hits = []
    for m in FULL_RE.finditer(d):
        s = m.group(0)
        i = m.start()
        # 向前扩展: 串可能以更早位置开始 (正则只匹配到 @ 后那段)
        while i > 0 and 0x20 <= d[i - 1] < 0x7f:
            i -= 1
        j = d.find(b'\x00', m.end())
        hits.append((i, bytes(d[i:j])))
    return hits


def sha_in_full(s):
    m = re.search(rb'([0-9a-f]{8})(?:-dirty)?@', s)
    return m.group(1).decode() if m else None


def version_from_sha(sha, repo):
    try:
        out = subprocess.run(['git', '-C', repo, 'rev-list', '--count', sha],
                             capture_output=True, text=True, timeout=60)
        if out.returncode != 0:
            return None
        return BASE + int(out.stdout.strip())
    except Exception:
        return None


def git_out(repo, *args):
    try:
        out = subprocess.run(['git', '-C', repo] + list(args),
                             capture_output=True, text=True, timeout=60)
        return out.stdout.strip() if out.returncode == 0 else None
    except Exception:
        return None


def build_full_name(repo, sha):
    # Kbuild 的 KSU_FULL_NAME_FORMAT 默认 "%TAG_NAME%-%COMMIT_SHA%@%REPO_NAME%"
    tag = git_out(repo, 'describe', '--abbrev=0', '--tags', sha) or 'unknown'
    return '%s-%s@ReSukiSU' % (tag, sha)


def find_movz_imm(d, imm, limit=BASE + 30000):
    # 返回 {rd: [文件偏移, ...]} — 匹配 mov wN, #imm (imm 16 位)
    res = {}
    for rd in range(32):
        pat = struct.pack('<I', MOVZ_W | ((imm & 0xffff) << 5) | rd)
        offs, k = [], d.find(pat)
        while k >= 0:
            offs.append(k)
            k = d.find(pat, k + 1)
        if offs:
            res[rd] = offs
    return res


def guess_old_version(d):
    # 候选: 所有 mov wN,#imm 中, 取出现次数最多的、落在版本码区间的 imm
    tally = {}
    for rd in range(32):
        base_pat = MOVZ_W | (rd)
        # 逐 4 字节扫描太慢; 改用已知区间的可能值反查
        pass
    # 只在合理区间内逐个试 (BASE .. BASE+20000), 版本码是十进制整数, 数量可控
    for v in range(BASE, BASE + 20000):
        n = sum(len(o) for o in find_movz_imm(d, v).values())
        if n:
            tally[v] = tally.get(v, 0) + n
    return tally


def disasm_verify(path, imm):
    # 用 NDK 的 llvm-objdump 回读 (可选)
    ndk = os.environ.get('NDK', '/home/dengxiang/.workbuddy/binaries/android-ndk-r29')
    obj = os.path.join(ndk, 'toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-objdump')
    if not os.path.exists(obj):
        return None
    try:
        out = subprocess.run([obj, '-d', '--no-show-raw-insn', path],
                             capture_output=True, text=True, timeout=900).stdout
    except Exception:
        return None
    return sum(1 for line in out.splitlines() if ('#0x%x' % imm) in line)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('ko')
    ap.add_argument('--to', type=int, default=35184, help='目标 KSU_VERSION')
    ap.add_argument('--from', dest='frm', type=int, default=None, help='旧 KSU_VERSION (默认自动探测)')
    ap.add_argument('--full-name', default=None, help='新的 FULL 串 (默认按 repo 的 tag+HEAD 推导)')
    ap.add_argument('--sha', default=None, help='目标提交短 sha (默认取 repo HEAD)')
    ap.add_argument('--no-full', action='store_true', help='只改数字码, 不动 FULL 串')
    ap.add_argument('--repo', default=os.path.join(os.path.dirname(os.path.abspath(__file__)),
                                                   '..', 'source', 'resukisu'))
    ap.add_argument('--out', default=None)
    ap.add_argument('--dry-run', action='store_true')
    args = ap.parse_args()

    d = load(args.ko)
    orig_size = len(d)
    orig_sha = hashlib.sha256(d).hexdigest()
    print('输入     : %s (%d 字节, sha256 %s)' % (args.ko, orig_size, orig_sha[:16]))
    print('文件大小 0x%x' % orig_size)

    # ---- FULL 串 ----
    fulls = find_full(d)
    print()
    print('=== KSU_VERSION_FULL 候选 ===')
    for off, s in fulls:
        print('  0x%-8x len=%-3d %r' % (off, len(s), s.decode('latin1')))
    if len(fulls) != 1:
        print('  ! 期望恰好 1 处; 得到 %d 处' % len(fulls))
    full_off, full_old = fulls[0]
    sha = sha_in_full(full_old)
    print('  解析出的 commit sha: %s' % sha)

    # ---- 旧版本码 ----
    old_v = args.frm
    if old_v is None and sha:
        guessed = version_from_sha(sha, os.path.abspath(args.repo))
        print()
        print('=== 用 git 反推旧版本码 ===')
        if guessed:
            print('  rev-list --count %s -> KSU_VERSION = %d' % (sha, guessed))
            old_v = guessed
        else:
            print('  git 里没有 %s (或非 git 仓库) -> 退回扫描' % sha)
    if old_v is None:
        print()
        print('=== 扫描候选版本码 (慢) ===')
        tally = guess_old_version(d)
        for v, n in sorted(tally.items(), key=lambda kv: -kv[1])[:5]:
            print('  %d: %d 处' % (v, n))
        if not tally:
            sys.exit('!! 没找到候选版本码, 用 --from 显式指定')
        old_v = max(tally, key=lambda k: tally[k])
        print('  采用 %d' % old_v)

    if old_v == args.to:
        print('\n旧 == 新 (%d), 无需改动' % args.to)
        return 0

    # ---- 指令定位 ----
    movz = find_movz_imm(d, old_v)
    n_insn = sum(len(v) for v in movz.values())
    print()
    print('=== 承载版本码的指令 ===')
    print('  mov wN, #0x%x (=%d): %d 处' % (old_v & 0xffff, old_v, n_insn))
    for rd, offs in sorted(movz.items()):
        print('    w%-2d %s' % (rd, ['0x%x' % o for o in offs]))
    if n_insn == 0:
        sys.exit('!! 没定位到指令, 检查 --from')

    # ---- 新 FULL 串 ----
    if args.no_full:
        new_full = full_old
        print()
        print('=== FULL 串改写 ===  (--no-full, 保持不变)')
    else:
        if args.full_name:
            new_full = args.full_name.encode()
        else:
            tsha = args.sha or (git_out(os.path.abspath(args.repo), 'rev-parse', '--short=8', 'HEAD') or sha)
            new_full = build_full_name(os.path.abspath(args.repo), tsha).encode()
        print()
        print('=== FULL 串改写 ===')
        print('  旧 (%d): %r' % (len(full_old), full_old.decode('latin1')))
        print('  新 (%d): %r' % (len(new_full), new_full.decode('latin1')))
        if len(new_full) > len(full_old):
            sys.exit('!! 新串更长, 会覆盖后面的数据 (需 <= %d 字节)' % len(full_old))
    pad = len(full_old) - len(new_full)

    # ---- 应用 ----
    new_d = bytearray(d)
    changed = []
    for rd, offs in movz.items():
        neww = MOVZ_W | (((args.to) & 0xffff) << 5) | rd
        for off in offs:
            new_d[off:off + 4] = struct.pack('<I', neww)
            changed.append((off, 4, 'mov w%d #%d -> #%d' % (rd, old_v, args.to)))
    new_d[full_off:full_off + len(full_old) + 1] = new_full + b'\x00' * (pad + 1)
    if not args.no_full:
        changed.append((full_off, len(full_old) + 1, 'FULL 串'))

    # ---- 自检 ----
    assert len(new_d) == orig_size, '大小变了'
    diff = [i for i in range(orig_size) if new_d[i] != d[i]]
    expect = set()
    for off, ln, _ in changed:
        expect.update(range(off, off + ln))
    extra = set(diff) - expect
    print()
    print('=== 自检 ===')
    print('  文件大小      : %d -> %d  %s' % (orig_size, len(new_d),
          'OK' if len(new_d) == orig_size else '变了!'))
    print('  实际变动字节  : %d' % len(diff))
    print('  预期变动字节  : %d (3 处指令 12B + FULL 串 %dB)' % (len(expect), len(full_old) + 1))
    print('  预期外变动    : %d  %s' % (len(extra), 'OK' if not extra else '异常!'))
    print('  ELF 头未动    : %s' % ('OK' if bytes(new_d[:64]) == bytes(d[:64]) else '变了!'))
    print('  vermagic 未动 : %s' % ('OK' if new_d.count(b'vermagic=') == d.count(b'vermagic=') else '?'))
    if extra:
        sys.exit('!! 有预期外的字节变动, 中止')

    if args.dry_run:
        print('\n[dry-run] 未写文件')
        return 0

    out = args.out or (args.ko + '.patched')
    with open(out, 'wb') as f:
        f.write(new_d)
    print()
    print('输出: %s (%d 字节)' % (out, os.path.getsize(out)))
    print('  sha256 %s' % hashlib.sha256(open(out, 'rb').read()).hexdigest()[:16])

    n = disasm_verify(out, args.to)
    if n is not None:
        print('  反汇编回读 mov #0x%x (=%d): %d 处 (期望 %d)' % (args.to & 0xffff, args.to, n, n_insn))
    fs = find_full(open(out, 'rb').read())
    for off, s in fs:
        print('  回读 FULL 串: %r' % s.decode('latin1'))
    return 0


if __name__ == '__main__':
    sys.exit(main())
