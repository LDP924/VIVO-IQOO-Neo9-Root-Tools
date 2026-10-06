#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""onetap_test.py - KSUOneTap 安装/启动/授权/状态检查 全流程 (adb 驱动)
用法:
  python onetap_test.py install     # 卸载旧版 + 安装新版
  python onetap_test.py launch      # 启动 app
  python onetap_test.py status      # 点状态检查按钮 + 抓 logcat
  python onetap_test.py tap X Y     # 模拟点击
  python onetap_test.py log         # 抓 KSUONETAP logcat
"""
import os, glob, shutil, subprocess, sys, time, re

ADB = os.environ.get("ADB") or shutil.which("adb") or "/usr/bin/adb"  # 可用环境变量 ADB=<路径> 覆盖
APK_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "apk", "ksuonetap", "out")

def resolve_apk():
    """APK 路径: 环境变量 APK= 优先, 否则取 out/ 里版本号最大的那个
    (不写死版本 —— 每次升版都要改脚本是不现实的)。"""
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
PKG = "com.neoroot.ksuonetap"
ACT = f"{PKG}/.ui.PagerActivity"
TAG = "KSUONETAP"

def adb(*args, timeout=60):
    # 不用 text=True: adb 会吐二进制（如 exec-out screencap），严格 utf-8 解码会抛
    # UnicodeDecodeError 把脚本打崩（run_su_until_root.py 上真实发生过一次）。
    r = subprocess.run([ADB] + list(args), capture_output=True, timeout=timeout)
    return (r.stdout.decode("utf-8", errors="replace"),
            r.stderr.decode("utf-8", errors="replace"),
            r.returncode)

def main():
    cmd = sys.argv[1] if len(sys.argv) > 1 else "install"

    if cmd == "install":
        print("== 卸载旧版 ==")
        out, err, rc = adb("uninstall", PKG)
        print(out.strip() or err.strip())
        print("== 安装新版 ==")
        out, err, rc = adb("install", APK, timeout=180)
        print(out.strip() or err.strip())
        # 验证安装时间
        out, _, _ = adb("shell", "dumpsys package " + PKG + " 2>/dev/null | grep -E 'lastUpdateTime|minSdk|targetSdk'")
        print(out.strip())

    elif cmd == "launch":
        adb("logcat", "-c")
        out, err, rc = adb("shell", "am start -n " + ACT)
        print(out.strip() or err.strip())
        time.sleep(6)
        out, _, _ = adb("logcat", "-d", "-s", TAG + ":I")
        print(out)

    elif cmd == "status":
        # 点状态检查按钮 (第三个按钮, 屏幕 1260x2800, 按钮区在底部)
        out, _, _ = adb("shell", "uiautomator dump /sdcard/ui_onetap.xml")
        print(out.strip())
        out, _, _ = adb("shell", "cat /sdcard/ui_onetap.xml")
        # 找按钮坐标
        m = re.findall(r'text="([^"]*)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', out)
        for t, x1, y1, x2, y2 in m:
            if "状态" in t:
                cx, cy = (int(x1)+int(x2))//2, (int(y1)+int(y2))//2
                print(f"找到按钮 '{t}' @ ({cx},{cy})")
                adb("shell", f"input tap {cx} {cy}")
                break
        else:
            # fallback: 屏幕底部第三个按钮
            print("未在 UI dump 找到, fallback tap (630, 2450)")
            adb("shell", "input tap 630 2450")
        time.sleep(5)
        out, _, _ = adb("logcat", "-d", "-s", TAG + ":I")
        print(out)

    elif cmd == "tap":
        x, y = sys.argv[2], sys.argv[3]
        adb("shell", f"input tap {x} {y}")
        time.sleep(3)
        out, _, _ = adb("logcat", "-d", "-s", TAG + ":I")
        print(out)

    elif cmd == "log":
        out, _, _ = adb("logcat", "-d", "-s", TAG + ":I")
        print(out)

    elif cmd == "shizuku":
        # 查看 shizuku 状态
        out, _, _ = adb("shell", "ps -A -o USER,PID,NAME | grep shizuku")
        print(out)
        out, _, _ = adb("shell", "cat /proc/$(pidof shizuku_server)/status 2>/dev/null | grep Uid")
        print(out)

    else:
        print("unknown cmd")

if __name__ == "__main__":
    main()
