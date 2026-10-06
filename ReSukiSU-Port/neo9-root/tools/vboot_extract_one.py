#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""vboot_extract_one.py — 从 vendor_boot.img 的 ramdisk 里只取一个文件。

为什么需要它：Android 的 vendor_boot 里装的是 **first-stage 内核模块**，像 vivo 的
`vr.ko`（反 root 检测）就只存在于这里，不在 /vendor/lib/modules 也不在 system.img。
换系统版本时它是**随固件走的**（vrpatch.ko 的 VR_DETECT_OFFSET 取自它），所以要能
按版本单独取出来，而不是整盘解包。

支持的输入（都是实测过的形态）：
  vendor_boot v3 / v4（v4 的多 fragment 表也处理）
  ramdisk 压缩：LZ4 legacy 块流（magic 02 21 4c 18，最常见）/ LZ4 frame / gzip / 未压缩 cpio

用法:
  python3 vboot_extract_one.py <vendor_boot.img> lib/modules/vr.ko -o vr.ko
  python3 vboot_extract_one.py <vendor_boot.img> --list          # 列出 ramdisk 里的文件
不依赖外部工具（只用 python-lz4；没有 lz4 时 gzip 路径仍可用）。
"""
import argparse
import gzip
import io
import struct
import sys

try:
    import lz4.block as _lz4b
except Exception:                                    # pragma: no cover
    _lz4b = None

PAGE = 4096
LZ4_LEGACY_MAGIC = b"\x02\x21\x4c\x18"
LZ4_FRAME_MAGIC = b"\x04\x22\x4d\x18"
GZIP_MAGIC = b"\x1f\x8b"


def parse_header(d):
    """返回 (header_version, page_size, header_size, vendor_ramdisk_size, fragments)。"""
    if d[:8] != b"VNDRBOOT":
        sys.exit("不是 vendor_boot（magic=%r）" % d[:8])
    hv, page = struct.unpack_from("<II", d, 8)
    vram_size = struct.unpack_from("<I", d, 24)[0]
    # v3: tags_addr@0x81C, name[16]@0x820, header_size@0x860, dtb_size@0x864, dtb_addr@0x868
    header_size = struct.unpack_from("<I", d, 0x860)[0]
    frags = []
    if hv >= 4:
        # v4 追加: table_size@0x870, num@0x874, ent_size@0x878, bootconfig_size@0x87C
        tsize, tnum, tent = struct.unpack_from("<III", d, 0x870)
        if tnum and tent:
            base = (header_size or 0x880)
            base = (base + page - 1) // page * page
            for i in range(tnum):
                rs, ro, rt = struct.unpack_from("<III", d, base + i * tent)
                frags.append((rs, ro, rt))
    return hv, page, header_size, vram_size, frags


def lz4_legacy_decompress(buf):
    """LZ4 legacy 流：magic(4) 只在**开头出现一次**，之后是重复的 [comp_size(4) | data]。
    解压块固定 8MB（实测 vivo 的 vendor_boot 就是这种；第一块之后**没有**重复 magic）。"""
    if _lz4b is None:
        sys.exit("需要 python-lz4（pip install lz4）才能解 LZ4 ramdisk")
    if buf[:4] != LZ4_LEGACY_MAGIC:
        sys.exit("不是 LZ4 legacy 流")
    out = bytearray()
    off = 4
    n = len(buf)
    while off + 4 <= n:
        comp = struct.unpack_from("<I", buf, off)[0]
        if comp == 0 or off + 4 + comp > n:
            break
        blob = buf[off + 4: off + 4 + comp]
        got = None
        for size in (8 << 20, 4 << 20, 1 << 20, 1 << 16, 4096):
            try:
                got = _lz4b.decompress(blob, uncompressed_size=size)
                break
            except Exception:
                continue
        if got is None:
            break
        out += got
        off += 4 + comp
    return bytes(out)


def decompress_ramdisk(d, data_start, vram_size):
    buf = d[data_start:data_start + vram_size] if vram_size else d[data_start:]
    head = buf[:4]
    if head == LZ4_LEGACY_MAGIC:
        return lz4_legacy_decompress(buf)
    if head == LZ4_FRAME_MAGIC:
        if _lz4b is None:
            sys.exit("需要 python-lz4 才能解 LZ4 frame ramdisk")
        import lz4.frame
        return lz4.frame.decompress(buf)
    if head[:2] == GZIP_MAGIC:
        return gzip.decompress(buf)
    if buf[:6] == b"070701" or buf[:6] == b"070702":
        return buf
    sys.exit("认不出 ramdisk 的压缩格式（首 8 字节 %s）" % buf[:8].hex())


def iter_cpio(blob):
    """遍历 newc cpio，产出 (name, mode, data)。"""
    off = 0
    n = len(blob)
    while off + 110 <= n:
        if blob[off:off + 6] not in (b"070701", b"070702"):
            break
        f = lambda i: int(blob[off + 6 + i * 8: off + 14 + i * 8], 16)
        mode, filesize = f(1), f(6)
        namesize = f(11)
        name = blob[off + 110: off + 110 + namesize - 1].decode("utf-8", "replace")
        data_off = off + 110 + namesize
        data_off = (data_off + 3) // 4 * 4
        data = blob[data_off:data_off + filesize]
        next_off = (data_off + filesize + 3) // 4 * 4
        if name == "TRAILER!!!":
            break
        yield name, mode, data
        off = next_off


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("image")
    ap.add_argument("member", nargs="?", help="ramdisk 内的路径，如 lib/modules/vr.ko")
    ap.add_argument("-o", "--out", help="输出文件（不给则打印信息）")
    ap.add_argument("--list", action="store_true", help="列出 ramdisk 内所有文件")
    a = ap.parse_args()

    d = open(a.image, "rb").read()
    hv, page, header_size, vram_size, frags = parse_header(d)
    data_start = ((header_size or page) + page - 1) // page * page
    print("vendor_boot v%d, page=%d, header_size=%d, ramdisk=%d 字节 @ %#x, fragment 数=%d"
          % (hv, page, header_size, vram_size, data_start, len(frags)))
    blob = decompress_ramdisk(d, data_start, vram_size)
    print("解压后 ramdisk: %d 字节" % len(blob))

    want = None
    for name, mode, data in iter_cpio(blob):
        if a.member and (name == a.member or name == "./" + a.member):
            want = (name, mode, data)
            if not a.out:
                break
        if a.list:
            print("  %-60s %8d" % (name, len(data)))
    if a.member:
        if not want:
            sys.exit("ramdisk 里没有 %s" % a.member)
        name, mode, data = want
        print("命中 %s (%d 字节)" % (name, len(data)))
        if a.out:
            open(a.out, "wb").write(data)
            import hashlib
            print("已写出 %s  md5=%s  sha256=%s"
                  % (a.out, hashlib.md5(data).hexdigest(), hashlib.sha256(data).hexdigest()[:32]))
        else:
            print(data[:64].hex())


if __name__ == "__main__":
    main()
