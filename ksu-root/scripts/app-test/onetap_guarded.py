#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""onetap_guarded.py - 安全部署: 先检查 shizuku -> 未激活则激活 -> 再部署"""
import os, shutil, subprocess, time, re, sys

ADB = os.environ.get("ADB") or shutil.which("adb") or "/usr/bin/adb"  # 可用环境变量 ADB=<路径> 覆盖
# shizuku 的 libshizuku.so 路径含安装哈希, 重装 shizuku 就会变 -> 不写死, 用 pm path 现推
# (base.apk 同目录下就是 lib/arm64/libshizuku.so)
SHIZUKU_PKG = "moe.shizuku.privileged.api"

def resolve_shizuku_so():
    out, _, _ = adb("shell", "pm path " + SHIZUKU_PKG)
    for line in (out or "").splitlines():
        if line.startswith("package:"):
            apk = line.split(":", 1)[1].strip()
            return os.path.join(os.path.dirname(apk), "lib", "arm64", "libshizuku.so")
    return ""
PKG = "com.neoroot.ksuonetap"

def adb(*args, timeout=120):
    r = subprocess.run([ADB] + list(args), capture_output=True, timeout=timeout)
    return r.stdout.decode("utf-8", errors="replace"), r.stderr.decode("utf-8", errors="replace"), r.returncode

# 1. 检查 shizuku server
out, _, _ = adb("shell", "ps -A -o PID,NAME | grep shizuku_server")
if "shizuku_server" in out:
    print("shizuku 已在运行:", out.strip())
else:
    print("shizuku 未运行 -> 重新激活")
    out, _, _ = adb("shell", resolve_shizuku_so(), timeout=60)
    print(out.strip())
    time.sleep(3)
    out, _, _ = adb("shell", "ps -A -o PID,NAME | grep shizuku_server")
    print("激活后:", out.strip() or "!! 激活失败")

# 2. 检查设备/uptime (确认不是刚重启)
out, _, _ = adb("shell", "cat /proc/uptime; getprop sys.boot_completed")
print("uptime/boot:", out.strip())

# 3. 启动 app + 点一键提权
adb("shell", "am force-stop " + PKG)
adb("logcat", "-c")
adb("shell", "am start -n " + PKG + "/.ui.PagerActivity")
time.sleep(6)

adb("shell", "uiautomator dump /sdcard/gd.xml")
out, _, _ = adb("shell", "cat /sdcard/gd.xml")
for t, x1, y1, x2, y2 in re.findall(r'text="([^"]*)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', out):
    if "提权" in t:
        cx, cy = (int(x1)+int(x2))//2, (int(y1)+int(y2))//2
        print(f"点击: {t[:30]} @ ({cx},{cy})", flush=True)
        adb("shell", f"input tap {cx} {cy}")
        break

# 4. 观察
minutes = int(sys.argv[1]) if len(sys.argv) > 1 else 10
end = time.time() + minutes * 60
last = ""
while time.time() < end:
    time.sleep(8)
    try:
        out, _, _ = adb("logcat", "-d", "-v", "threadtime")
    except Exception as e:
        print("logcat err:", e)
        time.sleep(5)
        continue
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
