#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""onetap_state2.py - 查看部署状态 (logcat 短超时)"""
import os, shutil, subprocess

ADB = os.environ.get("ADB") or shutil.which("adb") or "/usr/bin/adb"  # 可用环境变量 ADB=<路径> 覆盖

def adb(*args, timeout=30):
    r = subprocess.run([ADB] + list(args), capture_output=True, timeout=timeout)
    return r.stdout.decode("utf-8", errors="replace"), r.stderr.decode("utf-8", errors="replace"), r.returncode

out, _, _ = adb("shell", "ps -A -o USER,PID,PPID,NAME | grep -E 'exploit|ksuonetap'")
print("=== procs ===")
print(out.strip() or "(none)")

out, _, _ = adb("shell", "ls -la /data/local/tmp/rootd_ready.txt /data/local/tmp/exploit_daemon.log 2>&1")
print("=== files ===")
print(out.strip())

out, _, _ = adb("shell", "cat /data/local/tmp/exploit_daemon.log 2>&1 | tail -5")
print("=== exploit log tail ===")
print(out.strip() or "(empty)")

out, _, _ = adb("shell", "cat /proc/modules | grep -E 'kernelsu|unpatch'")
print("=== modules ===")
print(out.strip() or "(none)")

out, _, _ = adb("shell", "/data/local/tmp/su_ksu -c id 2>&1")
print("=== su ===")
print(out.strip())

out, _, _ = adb("shell", "cat /data/local/tmp/rootd_out 2>&1 | tail -20")
print("=== rootd_out ===")
print(out.strip() or "(none)")
