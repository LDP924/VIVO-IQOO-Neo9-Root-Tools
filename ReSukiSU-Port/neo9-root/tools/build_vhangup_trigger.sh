#!/bin/bash
# build_vhangup_trigger.sh - NDK 编译 vhangup_trigger (验证内核 vhangup stub 是否生效)
#
# 用法: ./build_vhangup_trigger.sh
# 覆盖: NDK=<ndk-root> ./build_vhangup_trigger.sh   # 默认 ~/android-ndk-cache/android-ndk-r29
#
# 产物就落在本目录（原先写死输出到 /mnt/d/payload-dumper-go/ksu-apk，换机器必失败）。
set -eu

NDK="${NDK:-$HOME/android-ndk-cache/android-ndk-r29}"
CC="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android21-clang"
HERE="$(cd "$(dirname "$0")" && pwd)"

[ -x "$CC" ] || { echo "找不到 clang: $CC" >&2; echo "用 NDK=<ndk-root> 指定, 或先跑 ./build.sh fetch-ndk" >&2; exit 1; }

cd "$HERE"
"$CC" -O2 -o vhangup_trigger vhangup_trigger.c
file vhangup_trigger
