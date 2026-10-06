#!/bin/bash
cd /root/kernel/source || exit 1
echo "== current LTO/CFI config =="
grep -E '^CONFIG_(LTO|CFI|LD_DEAD|TRIM)' .config
echo "== enable LTO_CLANG_FULL + CFI =="
./scripts/config --enable LTO_CLANG_FULL
./scripts/config --disable LTO_NONE
./scripts/config --enable CFI_CLANG
./scripts/config --enable CFI_PERMISSIVE 2>/dev/null || true
echo "== after =="
grep -E '^CONFIG_(LTO|CFI)' .config
