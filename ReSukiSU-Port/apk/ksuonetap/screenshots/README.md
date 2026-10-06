# screenshots — v1.0.5 真机验证留档

设备：iQOO Neo9 `PD2338` / `V2338A`，系统 `PD2338_A_15.1.14.7.W10.V000L1`。

| 文件 | 验证内容 |
|---|---|
| `01-main.png` | 主界面：状态卡四行（设备=已适配 / Shizuku / KernelSU / Root=uid=0）、日志区独立滚动，root 可用时「软重启」「清理并重启」均为可点状态 |
| `02-settings.png` | 设置页：一键提取模式单选（互斥高亮）、当前设备判定、关于区 |
| `03-no-root-gating.png` | `--ez no_root true` 强制无 root：「软重启」「清理并重启」置灰（`enabled=false`），其余按钮仍可用 |
| `04-terminal-long-output.png` | 内置终端：`getprop` 几百行输出后自动跟随到底部，末尾 `[rc=0]` 完整（丢尾修复的验证点） |
| `05-terminal-scroll-history.png` | 手动上滑读历史 + 右下角出现「↓ 回到最新」浮层 |
| `06-icon-zoom.png` | 桌面图标放大：紫粉渐变 + Q 版角色（猫耳/呆毛/刘海/大眼高光/腮红） |

重新生成：`adb exec-out screencap -p > xxx.png`（放大用
`~/.workbuddy/binaries/python/envs/default/bin/python` + Pillow 裁剪）。
