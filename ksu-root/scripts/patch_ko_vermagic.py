#!/usr/bin/env python3
# 等长改写 .ko 里的 vermagic —— 让一份模块能被**另一版内核**的装载器接受。
#
# 什么时候用：
#   某个系统版本拿不到对应内核源码（无法重编），但已实测该内核能加载按另一版
#   编出的模块时，把 vermagic 对齐过去。本项目就是这种情况：
#   PD2338_A_14.0.17.2.W10.V000L1 的内核是 5.15.137（vivo 自研，无公开源码树），
#   kernelsu-vivo.ko 只能取自 5.15.178 那份真重编产物。
#
# 为什么可以只改这个串：
#   vermagic 位于 ELF 的 `.modinfo` 段，是纯数据。内核对它做的是**整串比较**
#   （`check_modinfo()`；ko 的 `__versions` 段为空 ⇒ `has_crcs=false` ⇒ 不做逐符号 CRC）。
#   改它不动任何代码、不动段布局。
#
# 硬约束（脚本会强制）：
#   新串长度必须**等于**旧串长度 —— 否则 `.modinfo` 段大小变化，所有段偏移都要重算。
#   本项目不做这件事（宁可拒绝，也不产出一个需要重建段的 ko）。
#
# 另一条路（不需要改文件）：
#   `ksud insmod` 内部是 `ksuinit::load_module()`，它会自己从内核的 kmsg 报错里
#   抠出目标 vermagic、在**内存里**替换后重试 —— 所以走 App / ksud 时这份对齐
#   只是"让直接 insmod 也能用"的加固，不是必需品。判据见
#   neo9-root/docs/LKM_ON_OTHER_KERNEL.md。
#
# 用法：
#   python3 patch_ko_vermagic.py <in.ko> --print                    # 只看当前 vermagic
#   python3 patch_ko_vermagic.py <in.ko> --release 5.15.137-xxxx --out out.ko
#   python3 patch_ko_vermagic.py <in.ko> --to "<完整串>" --out out.ko
#   python3 patch_ko_vermagic.py <in.ko> --release 5.15.137-xxxx --dry-run
#
# 选项：
#   --release R     只给 release 段（如 `5.15.137-gc870e76526d2-dirty`），
#                   `SMP preempt mod_unload modversions vivo aarch64` 这截尾巴
#                   从**原串**继承（最不容易写错）。
#   --to S          直接给完整新串（必须与旧串等长）。
#   --from S        断言原串就是这个（防止对错的输入动手）。
#   --expect-md5 M  断言输入文件 md5（防止对着改过的文件动手）。
#   --out F         输出文件；不给则必须配 --print / --dry-run。
#   --dry-run       只打印将要写什么。
#
# 退出码：0 成功 / 1 有硬约束不满足 / 2 用法错误
import argparse
import hashlib
import os
import sys

TAG = b"vermagic="


def read_file(path):
    with open(path, "rb") as f:
        return bytearray(f.read())


def find_vermagic(d):
    """返回 [(**串**的文件偏移, 串)]（不是 `vermagic=` 标签的偏移）。

    `\\0` 分隔保证不会命中别处的巧合文本。
    """
    hits = []
    i = 0
    while True:
        i = d.find(TAG, i)
        if i < 0:
            return hits
        if i == 0 or d[i - 1] == 0:
            j = d.find(b"\x00", i)
            if j < 0:
                return hits
            start = i + len(TAG)          # ← 串起点，不是标签起点
            hits.append((start, bytes(d[start:j])))
        i += 1


def md5(b):
    return hashlib.md5(bytes(b)).hexdigest()


def main():
    ap = argparse.ArgumentParser(add_help=True, description="等长改写 .ko 的 vermagic")
    ap.add_argument("ko")
    ap.add_argument("--release")
    ap.add_argument("--to")
    ap.add_argument("--from", dest="from_")
    ap.add_argument("--expect-md5")
    ap.add_argument("--out")
    ap.add_argument("--print", dest="show", action="store_true")
    ap.add_argument("--dry-run", action="store_true")
    a = ap.parse_args()

    if not os.path.isfile(a.ko):
        sys.exit("找不到文件: %s" % a.ko)
    d = read_file(a.ko)
    in_md5 = md5(d)

    if a.expect_md5 and in_md5 != a.expect_md5.lower():
        sys.exit("输入 md5 不符:\n  实际 %s\n  期望 %s" % (in_md5, a.expect_md5))

    hits = find_vermagic(d)
    if len(hits) != 1:
        sys.exit("vermagic 命中 %d 处（要求恰好 1 处）—— 不是常规内核模块？" % len(hits))
    off, old = hits[0]
    old_s = old.decode()

    print("文件      : %s" % a.ko)
    print("md5       : %s" % in_md5)
    print("vermagic  : %s" % old_s)
    print("  长度 %d 字节, 文件偏移 %#x" % (len(old), off))

    if a.show:
        return 0
    if not (a.to or a.release):
        sys.exit("需要 --to 或 --release（或用 --print 只看）")

    if a.from_ and a.from_ != old_s:
        sys.exit("原串与 --from 不符:\n  文件 %s\n  期望 %s" % (old_s, a.from_))

    if a.to:
        new = a.to.encode()
    else:
        # release 之后的整截尾巴（含前导空格）从原串继承
        sp = old.find(b" ")
        if sp < 0:
            sys.exit("原 vermagic 没有 release 之后的尾巴，无法继承 —— 请用 --to 给完整串")
        tail = old[sp:]
        new = a.release.encode() + tail
        print("尾巴继承  : %r" % tail.decode())

    print("新串      : %s" % new.decode())
    if len(new) != len(old):
        sys.exit(
            "长度不等: 原 %d / 新 %d —— 这会改变 .modinfo 段布局, 本工具拒绝执行\n"
            "（改段长度需要重建 ELF 段表, 不在本工具职责内）" % (len(old), len(new))
        )

    out = bytearray(d)
    out[off:off + len(old)] = new

    # 写后回读自检：串必须**原样**落在 off 处，且标签没被动过。
    # （曾经因为把偏移算成 `vermagic=` 的起点而静默写错位置 —— 这道自检就是防它。）
    check = find_vermagic(out)
    if len(check) != 1 or check[0][0] != off or check[0][1] != new:
        sys.exit("内部自检失败：写回后 vermagic 不在预期位置/内容 —— 已终止, 未落盘")
    if bytes(out[off - len(TAG):off]) != TAG:
        sys.exit("内部自检失败：`vermagic=` 标签被破坏 —— 已终止, 未落盘")

    out_md5 = md5(out)

    print("---")
    print("写入      : %s..%s (%d 字节)" % (hex(off), hex(off + len(old)), len(new)))
    if not a.out:
        print("md5 (新)  : %s" % out_md5)
        if not a.dry_run:
            print("\n没给 --out：未写盘（加 --out <文件> 才落盘）")
        return 0
    if a.dry_run:
        print("md5 (新)  : %s" % out_md5)
        print("\n--dry-run：未写盘")
        return 0

    with open(a.out, "wb") as f:
        f.write(out)
    print("已写出    : %s" % a.out)
    print("md5 (新)  : %s" % out_md5)
    print("大小      : %d 字节 (未变)" % len(out))
    return 0


if __name__ == "__main__":
    sys.exit(main())
