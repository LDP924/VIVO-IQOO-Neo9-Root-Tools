#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""onetap_deploy_now.py - 启动 app + 点一键提权 + 观察 (部署版)"""
import os, shutil, subprocess, time, re, sys

ADB = os.environ.get("ADB") or shutil.which("adb") or "/usr/bin/adb"  # 可用环境变量 ADB=<路径> 覆盖
PKG = "com.neoroot.ksuonetap"

def adb(*args, timeout=120):
    r = subprocess.run([ADB] + list(args), capture_output=True, timeout=timeout)
    return r.stdout.decode("utf-8", errors="replace"), r.stderr.decode("utf-8", errors="replace"), r.returncode

# 等 shizuku server 稳定
time.sleep(3)
out, _, _ = adb("shell", "ps -A | grep -c shizuku_server")
print("shizuku_server:", out.strip())

adb("shell", "am force-stop " + PKG)
adb("logcat", "-c")
adb("shell", "am start -n " + PKG + "/.ui.PagerActivity")
time.sleep(6)

adb("shell", "uiautomator dump /sdcard/dep2.xml")
out, _, _ = adb("shell", "cat /sdcard/dep2.xml")
for t, x1, y1, x2, y2 in re.findall(r'text="([^"]*)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', out):
    if "提权" in t:
        cx, cy = (int(x1)+int(x2))//2, (int(y1)+int(y2))//2
        print(f"点击: {t[:30]} @ ({cx},{cy})", flush=True)
        adb("shell", f"input tap {cx} {cy}")
        break

minutes = int(sys.argv[1]) if len(sys.argv) > 1 else 10
end = time.time() + minutes * 60
last = ""
while time.time() < end:
    time.sleep(8)
    out, _, _ = adb("logcat", "-d", "-v", "threadtime")
    for line in out.splitlines():
        if "KSUONETAP" in line:
            ts = line[:18]
            if ts > last:
                last = ts
                print(line, flush=True)
    if "ROOT OK" in out or "TIMEOUT" in out or "EXCEPTION" in out:
        print("=== 流程结束 ===", flush=True)
        break
print("=== watch done ===")
