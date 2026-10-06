import re
VRT_ROOT = os.environ.get("VRT_ROOT", "/mnt/d/payload-dumper-go")  # 原分析机为 D:\payload-dumper-go

data = open(os.path.join(VRT_ROOT, "resukisu_libksud.so"), 'rb').read()
# 找 URL 相关
pats = [rb'https?://[^\x00 ]+', rb'[a-z0-9.-]+\.(?:com|org|net|io|dev|app)[/a-z0-9._-]*', rb'download[^\x00 ]{0,80}', rb'cdn[^\x00 ]{0,80}', rb'github[^\x00 ]{0,100}']
seen = set()
for p in pats:
    for m in re.finditer(p, data, re.IGNORECASE):
        t = m.group().decode('latin1')
        if t not in seen and len(t) > 8:
            seen.add(t)
            print(repr(t[:160]))
