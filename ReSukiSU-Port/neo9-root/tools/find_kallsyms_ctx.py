import re
VRT_ROOT = os.environ.get("VRT_ROOT", "/mnt/d/payload-dumper-go")  # 原分析机为 D:\payload-dumper-go

data = open(os.path.join(VRT_ROOT, "resukisu_libksud.so"), 'rb').read()
for kw in [b'Cannot parse', b'kallsyms', b'parse kallsyms', b'symbol table', b'Failed to read symbol']:
    for m in re.finditer(re.escape(kw), data):
        s = max(0, m.start() - 120)
        e = min(len(data), m.end() + 180)
        chunk = data[s:e]
        strs = re.findall(rb'[ -~]{4,}', chunk)
        print('---', kw.decode(), '@', hex(m.start()), ':')
        for t in strs:
            print('   ', repr(t.decode('latin1')[:130]))
