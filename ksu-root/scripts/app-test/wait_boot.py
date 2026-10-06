#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""wait_boot.py - 等设备重启完成并报告状态"""
import os, shutil, subprocess, time, sys

ADB = os.environ.get("ADB") or shutil.which("adb") or "/usr/bin/adb"  # 可用环境变量 ADB=<路径> 覆盖

def adb(*args, timeout=60):
    r = subprocess.run([ADB] + list(args), capture_output=True, timeout=timeout)
    return r.stdout.decode("utf-8", errors="replace"), r.stderr.decode("utf-8", errors="replace"), r.returncode

# 等设备回来
print("等待设备...")
for i in range(40):
    time.sleep(5)
    out, _, _ = adb("devices")
    if "device" in out and "offline" not in out:
        print(f"设备在线 ({i*5}s)")
        break

# 等 boot 完成
for i in range(60):
    time.sleep(5)
    out, _, _ = adb("shell", "getprop sys.boot_completed")
    if out.strip() == "1":
        print(f"BOOT COMPLETED ({i*5}s)")
        break

out, _, _ = adb("shell", "cat /proc/uptime; ps -A -o PID,NAME | grep -cE 'zygote'; cat /proc/modules | grep -cE 'kernelsu|unpatch'; /data/local/tmp/su_ksu -c id 2>&1")
print(out.strip())
print("=== 状态解读: uptime 短=硬重启, zygote>0=框架OK, modules=0=KSU清空, su失败=无root ===")
