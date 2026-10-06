# app-test — KSUOneTap 设备端测试脚本 (Python + adb)

> 这些脚本用于驱动 KSUOneTap App 的设备端测试，全部通过 `adb` 与设备交互。
>
> **adb 路径自动探测**（2026-09-27 起）：`ADB = os.environ.get("ADB") or shutil.which("adb") or "/usr/bin/adb"`。
> 需要指定时用环境变量覆盖即可：`ADB=/path/to/adb python3 onetap_state2.py`。
> （原先 11 个脚本各自硬编码了某台 Windows 机器的 `adb.exe` 路径，在别的机器上必然失败。）
>
> **APK 路径自动探测**（2026-09-27 起，`install_v100.py` / `onetap_test.py`）：
> 取 `apk/ksuonetap/out/` 里**版本号最大的** `KSUOneTap-v*.apk`，不写死版本
> （升版后脚本不用改）。要测特定包时用 `APK=/path/to/x.apk python3 ...` 覆盖 ——
> 例如回滚测 v1.0.5：`APK=apk/archive/KSUOneTap-v1.0.5.apk python3 onetap_test.py install`。

## 脚本清单

| 脚本 | 用途 |
|---|---|
| `onetap_test.py` | 基础: 卸载/安装 APK / 启动 App / 点按钮 / 抓日志 |
| `onetap_guarded.py` | 安全部署: 先检查 shizuku -> 未激活则自动激活 -> 启动 App 点一键提权 -> 观察 |
| `onetap_recover.py` | 崩溃后完整恢复: 等 boot -> 激活 shizuku -> 清理残留 -> 重新一键提权 |
| `onetap_deploy_now.py` | 启动 App + 点一键提权 + 持续抓 KSUONETAP 日志 |
| `onetap_softreboot_watch.py` | 软重启 (ksud soft-reboot) + 长时间观察 netd/zygote 稳定性 |
| `onetap_state2.py` | 快速状态: exploit 进程 / rootd_ready / 模块 / su |
| `final_state.py` | 最终状态: uptime / 模块 / root / zygote / netd 崩溃计数 |
| `install_v100.py` | 安装 APK (含 shizuku 自动激活) |
| `wait_boot.py` | 等设备重启完成并报告状态 |
| `ksud_softreboot_test.py` | 对比测试 ksud soft-reboot vs 裸 stop&&start |
| `u0_test.py` | 测试 u0 执行链 (insmod unpatch / 杀 exploit) |

启动 App 用的是 **`com.neoroot.ksuonetap/.ui.PagerActivity`** —— 四页（主页/日志/设置/关于）
现在都在这一个 Activity 里，`.MainActivity` 已不存在（2026-09-27 修正了脚本里的旧类名）。

## 常用命令

```bash
# 一键部署 (含 shizuku 检查/激活):
python3 onetap_guarded.py 10

# 崩溃后恢复 + 重新部署:
python3 onetap_recover.py 10

# 状态检查:
python3 onetap_state2.py
python3 final_state.py
```

## ⚙️ 设备适配

- `ADB` 已自动探测；必要时用环境变量 `ADB=` 覆盖
- `onetap_guarded.py` / `onetap_recover.py` / `install_v100.py` 里的 `SHIZUKU_SO`
  —— shizuku APK 的 `libshizuku.so` 路径，**每次重装 shizuku 都会变**（含安装哈希）。
  脚本里现在会先试常量、失败则用 `pm path moe.shizuku.privileged.api` 现推
- `DEV = /data/local/tmp` 部署目录
