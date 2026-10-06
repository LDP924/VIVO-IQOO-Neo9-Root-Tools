#!/bin/bash
# fetch-ndk.sh - 下载 Android NDK r29 并解出交叉工具链 (不动系统路径)
#
# 为什么需要它:
#   本项目的 exploit 要用 bionic 动态链接的 aarch64 二进制 (app 域 seccomp 兼容),
#   所以必须有 NDK 的 clang。有些机器系统路径只读 (apt 装不了 gcc-aarch64-linux-gnu),
#   这里就把 NDK 下到用户目录, 由 build.sh 自动探测。
#
# 用法:
#   bash scripts/fetch-ndk.sh                 # 下到 $HOME/android-ndk-cache 并解包
#   DEST=/opt/ndk bash scripts/fetch-ndk.sh   # 指定目标目录
#   NO_EXTRACT=1 bash scripts/fetch-ndk.sh    # 只下载 zip, 不解包
#   MIRROR=google bash scripts/fetch-ndk.sh   # 换官方源 (国内慢, 默认用腾讯镜像)
#   PARTS=4 bash scripts/fetch-ndk.sh         # 并发分片数 (默认 8)
#
# 说明:
#   - 官方包 747MB; 腾讯镜像 8 并发实测 ~7.7MB/s (单流 ~1.5MB/s, 官方 ~0.5MB/s)。
#   - 只解 toolchains/ 与 source.properties (不装整个 NDK, 省 ~1.5GB)。
#   - 已存在可用工具链时直接跳过 (幂等)。
set -u

NDK_VER=r29
ZIPNAME=android-ndk-${NDK_VER}-linux.zip
TOTAL=783549481                      # 官方 Content-Length, 用作分片与校验基准

DEST=${DEST:-$HOME/android-ndk-cache}
PARTS=${PARTS:-8}
NO_EXTRACT=${NO_EXTRACT:-0}

case "${MIRROR:-tencent}" in
    google)  URL="https://dl.google.com/android/repository/$ZIPNAME" ;;
    aliyun)  URL="https://mirrors.aliyun.com/android.googlesource.com/$ZIPNAME" ;;
    *)       URL="https://mirrors.cloud.tencent.com/AndroidSDK/$ZIPNAME" ;;
esac

CC="$DEST/android-ndk-$NDK_VER/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android21-clang"

echo "目标目录: $DEST"
echo "下载源  : $URL"

if [ -x "$CC" ]; then
    echo "已存在可用工具链, 跳过:"
    echo "  $CC"
    echo "NDK=$DEST/android-ndk-$NDK_VER"
    exit 0
fi

mkdir -p "$DEST/dl/parts"
ZIP="$DEST/dl/$ZIPNAME"

if [ "$(stat -c%s "$ZIP" 2>/dev/null || echo 0)" -ne "$TOTAL" ]; then
    echo "分片下载 ($PARTS 并发)..."
    /bin/rm -f "$DEST"/dl/parts/p*
    CHUNK=$(( (TOTAL + PARTS - 1) / PARTS ))
    pids=()
    for i in $(seq 0 $((PARTS - 1))); do
        s=$((i * CHUNK))
        e=$((s + CHUNK - 1))
        [ "$e" -ge "$TOTAL" ] && e=$((TOTAL - 1))
        ( curl -sS --retry 5 --retry-delay 2 -r "$s-$e" -L -o "$DEST/dl/parts/p$i" "$URL" ) &
        pids+=($!)
    done
    fail=0
    for p in "${pids[@]}"; do wait "$p" || { echo "  分片进程 $p 失败"; fail=1; }; done
    [ "$fail" -eq 0 ] || { echo "下载失败"; exit 1; }

    : > "$ZIP"
    for i in $(seq 0 $((PARTS - 1))); do
        s=$((i * CHUNK))
        e=$((s + CHUNK - 1))
        [ "$e" -ge "$TOTAL" ] && e=$((TOTAL - 1))
        want=$((e - s + 1))
        got=$(stat -c%s "$DEST/dl/parts/p$i" 2>/dev/null || echo 0)
        if [ "$got" -ne "$want" ]; then
            echo "  分片 $i 大小不符: $got != $want"; exit 1
        fi
        cat "$DEST/dl/parts/p$i" >> "$ZIP"
    done
    /bin/rm -rf "$DEST/dl/parts"
fi

sz=$(stat -c%s "$ZIP" 2>/dev/null || echo 0)
echo "包大小: $sz / $TOTAL"
[ "$sz" -eq "$TOTAL" ] || { echo "大小不符, 包可能损坏 (可删掉重试)"; exit 1; }

if [ "$NO_EXTRACT" = "1" ]; then
    echo "NO_EXTRACT=1, 只保留 zip: $ZIP"
    exit 0
fi

command -v unzip >/dev/null 2>&1 || { echo "缺 unzip, 无法解包 (可 NO_EXTRACT=1 只下载)"; exit 1; }
echo "解出 toolchains/ ..."
( cd "$DEST" && unzip -q -o "dl/$ZIPNAME" \
    "android-ndk-$NDK_VER/toolchains/*" "android-ndk-$NDK_VER/source.properties" ) || exit 1
/bin/rm -f "$ZIP"

if [ -x "$CC" ]; then
    echo "完成:"
    echo "  NDK=$DEST/android-ndk-$NDK_VER"
    "$CC" --version | head -1 | sed 's/^/  /'
    echo
    echo "下一步: ./build.sh exploit"
else
    echo "解包后仍找不到 $CC"; exit 1
fi
