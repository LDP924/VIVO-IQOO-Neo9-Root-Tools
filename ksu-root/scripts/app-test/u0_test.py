#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""u0_test.py - 测试 u0 执行链"""
import os, shutil, subprocess

ADB = os.environ.get("ADB") or shutil.which("adb") or "/usr/bin/adb"  # 可用环境变量 ADB=<路径> 覆盖
DEV = "/data/local/tmp"

def adb(*args, timeout=60):
    r = subprocess.run([ADB] + list(args), capture_output=True, timeout=timeout)
    return r.stdout.decode("utf-8", errors="replace"), r.stderr.decode("utf-8", errors="replace"), r.returncode

# 1. 直接执行 u0
out, _, _ = adb("shell", DEV + "/u0 id 2>&1")
print("u0 id:", out.strip())

# 2. u0 ksud insmod unpatch (KSU root 域执行)
out, _, _ = adb("shell", DEV + "/su_ksu -c '" + DEV + "/u0 " + DEV + "/ksud insmod " + DEV + "/unpatch.ko' 2>&1")
print("insmod unpatch:", out.strip())

# 3. 验证 unpatch 加载
out, _, _ = adb("shell", "cat /proc/modules | grep -E 'kernelsu|unpatch'")
print("modules:", out.strip())

# 4. 杀 exploit daemon (kernel sid 靶子)
out, _, _ = adb("shell", DEV + "/su_ksu -c 'kill -9 18334 18630 2>/dev/null; sleep 1; ps -A | grep -c exploit'")
print("exploit procs:", out.strip())
