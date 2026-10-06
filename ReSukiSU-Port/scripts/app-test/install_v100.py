#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""install_v100.py - 安装 v1.0.0 并确认 shizuku"""
import os, glob, re, shutil, subprocess, time

ADB = os.environ.get("ADB") or shutil.which("adb") or "/usr/bin/adb"  # 可用环境变量 ADB=<路径> 覆盖
APK_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "apk", "ksuonetap", "out")

def resolve_apk():
    """APK 路径: 环境变量 APK= 优先, 否则取 out/ 里版本号最大的那个
    (不写死版本 —— 每次升版都要改 11 个脚本是不现实的)。"""
    if os.environ.get("APK"):
        return os.environ["APK"]
    cands = glob.glob(os.path.join(APK_DIR, "KSUOneTap-v*.apk"))
    if not cands:
        raise SystemExit("out/ 里没有 APK -> 先跑 apk/ksuonetap/build_ksuonetap.sh")
    def key(p):
        m = re.search(r"v(\d+)\.(\d+)\.(\d+)", os.path.basename(p))
        return tuple(int(x) for x in m.groups()) if m else (0, 0, 0)
    return max(cands, key=key)

APK = resolve_apk()
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

def adb(*args, timeout=180):
    r = subprocess.run([ADB] + list(args), capture_output=True, timeout=timeout)
    return r.stdout.decode("utf-8", errors="replace"), r.stderr.decode("utf-8", errors="replace"), r.returncode

# 1. 检查 shizuku
out, _, _ = adb("shell", "ps -A -o PID,NAME | grep shizuku_server")
if "shizuku_server" in out:
    print("shizuku 运行中:", out.strip())
else:
    print("shizuku 未运行 -> 激活")
    out, _, _ = adb("shell", resolve_shizuku_so(), timeout=60)
    print(out.strip())
    time.sleep(3)

# 2. 安装
print("== 卸载旧版 ==")
adb("uninstall", PKG)
print("== 安装 v1.0.0 ==")
out, _, _ = adb("install", APK)
print(out.strip() or "install done")

# 3. 确认版本
out, _, _ = adb("shell", "dumpsys package " + PKG + " 2>/dev/null | grep -E 'versionName|versionCode|minSdk|targetSdk'")
print(out.strip())
