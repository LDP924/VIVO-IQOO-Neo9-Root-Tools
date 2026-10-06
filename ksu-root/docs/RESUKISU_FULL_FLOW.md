# ReSukiSU 完整流程验证记录 (iQOO Neo9 PD2338C) — 2026-08-19

> ⚠️ **这是历史记录（2026-08-19），描述的是 rootd/rootc 年代的流程。** 当时的命令与
> 版本号都不是现状：
> - 部署链路：rootd 文件队列 + `rootc` → 现为 exploit 内置 su（`$DEV/su`）
>   —— 老客户端已归档到 `neo9-root/archive/old-client/`
> - 主机脚本 `deploy_all.ps1` / `deploy_ksu.sh` → 已归档到 `scripts/archive/`，
>   部署逻辑现在 App 里（`apk/ksuonetap/src/.../deploy/`）
> - `ksud` 当时是 v4.2.0-rc1 → 现为 **35179 / v4.2.0-rc3**
>
> 本文的价值在于**当时的验证结论与踩坑记录**（哪些做法行得通、为什么），
> 现行操作步骤见仓库根 `README.md` 的「手动步骤」与 `docs/INDEX.md`。

## 结论

**ReSukiSU (KernelSU) 在 iQOO Neo9 上完整可用** —— 内核模块 (LTO+CFI 构建) + 设备上已装的
ReSukiSU Manager (v4.2.0-rc1) + 其自带 ksud 构成完整闭环，**无需重新构建 ksud**。
（早期观察到的 `feature list NOT_SUPPORTED` 是 uid 2000 权限不足所致——GET_FEATURE 的
perm_check 要求 uid 0 或 Manager，用 u0 运行即完全正常。）

## 完整流程验证清单

### 1. 内核侧
| 项目 | 结果 |
|---|---|
| 模块加载 | `kernelsu 221184 Live` (12.3MB LTO+CFI) |
| init 执行 | ✅ initstate 完整运行 (kobject_del 隐藏 sysfs 即证明) |
| reboot-fd 超级调用 | ✅ fd=3 安装成功 (uid 0 和 2000 均可) |
| GET_INFO | ✅ version=35077, flags=LKM\|LATE_LOAD, uapi=2 |
| GRANT_ROOT | ✅ uid 2000 → uid=0 |
| GET_FEATURE / SET_FEATURE | ✅ su_compat/kernel_umount 已启用; sulog 开→关往返正常 |
| sucompat execve hook | ✅ uid 2000 执行 /system/bin/su → root + u:r:ksu:s0 |

### 2. su 授权链路
| 场景 | 结果 |
|---|---|
| adb shell (uid 2000, allow_shell=1) | ✅ `su_ksu -c id` → uid=0 u:r:ksu:s0 |
| com.termux (uid 10325, allowlist 授权) | ✅ root (Manager 已预授权, profile=u:r:ksu:s0) |
| **Termux tsu (真实 app 实测)** | ✅ **用户实测: Termux 内 `tsu` 成功唤醒 su 获得 root** |
| 未授权 uid 10347 | ✅ 正确拒绝 (execve 不重定向 → ENOENT) |
| allowlist 持久化 | ✅ /data/adb/ksu/.allowlist (com.termux 条目) |

### 3. ksud 事件流
| 事件 | 结果 |
|---|---|
| post-fs-data | ✅ uapi 校验 → susfs(无,预期) → 模块脚本 → umount 配置 → restorecon |
| services / boot-completed | ✅ run_stage 执行模块脚本 |
| 模块脚本联动 | ✅ helloksu 模块 post-fs-data/service/boot-completed.sh 全部执行 |

### 4. 模块管理
| 操作 | 结果 |
|---|---|
| module install | ✅ KernelSU banner + 解压 modules_update |
| module list | ✅ JSON (enabled/version/author 等) |
| enable / disable | ✅ |
| uninstall | ✅ remove 标记 (延迟移除, 设计如此) |

### 5. 日志与功能
| 项目 | 结果 |
|---|---|
| sulog (feature set sulog 1) | ✅ /data/adb/ksu/log/sulog-*.log 记录 sucompat→root_execve 完整链 |
| boot 日志 | ✅ dmesg.log / logcat.log (post-fs-data 捕获) |
| Manager 集成 | ✅ logcat: `KsuCli: install result: true` |

## 设备端组件清单

```
/data/local/tmp/kernelsu-vivo.ko   # LTO+CFI 构建的模块 (12.3MB)
/data/local/tmp/ksud               # Manager APK 提取的 ksud (v4.2.0-rc1)
/data/adb/ksud                     # Manager 自装 (同一版本)
/data/local/tmp/su_ksu             # 补丁版 su 客户端 (execve /system/bin/su 触发内核 hook)
/data/local/tmp/u0                 # setuid(0) 工具 (真 root 上下文)
/data/local/tmp/deploy_ksu.sh      # 一键重载脚本
/data/local/tmp/ksu_probe2         # 超级调用诊断工具
/data/local/tmp/asuid              # 以指定 uid 执行 (模拟 app)
```

## 重启后重新部署 (冷启动流程)

```sh
# 1. 开机后尽快 (kptr_restrict 变 2 之前, ~5 分钟内):
adb shell "rm -f /data/local/tmp/rootd_ready.txt; \
  cd /data/local/tmp && CHEESE_STEXT_PA=0xa8010000 CHEESE_DAEMON=1 CHEESE_PATCH_CAP=1 \
  nohup ./exploit_vivo_neo9 > exploit_daemon.log 2>&1 &"

# 2. 等 rootd_ready.txt 出现 "ready"
# 3. 加载模块:
adb shell "/data/local/tmp/rootc 'sh /data/local/tmp/deploy_ksu.sh'"

# 4. 验证:
adb shell "/data/local/tmp/su_ksu -c id"     # → uid=0(root) u:r:ksu:s0
```

注: magisk 层 (magiskd/su_test) 与 KSU 是两套独立方案; KSU 侧不依赖 magisk。
sulog 等功能开关在重启后由 ksud post-fs-data 从 /data/adb/ksu 配置恢复。

## 已知边界

- **✅ 软重启已支持 (2026-08-19 解决)** —— 早期实测软重启会导致 zygote 崩溃循环
  (exploit 的 CHEESE_PATCH_CAP 内核 patch 残留)。解决方案:
  `unpatch.ko` 模块 (手动 PTE 修改, 绕过 set_memory_rw 的 vmalloc-only 限制)
  还原 cap_bprm_creds_from_file 原始指令 → 软重启恢复正常,
  kernelsu 模块幸存, KSU 提权不受影响。配合 `deploy_all.ps1 -SoftRebootReady`
  一键完成: KSU 激活 → unpatch → 杀 rootc → 系统只跑 KSU。
- **⛔ 红线：禁止 boot patch（未解锁 BL）** —— 本机 BL 未解锁，boot/init_boot 分区受
  vbmeta + 签名校验保护；任何 boot patch（ksud boot-patch / boot-patch-v2 /
  boot-restore / anykernel3 刷写）都会导致启动校验失败 **变砖**。
  持久化只能走"重启后重新加载 LKM"的临时方案，绝不能动 boot 分区。
- **弹窗授权**: 未授权 app 请求 su 时, ReSukiSU 不自动弹窗 (与 Magisk 不同);
  授权通过 Manager 超级用户页面手动管理 (allowlist 机制已验证)。
- **持久化**: 内核模块重启后需重新加载 (boot patch 已明确禁止, 遵守 /system 与
  boot 分区红线)。
- **ksud 构建**: 无需自建 —— 设备 Manager 自带 ksud 与内核完全兼容。
  如需自建: rustup + NDK (build.rs 需要 Android NDK clang++), 未做。
