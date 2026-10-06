#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""onetap_softreboot_watch.py - 软重启 + 长时间观察 netd/zygote 稳定性"""
import os, shutil, subprocess, time, sys

ADB = os.environ.get("ADB") or shutil.which("adb") or "/usr/bin/adb"  # 可用环境变量 ADB=<路径> 覆盖
DEV = "/data/local/tmp"

def adb(*args, timeout=120):
    r = subprocess.run([ADB] + list(args), capture_output=True, timeout=timeout)
    return r.stdout.decode("utf-8", errors="replace"), r.stderr.decode("utf-8", errors="replace"), r.returncode

print("=== 软重启前状态 ===")
out, _, _ = adb("shell", "cat /proc/uptime; cat /proc/modules | grep -E 'kernelsu|unpatch'; " + DEV + "/su_ksu -c id 2>&1")
print(out.strip())

out, _, _ = adb("shell", "cat /proc/uptime")
upt0 = out.split()[0]
print("uptime before:", upt0)

print("=== 触发软重启 ===")
adb("shell", DEV + "/su_ksu -c 'stop; sleep 3; start'")

# 等 boot_completed
for i in range(40):
    time.sleep(5)
    out, _, _ = adb("shell", "getprop sys.boot_completed")
    if out.strip() == "1":
        print("BOOT COMPLETED after", (i+1)*5, "s")
        break
out, _, _ = adb("shell", "cat /proc/uptime")
upt1 = out.split()[0]
print("uptime after:", upt1, "(连续=软重启成功)")

# 长时间观察: 每 10s 采样 netd 崩溃 / zygote 状态 / 设备在线
minutes = int(sys.argv[1]) if len(sys.argv) > 1 else 8
end = time.time() + minutes * 60
crash_count = 0
while time.time() < end:
    time.sleep(10)
    # netd 崩溃检查
    out, _, _ = adb("logcat", "-d", "-b", "crash", "-t", "5")
    if "netd" in out and "SIGABRT" in out:
        crash_count += 1
        print(f"[{int(time.time()%1000)}s] !! netd crash detected ({crash_count})")
    # zygote 状态
    out, _, _ = adb("shell", "getprop init.svc.zygote; ps -A | grep -c zygote", timeout=30)
    lines = out.strip().splitlines()
    zstate = lines[0].strip() if lines else "?"
    zcount = lines[1].strip() if len(lines) > 1 else "?"
    # 设备在线?
    out2, _, _ = adb("devices")
    online = "device" in out2 and "offline" not in out2
    print(f"[watch] zygote={zstate} count={zcount} online={online}", flush=True)
    if not online:
        print("!! 设备掉线 (可能重启)")
        break

print(f"=== 观察结束: netd_crashes={crash_count} ===")

# 最终状态
out, _, _ = adb("shell", "cat /proc/uptime; cat /proc/modules | grep -cE 'kernelsu|unpatch'; " + DEV + "/su_ksu -c id 2>&1")
print(out.strip())
