#!/bin/bash
# 在 vermagic 宏中附加 "vivo " (与设备 vermagic 一致)
P=/root/kernel/source/include/linux/vermagic.h
python3 - <<'EOF'
p = '/root/kernel/source/include/linux/vermagic.h'
s = open(p).read()
old = 'MODULE_VERMAGIC_MODULE_UNLOAD MODULE_VERMAGIC_MODVERSIONS\t\\\n\tMODULE_ARCH_VERMAGIC'
new = 'MODULE_VERMAGIC_MODULE_UNLOAD MODULE_VERMAGIC_MODVERSIONS "vivo "\t\\\n\tMODULE_ARCH_VERMAGIC'
assert old in s, 'pattern not found'
s = s.replace(old, new)
open(p, 'w').write(s)
print('vermagic.h patched')
EOF
grep -n 'vivo' "$P"
