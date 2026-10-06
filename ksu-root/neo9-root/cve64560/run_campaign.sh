#!/bin/bash
# run_campaign.sh —— 带单实例守卫的 campaign 启动器
#
# 背景（教训）：stage0 的进程命令行是 "stage0 probe"（argv[0] 不含路径），
# 所以 `pkill -f /data/local/tmp/stage0` 匹配不上 → 旧 campaign 不会被清掉。
# 多份 campaign 同时在设备上跑会互相抢核、各自造 2 万个定时器，
# 数据全部作废（实测观察到 active=8215 的异常基线）。因此启动前必须预检。
#
# 用法：
#   ./run_campaign.sh <attempts> [S0_XXX=... ...]
# 例：
#   ./run_campaign.sh 800 S0_KS=20000 S0_SOCKS=512 S0_MSGS=8
#
# 环境：
#   MODE=probe|stage0|detect  （默认 probe）
#   FORCE=1                   预检发现残留时先清掉再说
set -u

HERE=$(cd "$(dirname "$0")" && pwd)
BIN="$HERE/stage0"
MODE=${MODE:-probe}
ATTEMPTS=${1:-100}
shift || true
EXTRA="$*"

[ -x "$BIN" ] || { echo "!! 找不到 $BIN，请先编译"; exit 1; }
command -v adb >/dev/null || { echo "!! 没有 adb"; exit 1; }

# 注意：`sh -c '... ./stage0 probe'` 包装进程的 argv 也含 "stage0"，
# 所以要按 NAME（comm）精确匹配，否则单实例也会被误判成 2 个。
# 二进制陈旧检查：stage0.c 比 stage0 新 ⇒ 拒绝启动（防止拿旧二进制做实验）
check_stale() {
	[ -f stage0.c ] || return 0
	[ -f stage0 ] || { echo "ERROR: ./stage0 不存在，先编译"; return 1; }
	if [ stage0.c -nt stage0 ]; then
		echo "ERROR: STALE BINARY —— stage0.c 比 stage0 新，请先重新编译再跑"
		return 1
	fi
	return 0
}

count_stage0() {
	adb shell "ps -A -o PID,NAME 2>/dev/null | grep -w stage0 | grep -v grep | wc -l" | tr -d '\r'
}

echo "== 预检：设备侧 stage0 进程数 =="
N=$(count_stage0)
if [ "${N:-0}" != "0" ]; then
	echo "!! 设备上已有 $N 个 stage0 进程："
	adb shell "ps -A -o PID,PPID,NAME,ARGS 2>/dev/null | grep -w stage0 | grep -v grep"
	if [ "${FORCE:-0}" = "1" ]; then
		echo "== FORCE=1：清理残留"
		adb shell "pkill -f 'stage0 probe'; pkill -f './stage0'; sleep 1"
		N=$(count_stage0)
		[ "${N:-0}" = "0" ] || { echo "!! 清理失败（剩 $N）"; exit 2; }
	else
		echo "!! 拒绝启动（防并发污染）。如确认可清理："
		echo "   FORCE=1 $0 $ATTEMPTS $EXTRA"
		exit 2
	fi
fi
echo "   pre-flight OK"

STAMP=$(date +%Y%m%d-%H%M%S)
LOG="$HERE/campaign-$MODE-$STAMP.log"

adb push "$BIN" /data/local/tmp/ >/dev/null 2>&1 || { echo "!! push 失败"; exit 3; }
adb shell "chmod 755 /data/local/tmp/stage0"

CMD="cd /data/local/tmp && S0_ATTEMPTS=$ATTEMPTS $EXTRA ./stage0 $MODE"
echo "== 启动：$CMD"
echo "== 日志：$LOG"
adb shell "$CMD" 2>&1 | tee "$LOG"

echo "== 汇总 =="
echo "   HITS        = $(grep -c 'PROBE_HIT\|STAGE0_PASS\|DETECT_HIT' "$LOG")"
echo "   DRAIN_FAIL  = $(grep -c 'DRAIN_FAIL' "$LOG")"
echo "   progress    ="
grep '\[progress\]\|done:' "$LOG" | tail -3
