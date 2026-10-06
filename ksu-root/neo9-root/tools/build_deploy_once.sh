#!/bin/bash
# build_deploy_once.sh - NDK 编译 deploy_once (触发 vhangup stub + ksud insmod 的一次性工具)
#
# 用法: ./build_deploy_once.sh
# 覆盖: NDK=<ndk-root> ./build_deploy_once.sh     # 默认 ~/android-ndk-cache/android-ndk-r29
#
# 产物就落在本目录（原先写死输出到 /mnt/d/payload-dumper-go/ksu-apk，换机器必失败）。
set -eu

NDK="${NDK:-$HOME/android-ndk-cache/android-ndk-r29}"
CC="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android21-clang"
HERE="$(cd "$(dirname "$0")" && pwd)"

[ -x "$CC" ] || { echo "找不到 clang: $CC" >&2; echo "用 NDK=<ndk-root> 指定, 或先跑 ./build.sh fetch-ndk" >&2; exit 1; }

cd "$HERE"
"$CC" -O2 -o deploy_once deploy_once.c
file deploy_once
