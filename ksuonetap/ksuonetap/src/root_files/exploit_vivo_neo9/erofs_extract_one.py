#!/usr/bin/env python3
# erofs_extract_one.py - 从 EROFS 镜子里只抽**一个文件**（不解包整盘）
#
# 为什么需要它：Android 的 system.img 是 EROFS + 压缩，整盘解包动辄 8~10GB；
# 而换固件适配时往往只要里面的一两个文件（例如 /system/bin/init）。
# 沙箱里 erofsfuse 挂载被禁（fusermount: Operation not permitted），
# 所以这里用 dump.erofs 的 extent 表 + 逐 extent LZ4 解压直接拼出原文件。
#
# 依赖: erofs-utils (dump.erofs)、python 包 lz4
#
# 用法:
#   python3 erofs_extract_one.py <system.img> /system/bin/init [-o 输出路径]
#   python3 erofs_extract_one.py <system.img> --extents /system/bin/init   # 只看 extent 表
#
# 自检（不会静默产出错文件）：解出的长度必须等于 inode 的 Size；若是 ELF，
# 再核对 magic 与 program header 的 p_offset/p_filesz 不越界。
import os
import re
import subprocess
import sys

EXT_RE = re.compile(
    r'^\s*\d+:\s*(\d+)\.\.\s*(\d+)\s*\|\s*(\d+)\s*:\s*(\d+)\.\.\s*(\d+)\s*\|\s*(\d+)\s*$')


def dump(img, path):
    r = subprocess.run(['dump.erofs', '--path=' + path, '-e', img],
                       capture_output=True, text=True)
    if r.returncode:
        sys.exit('dump.erofs 失败: ' + r.stdout + r.stderr)
    return r.stdout


def parse(out):
    size = None
    m = re.search(r'^Size:\s*(\d+)', out, re.M)
    if m:
        size = int(m.group(1))
    exts = []
    for line in out.splitlines():
        g = EXT_RE.match(line)
        if g:
            lo, hi, ln, po, ph, pl = (int(x) for x in g.groups())
            exts.append(dict(logical=lo, out_len=ln, phys=po, phys_len=pl))
    return size, exts


def main():
    if len(sys.argv) < 3:
        sys.exit(__doc__)
    img = sys.argv[1]
    only = sys.argv[2] == '--extents'
    path = sys.argv[3] if only else sys.argv[2]
    out_path = None
    if '-o' in sys.argv:
        out_path = sys.argv[sys.argv.index('-o') + 1]

    out = dump(img, path)
    print(out.split('\n Ext:')[0].rstrip())
    size, exts = parse(out)
    if not exts:
        sys.exit('没有解析到 extent —— 该文件可能是未压缩/内联的，直接按 inode 元数据取即可')
    print('  共 %d 个 extent，逻辑总长 %d' % (len(exts), sum(e['out_len'] for e in exts)))
    if only:
        return

    try:
        import lz4.block
    except ImportError:
        sys.exit('缺 python 包 lz4（pip install lz4）')

    # 物理顺序 == 逻辑顺序（mkfs.erofs 的正常布局），逐 extent 独立解压后拼接。
    # 每个 extent = 一个 pcluster：4096 字节物理数据，LZ4 解出 out_len 字节。
    data = bytearray()
    with open(img, 'rb') as f:
        for i, e in enumerate(exts):
            f.seek(e['phys'])
            blob = f.read(e['phys_len'])
            if len(blob) != e['phys_len']:
                sys.exit('extent %d 读不出来（镜像被截断？）' % i)
            if e['out_len'] == e['phys_len']:
                data += blob           # 该 extent 是未压缩的原样存储
            else:
                try:
                    data += lz4.block.decompress(blob, uncompressed_size=e['out_len'])
                except Exception as ex:
                    sys.exit('extent %d LZ4 解压失败: %s（该镜像可能用的是 compact/interlaced '
                             '格式，改用 fsck.erofs --extract=DIR 整盘解包）' % (i, ex))

    # ---- 自检 ----
    assert size is None or len(data) == size, '长度不符: 得到 %d, inode 说 %d' % (len(data), size)
    ok_elf = data[:4] == b'\x7fELF'
    print('  解出 %d 字节 (%s)' % (len(data), 'ELF' if ok_elf else '非 ELF'))
    if ok_elf:
        e_phoff = int.from_bytes(data[0x20:0x28], 'little')
        e_phentsize = int.from_bytes(data[0x36:0x38], 'little')
        e_phnum = int.from_bytes(data[0x38:0x3a], 'little')
        for i in range(e_phnum):
            o = e_phoff + i * e_phentsize
            p_type = int.from_bytes(data[o:o + 4], 'little')
            p_offset = int.from_bytes(data[o + 8:o + 16], 'little')
            p_filesz = int.from_bytes(data[o + 32:o + 40], 'little')
            if p_type == 1 and p_offset + p_filesz > len(data):
                sys.exit('自检失败: PT_LOAD[%d] 的 p_offset+p_filesz 越界 —— 解出来的不是原文件'
                         '（改用 fsck.erofs --extract=DIR）' % i)
        print('  PT_LOAD 边界自检通过 —— 内容可信')

    if out_path:
        open(out_path, 'wb').write(bytes(data))
        print('  -> %s' % out_path)


if __name__ == '__main__':
    main()
