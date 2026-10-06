#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""final_state.py - 确认最终状态: KSU/unpatch/root/netd/zygote"""
import os, shutil, subprocess

ADB = os.environ.get("ADB") or shutil.which("adb") or "/usr/bin/adb"  # 可用环境变量 ADB=<路径> 覆盖
DEV = "/data/local/tmp"

def adb(*args, timeout=60):
    r = subprocess.run([ADB] + list(args), capture_output=True, timeout=timeout)
    return r.stdout.decode("utf-8", errors="replace"), r.stderr.decode("utf-8", errors="replace"), r.returncode

print("=== uptime / boot ===")
out, _, _ = adb("shell", "cat /proc/uptime; getprop sys.boot_completed")
print(out.strip())

print("=== modules ===")
out, _, _ = adb("shell", "cat /proc/modules | grep -E 'kernelsu|unpatch'")
print(out.strip() or "(none)")

print("=== KSU root ===")
out, _, _ = adb("shell", DEV + "/su_ksu -c id 2>&1")
print(out.strip())

print("=== zygote/netd 状态 ===")
out, _, _ = adb("shell", "ps -A -o PID,NAME | grep -E 'zygote|netd|system_server'")
print(out.strip() or "(none)")

print("=== netd 崩溃计数 (最近 100 条 crash log) ===")
out, _, _ = adb("logcat", "-d", "-b", "crash", "-t", "100")
netd_n = sum(1 for l in out.splitlines() if "netd" in l and "SIGABRT" in l)
print(f"netd SIGABRT 次数: {netd_n}")
print("最近一条:", [l for l in out.splitlines() if "netd" in l][-1] if any("netd" in l for l in out.splitlines()) else "(无)")

print("=== 软重启时间点 (uptime 连续验证) ===")
out, _, _ = adb("shell", "cat /proc/uptime")
print(out.strip())
