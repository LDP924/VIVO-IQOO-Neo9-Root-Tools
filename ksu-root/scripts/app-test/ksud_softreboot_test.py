#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""ksud_softreboot_test.py - 测试 ksud soft-reboot (对比 stop&&start)"""
import os, shutil, subprocess, time

ADB = os.environ.get("ADB") or shutil.which("adb") or "/usr/bin/adb"  # 可用环境变量 ADB=<路径> 覆盖
DEV = "/data/local/tmp"

def adb(*args, timeout=120):
    r = subprocess.run([ADB] + list(args), capture_output=True, timeout=timeout)
    return r.stdout.decode("utf-8", errors="replace"), r.stderr.decode("utf-8", errors="replace"), r.returncode

print("=== 软重启前 ===")
out, _, _ = adb("shell", "cat /proc/uptime; cat /proc/modules | grep -cE 'kernelsu|unpatch'; " + DEV + "/su_ksu -c id 2>&1")
print(out.strip())

out, _, _ = adb("shell", "cat /proc/uptime")
upt0 = out.split()[0]
print("uptime before:", upt0)

print("=== 触发 ksud soft-reboot ===")
t0 = time.time()
out, err, _ = adb("shell", DEV + "/su_ksu -c '" + DEV + "/ksud soft-reboot'")
print("rc:", out.strip() or err.strip())
print("触发耗时:", round(time.time()-t0, 1), "s")

# 等 boot_completed 重新变 1 (soft-reboot 会先置 0 再置 1)
for i in range(60):
    time.sleep(5)
    out, _, _ = adb("shell", "getprop sys.boot_completed")
    if out.strip() == "1":
        print(f"BOOT COMPLETED after {round(time.time()-t0,1)}s")
        break
out, _, _ = adb("shell", "cat /proc/uptime")
print("uptime after:", out.split()[0])

# 观察 netd 崩溃 (关键: 应该远少于 stop&&start 的 26 次)
print("=== 观察 netd 5 分钟 ===")
end = time.time() + 300
crash_count = 0
while time.time() < end:
    time.sleep(10)
    out, _, _ = adb("logcat", "-d", "-b", "crash", "-t", "6")
    if "netd" in out and "SIGABRT" in out:
        crash_count += 1
        print(f"!! netd crash ({crash_count})", flush=True)
    out, _, _ = adb("shell", "getprop init.svc.zygote; ps -A | grep -c zygote")
    lines = out.strip().splitlines()
    print(f"[watch] zygote={lines[0].strip() if lines else '?'} count={lines[1].strip() if len(lines)>1 else '?'}", flush=True)

print(f"=== 结束: netd_crashes={crash_count} ===")
out, _, _ = adb("shell", "cat /proc/modules | grep -cE 'kernelsu|unpatch'; " + DEV + "/su_ksu -c id 2>&1; cat /proc/uptime")
print(out.strip())
