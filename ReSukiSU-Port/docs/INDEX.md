# 仓库导航索引

> 本文件回答一个问题：**"我要处理 X，该动哪个文件？"**
> 整理于 2026-09-26；构建入口统一为仓库根的 `./build.sh`。
> 2026-09-27 全库源码整理后更新（脚本归档 / assets 同步闸门 / 路径参数化）。

## 1. 顶层布局

| 目录 | 内容 | 什么时候动它 |
|---|---|---|
| `build.sh` | **统一构建入口**（编译/测试/assets 同步校验/产物校验/自检/下载 NDK） | 编译、换机、排查环境 |
| `neo9-root/` | 临时 root 主线：exploit + 内置 su + 分析工具 | 改 exploit、su、稳定性层 |
| `test-modules/` | 内核模块：`vrpatch`(中和 vr.ko) / `unpatch`(恢复 cap_bprm)；`build.sh` 按 `FW=<版本>` 出产物到 `out/<版本>/` | 改 vr.ko 绕过策略 |
| `kernel-module/` | `kernelsu-vivo.ko` + 内核 `.config`（构建 KSU 用）；`out/<版本>/` 放 vermagic 对齐后的变体 | 重编 KSU 内核模块 |
| `source/resukisu/` | ★ ReSukiSU 源码（main @ `fa8311f6`，**含完整 `.git`**）→ `KSU_VERSION = 35184` | 改 KSU 本体、对齐驱动版本 |
| `apk/` | `ksuonetap/` 一键 App 源码（`core`/`deploy`/`ui`/`ui.page` 四包；界面 = 单 Activity + 跟手分页 + 底部 dock 四页 + 6 套主题色板）；`ksuonetap/assets/<系统版本>/` = **系统版本绑定产物**（目录名 = 设备**软件版本号**，即 `ro.vivo.default.version` 那串，**不是** `Build.DISPLAY` —— vivo 的 `ro.build.display.id` 在 OriginOS 4 上是 AOSP build id；每目录的 `SYSTEM.txt` 用 `tier=` 声明齐备要求：`full` = exploit + 3 个 .ko，`lpe-su` = 只有 exploit。当前两版都是 `full`）+ `archive/` 旧产物 | 改一键 App、适配新系统版本 |
| `userspace/` | KSU 用户态工具源码 + `ksud`/`su_ksu`（KSU 激活流程用） | 改 KSU 侧工具 |
| `scripts/` | 构建辅助（`fetch-ndk.sh` / `fetch-manager.sh` / `build_ksu_module.sh` / `patch_ko_version.py` / **`check-assets-sync.sh`**）+ `app-test/` 设备测试 + `archive/` 旧脚本 | 部署、集成、设备测试 |
| `patches/` | 内核源码集成的提取脚本 | 内核集成 |
| `docs/` | **流程与集成**文档（见 §2） | 交接、复现整体流程 |
| `neo9-root/docs/` | **底层调试与研究**文档（见 §2） | 查逆向结论、调试细节 |
| `neo9-root/archive/` | 归档区（含每次清理记录 `CLEANUP_LOG.md`） | 回溯旧产物 |
| `.workbuddy/memory/` | 逐日工作日志（**项目记忆，勿删**） | 交接上下文 |

`neo9-root/` 内部：

| 目录 | 内容 |
|---|---|
| `exploit/` | `exploit_vivo.c`（唯一源码）+ **`fw_profile.h`（系统版本绑定常量，按 profile 分支）** + `fw_profile.py`（解析/编码，供校验器与测试共用）+ `stubs/*.s` + `build_exploit_stable.sh`（`FW=<版本>` 选 profile，产物落 `out/<Build.DISPLAY>/`）+ 测试与产物校验 |
| `client/` | **只剩 `u0` / `u0.c`**（提权辅助：激活 KSU 时 `u0 ksud insmod`；随包进 `assets/u0`） |
| `tools/` | 一次性反汇编/分析脚本（见该目录 `README.md` 的索引），用于重推偏移 |
| `scripts/` | 真机驱动 `run_su_until_root.py` + 它的回归测试 |
| `tsu/` | Termux 侧 su 工具 |
| `archive/` | 归档区（见上），含 `old-client/`（老 rootd 客户端）、`logs/`（实跑证据） |

## 2. 文档地图（两个 docs 目录的分工）

`docs/` = **我做了什么、怎么复现**（流程/集成/验证记录）
`neo9-root/docs/` = **为什么这样做**（逆向结论/调试细节/机制）

### docs/

| 文档 | 一句话 |
|---|---|
| `INDEX.md` | 本文件 |
| `RESUKISU_VERSION.md` | ★ **KSU 源码版本治理**：版本码公式 / 谱系 / 升降级流程 / 为什么 .ko 反查不到版本 |
| `KERNEL_INTEGRATION.md` | vivo 内核源码要打哪些补丁才能集成 KSU |
| `KSU_FIX_RECORD.md` | KSU 移植过程的问题与修复记录 |
| `RESUKISU_FULL_FLOW.md` | 从零到 KSU 可用的完整流程验证记录 |

### neo9-root/docs/

| 文档 | 一句话 |
|---|---|
| `DEBUG_RECORD.md` | 主调试记录；**vr.ko 检测函数 / euid 语义 / 各 STUB_MODE 结论**都在这（§12） |
| `vrko_static_analysis.md` | vr.ko 静态逆向：符号、混淆、SELinux 自定义类、开关候选 |
| `VRKO_BYPASS.md` | vr.ko 绕过方案总纲（检测条件 / 三级绕过 / 部署顺序 / 实测数据） |
| `EXPLOIT0_FIX.md` | CVE-2025-21479 exploit 首次移植到 Neo9 的修复过程 |
| `DERIVE_PD2338_A_14_0_17_2.md` | **14.0.17.2（内核 5.15.137）版本绑定常量的推导记录**：输入哈希、每个值的取证判据、两版 config 旁证、沿用项的理由、**未适配/未验证清单** |
| `CONTEXT_SUMMARY.md` | ⛔ **已废弃**（2026-08-18，Magisk 授权层时代）—— 里面的设备事实仍可参考，但操作步骤与许可口径都已作废 |
| `STABILITY_V2.md` | **稳定性层 v2**：写-校验闭环 / fork 硬闸门 / 残留救援 / 地址不变闸门 + 真机实测 |
| `LKM_ON_OTHER_KERNEL.md` | **把 KSU 模块装到"没有对应源码"的另一版内核**：两条通路的实测判据（内核装载器 vs `ksuinit`/`ksud late-load`）、vermagic 等长可改但 `IGNORE_VERMAGIC` 在本内核无效、`__ksymtab` 导出面差异（52 个符号）、KMI 探测为什么在厂商内核上必然失败、`--kmi` 用法 |
| `SU_BUILTIN.md` | **内置 su**：协议、用法、与稳定性层的衔接、整机影响面、`--u0` 与 vr.ko 共存（§11）、默认 uid=0（§12） |
| `u0.md` | `u0` 工具说明 —— **仍在使用**：激活 KSU 时 `u0 ksud insmod`，且随包进 `assets/u0` |

### 其它

- `apk/ksuonetap/README_KSUOneTap.md` — 一键 App 的部署与使用
- `scripts/app-test/README.md` — 设备端测试脚本说明（adb 路径自动探测）
- `neo9-root/tools/README.md` — 一次性分析脚本索引（含 `VRT_ROOT` 约定与"输入文件不在仓库"的说明）
- `neo9-root/archive/README.md` — 归档规则与内容（`old-client/`、`logs/` 等各有自己的 README）
- `scripts/archive/README.md` — 旧集成/部署脚本的去向对照表
- `apk/archive/README.md` — 旧 APK 与旧 ksud 的归档说明（**含 ksud 版本回滚路径**）
- `neo9-root/tsu/README.md` — Termux su 工具

## 3. 关键路径速查

| 我要… | 去哪 |
|---|---|
| 编译全部产物 | `./build.sh` |
| 只编 exploit / 跳过静态版 | `./build.sh exploit --no-static` |
| 只编某一个固件 profile | `./build.sh exploit --fw 14.0.17.2`（`--fw --list` 看可选值） |
| 换系统版本时重推全部版本绑定常量 | `neo9-root/tools/fw_kernel_derive.py` + 流程见 `neo9-root/tools/README.md` |
| 换固件后重建 `vrpatch.ko` / `unpatch.ko` | `FW=14.0.17.2 MODULE=vrpatch bash test-modules/build.sh`（产物 `test-modules/out/<版本>/`；偏移怎么重推见 `neo9-root/docs/VRKO_BYPASS.md` §5） |
| 推 init-hook 载荷常量（目标固件 `/system/bin/init`） | `neo9-root/tools/init_hook_derive.py`；要先用 `fsck.erofs --extract=` 从 system.img 取 init |
| 换新机器先自检 | `./build.sh doctor` |
| 本机没有 NDK | `./build.sh fetch-ndk` |
| 跑离线回归（不需设备） | `./build.sh test` |
| 改了 `.ko` / `su` / `exploit` / `ksud` 后确认打包副本同步 | `./build.sh sync` |
| 换 KSU 管理器（顺带重提 ksud，防版本漂移） | `bash scripts/fetch-manager.sh --to-assets` |
| 校验产物（架构/地址闸门） | `./build.sh verify` |
| 改 STUB_MODE 行为（提权写什么字段） | `neo9-root/exploit/exploit_vivo.c` 的 `#if STUB_MODE == N` 段 |
| 改 su 行为/协议/默认 u0 | 同上，`stab_su_*` 函数 |
| 改稳定性层（写-校验/还原/救援） | 同上，标注 `STAB` 的段 |
| 重新推某个内核偏移 | `neo9-root/tools/disasm_vrko*.py` + 编译期 `_Static_assert` |
| 中和 vr.ko / 恢复 cap_bprm | `test-modules/vrpatch/`、`test-modules/unpatch/` |
| 一键提权流程 | `apk/ksuonetap/` |
| 找历史结论/踩过的坑 | `neo9-root/docs/DEBUG_RECORD.md` |

## 4. 常用命令

```sh
./build.sh                     # 编译 + 回归 + assets 同步校验 + 产物校验
./build.sh doctor              # 环境自检 (工具链/设备/关键文件/地址锁)
./build.sh sync                # 只做 assets 同步校验
./build.sh clean --deep        # 清日志与产物 (只留源码)

# 设备端
VER=PD2338_A_15.1.14.7.W10.V000L1   # 或 14.0.17.2
adb push neo9-root/exploit/out/$VER/exploit_vivo_neo9_stable_su_ndk13 /data/local/tmp/exploit_vivo_neo9
# 冷窗口 (<5min) 内运行, 命中后:
/data/local/tmp/exploit_vivo_neo9 --install-su
/data/local/tmp/su -c 'id'     # 默认 uid=0
```

## 5. 硬约束（别踩）

1. **漏洞命中相关地址一个字节都不能改**：`gPhyAddrs[]` / `KERNEL_PHYS_BASE` /
   `kFakeGpuAddr` / `CHEESE_STEXT_PA` / `NEO9_VHANGUP_OFFSET` / `NEO9_PREP_KRED_VA` /
   `NEO9_COMMIT_CREDS_VA` / spray 布局。已有两道闸门：编译期 `_Static_assert` +
   `verify_bins.py` 反汇编核对。
2. **模块刷入永远由人手动执行**，脚本只出产物。
3. **`source/resukisu/.git` 不能删**：`KSU_VERSION = 30000 + `git rev-list --count HEAD` + 700`，
   而且 Kbuild 会 `$(error)` 直接拒绝没有 `.git` 的源码（它要求源码是 git submodule 形态）。
   当前锁定 `main @ fa8311f6`，提交数 **4484** → `KSU_VERSION = 35184`。
   核对：`./build.sh doctor` 或 `bash scripts/build_ksu_module.sh --version-only`。
   （旧的 `source/resukisu-all.bundle` 实测缺对象、无法 fetch，已移出仓库。）
4. `.workbuddy/` 是项目记忆，不要删。
5. 设备的 `/tmp` 是 10MB tmpfs —— 大文件别往 `/tmp` 放。
6. **`apk/ksuonetap/assets/` 里的东西是副本，改完源必须同步**（`.ko` / `u0` / `su_ksu` /
   `exploit_vivo_neo9` / `ksud`）。忘了同步**不会报任何错**，只会表现为"改了却没生效"。
   闸门：`./build.sh sync`（`scripts/check-assets-sync.sh`）。
7. **`ksud` 必须与 manager 同版本**：它不是本仓库编译的，而是 manager APK 里的
   `lib/arm64-v8a/libksud.so`。换 manager 时用 `bash scripts/fetch-manager.sh --to-assets`
   （会顺带重提 ksud，并校验 UAPI 一致）。ksud 的 UAPI 校验是严格 `!=` —— 不匹配会
   **拒绝所有操作**。KSU 侧常量：`source/resukisu/uapi/supercall.h` 的
   `KERNEL_SU_UAPI_VERSION`（当前 **4**）。
