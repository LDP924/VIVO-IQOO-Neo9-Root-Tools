#!/bin/bash
# 手动集成 ReSukiSU 到内核树 (等效 setup.sh, 免 git clone)
set -e
cd /root/kernel/source

# 1. 符号链接 resukisu/kernel -> drivers/kernelsu
ln -sf /root/kernel/resukisu/kernel drivers/kernelsu
echo "[+] symlink: drivers/kernelsu"

# 2. drivers/Makefile
if ! grep -q "kernelsu" drivers/Makefile; then
    printf '\nobj-$(CONFIG_KSU) += kernelsu/\n' >> drivers/Makefile
    echo "[+] Makefile modified"
fi

# 3. drivers/Kconfig
if ! grep -q "drivers/kernelsu/Kconfig" drivers/Kconfig; then
    sed -i '/endmenu/i\source "drivers/kernelsu/Kconfig"' drivers/Kconfig
    echo "[+] Kconfig modified"
fi

echo "[+] Integrated:"
ls -la drivers/kernelsu | head -2
tail -3 drivers/Makefile
