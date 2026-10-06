# KSU 移植工程包 (iQOO Neo9 PD2338) —— 已切换原版 KernelSU

> 内核 KSU 驱动移植完整工程 —— 内核模块 / 用户态工具 / 构建脚本 / 提权 App
> 设备: vivo iQOO Neo9 (V2338A / PD2338), OriginOS 6 (16.2.13.2), 内核 5.15.197-g708015331567-dirty (SM8550)
> 状态: ✅ **2026-10-06 起内核驱动 = 原版 tiann/KernelSU v3.3.0 (KSU_VERSION 32601)**
>        —— ko (kernelsu-vanilla-197.ko, 橘子6 混血树 M= 重编) + 官方 Manager v3.3.0 + 官方 ksud 三者同源,
>           2026-10-05 GhostLock App v4 真机验证 (exploit→ko 加载→Manager 识别内核 32601)
>        ✅ KSUOneTap v1.2.0-kernelsu-original (GhostLock LPE 一键提权, 无 Shizuku + 内置终端 + vr.ko 绕过;
>           「激活 KSU」改为 pre-KSU 顺序 + PackageInstaller 免 root 装管理器, 待真机复验)
>        📦 历史 ReSukiSU 线 (35184, 绑 15.1.14.7/内核 5.15.178) 产物已移除:
>           源码 diff 留档在 docs/resukisu-local-diff-fa8311f6-20261006.patch,
>           完整源码树备份在 ~/kernel/kernelsu-src-backups/resukisu-fa8311f6-full (含未提交改动)

> ⚖️ 授权与使用: **仅供安全研究使用**；第三方组件版权归各自作者。
> 详见下方 [授权与使用](#授权与使用) 章节。
> 🛡️ vr.ko 绕过方案: 见 [neo9-root/docs/VRKO_BYPASS.md](neo9-root/docs/VRKO_BYPASS.md)。
> 🧭 找东西先看 [docs/INDEX.md](docs/INDEX.md)。
> 📟 驱动版本状态单一来源: [kernel-module/VERSION.txt](kernel-module/VERSION.txt)。

> 🧭 **找东西先看 [docs/INDEX.md](docs/INDEX.md)**（仓库导航：目录职责 / 文档地图 / 关键路径速查）。

## 构建

统一入口是仓库根的 `./build.sh`：

```sh
./build.sh              # 一把梭: 编 exploit + 离线回归 + assets 同步校验 + 产物校验
./build.sh doctor       # 环境自检 (工具链/设备/关键文件/地址锁) —— 换机器先跑这个
./build.sh fetch-ndk    # 本机没有 NDK 时 (下到 ~/android-ndk-cache)
./build.sh exploit --no-static   # 只要 NDK 版
./build.sh test         # 只跑离线回归 (不需设备/工具链)
./build.sh sync         # 校验 assets 打包副本与源目录一致 (忘了同步不会报错, 只会"改了没生效")
./build.sh clean --deep # 清日志与产物
```

产物在 `neo9-root/exploit/out/<Build.DISPLAY>/`，推荐推 `exploit_vivo_neo9_stable_su_ndk13`
（内置 su，默认 `su -c 'cmd'` 即 uid=0）。**双闸门保证地址不变**：编译期 `_Static_assert`
（逐 profile）+ `verify_bins.py`（先核对 `fw_profile.h` 与期望表一致，再反汇编核对）。

## 目录结构

```
ReSukiSU-port/
├── build.sh                      # ★ 统一构建入口 (exploit / modules / test / sync / verify / doctor / fetch-ndk / clean)
├── README.md                     # 本文件
├── (顶层不放 LICENSE —— 许可与授权说明在 apk/ksuonetap/, 见「授权与使用」章节)
├── kernel-module/
│   ├── kernelsu-vivo.ko          # ✅ 最终可用模块 (LTO+CFI 构建, 12.3MB)
│   └── vivo.config               # 设备内核配置 (构建基础)
├── userspace/                    # KSU 用户态工具 (源码 + 常用二进制)
│   ├── ksud                      # KernelSU 用户态 (从 Manager APK 提取, v4.2.0-rc1)
│   ├── su_ksu (+ su_patched.c)   # 补丁版 su 客户端 (去 prctl 门禁, execve /system/bin/su)
│   └── *.c                       # 探针源码 (ksu_probe/ksu_probe2/ksu_feature_probe/asuid/su_test2)
│                                 #   对应二进制已归档 -> neo9-root/archive/probes/
├── scripts/
│   ├── fetch-ndk.sh              # ★ 下载 NDK r29 (分片并发, 不动系统路径)
│   ├── fetch-manager.sh          # 拉 ReSukiSU 管理器 APK (顺带同步提取 ksud)
│   ├── build_ksu_module.sh       # ★ 可移植的模块编译 (含 --version-only 版本核对)
│   ├── build_ksu_lto2.sh         # 旧版 WSL 专用构建脚本 (已由 build_ksu_module.sh 取代)
│   ├── patch_ko_version.py       # 只改 .ko 里的版本码, 不重编
│   ├── check-assets-sync.sh      # ★ assets 打包副本一致性闸门 (./build.sh sync)
│   ├── app-test/                 # KSUOneTap 设备端测试脚本 (Python+adb)
│   └── archive/                  # 旧单步集成/部署脚本 (build_ksu.sh / deploy_all.ps1 等 9 个)
├── neo9-root/                    # 临时 root 工程 (不含 magisk)
│   ├── exploit/                  # ★ exploit_vivo.c (唯一源码) + stubs/*.s
│   │                             #   ★ 稳定性层 v2 (STAB): 写-校验闭环 / 写前校验 / 残留救援 /
│   │                             #     单进程提权 / GPU 硬闸门 —— 地址与偏移零改动
│   │                             #   ★ 内置 su: 本进程开 socket 命令服务 + 同二进制做 su 客户端
│   │                             #     默认 uid=0 (setcon 白名单域 + setuid), vr.ko 未中和也可用
│   │                             #   构建: ./build.sh exploit (或本目录 build_exploit_stable.sh)
│   │                             #   测试: test_stub_state.py / test_su_protocol.py / verify_bins.py
│   ├── scripts/                  # 真机驱动: run_su_until_root.py (反复试到命中) + test_run_su_driver.py + logs/
│   ├── client/                   # 只剩 u0 (提权辅助: 激活 KSU 时 u0 ksud insmod); 随包进 assets/u0
│   ├── tsu/                      # Termux su 工具 (tsu)
│   ├── tools/                    # vr.ko / 内核分析工具 (disasm_vrko*.py 等, 重推偏移用)
│   ├── docs/                     # 调试记录 + vr.ko 分析/绕过 + STABILITY_V2 + SU_BUILTIN
│   └── archive/                  # 归档区 (含每次清理记录 CLEANUP_LOG.md)
│       ├── pre-stable/           #   稳定性层 v2 之前的 exploit 产物与旧构建脚本
│       ├── probes/               #   KSU 探索期探针二进制
│       ├── old-client/           #   老 rootd 队列客户端 (rootc / shim.so / su 包装器)
│       └── test-modules/         #   早期 KSU 测试模块
├── test-modules/                 # 内核模块
│   ├── src/                      # ksu_testmod / puretest / proctest 源码 (二进制已归档)
│   ├── unpatch/                  # ★ 软重启兼容模块 (unpatch.ko + 源码 + 构建脚本)
│   └── vrpatch/                  # ★★ vr.ko 中和模块 (vrpatch.ko, 软重启 netd 不崩)
├── docs/                         # 流程与集成文档
│   ├── INDEX.md                  # ★ 仓库导航索引 (先看这个)
│   ├── KSU_FIX_RECORD.md         # 根因分析: struct module 布局错位 (CFI cfi_check)
│   ├── RESUKISU_FULL_FLOW.md     # 完整流程验证记录 + 重启部署步骤
│   └── KERNEL_INTEGRATION.md     # 内核源码修改点 (vermagic/Kconfig/LTO+CFI)
├── patches/
│   └── extract_integration.sh    # 从内核源码提取集成修改
├── apk/
│   ├── ksuonetap/                # ★ KSUOneTap 完整工程 (21 个 Kotlin: core/deploy/ui/page 四包 + res + assets)
│   │                             #   dock 四页 (主页/日志/设置/关于) + 跟手滑动 / 6 套主题色板 + 深浅模式 / 内置终端 /
│   │                             #   设备版本门控 / 机器人持钥匙矢量图标
│   │                             #   assets/<系统版本>/ = 系统版本绑定产物 (exploit + 3 个 .ko);
│   │                             #   目录名 = 设备 Build.DISPLAY, 见 assets/PD2338_A_15.1.14.7.W10.V000L1/SYSTEM.txt
│   └── archive/                  # apk 侧归档: 旧 App 1.0.0_beta1、旧 Manager 35072
└── source/
    └── resukisu/                 # ★ ReSukiSU 源码 (main @ fa8311f6 / tag v4.2.0-rc3, **含完整 .git**)
                                  #   KSU_VERSION = 30000 + 4484 + 700 = 35184
                                  #   改这里就是改 KSU 本体; 版本码只由 git 提交数决定, 不能手改
                                  #   核对: ./build.sh doctor  |  bash scripts/build_ksu_module.sh --version-only
```

## 快速使用

### KSUOneTap App (推荐, 手机端一键)

```sh
# 1. 激活 shizuku (每次重启后一次):
adb shell /data/app/~~*/moe.shizuku.privileged.api-*/lib/arm64/libshizuku.so
# 2. 安装 + 打开 App 点 "一键提权" (自动部署 + KSU + unpatch + 杀 exploit)
adb install apk/ksuonetap/out/KSUOneTap-v1.0.6.apk
# 3. 软重启按钮 = ksud soft-reboot (ReSukiSU 同款, 稳定不崩 netd)
```

详见 `apk/ksuonetap/README_KSUOneTap.md` 和 `scripts/app-test/`。

### 一键部署 (App)

主机侧的 `deploy_all.ps1` 已归档（见 `scripts/archive/README.md`）—— 部署逻辑现在
全在 App 里（`apk/ksuonetap/src/.../deploy/`）。设备侧只剩两步：

```sh
# 1. 装 App, 打开并授权 Shizuku
adb install -r apk/ksuonetap/out/KSUOneTap-v1.0.6.apk
# 2. App 内点「一键提取 Root」(冷窗口内命中率最高)
```

完整模式会自动走完: 部署文件 → 临时 root → `ksud insmod kernelsu-vivo.ko` →
`unpatch` + `vrpatch` → 装 Manager。仅提取 root 模式则停在临时 root（拿到 `$DEV/su`），
之后可在 App 里点「激活 KSU」接着上，**不用重跑漏洞**。

### 手动步骤 (等价于 App 的完整流程)

```sh
DEV=/data/local/tmp
# 1. 冷窗口内 (<5 分钟) 起 exploit —— 内置 su 服务会自己把 $DEV/su 装好
adb push neo9-root/exploit/out/$VER/exploit_vivo_neo9_stable_su_ndk13 $DEV/exploit_vivo_neo9
adb shell "cd $DEV && CHEESE_SU=1 CHEESE_STEXT_PA=0xa8010000 CHEESE_PATCH_CAP=1 \
  nohup ./exploit_vivo_neo9 > exploit_daemon.log 2>&1 &"
#    等 $DEV/su_ready.txt 出现 (spray 最长约 9 分钟; 勿重复启动 —— 新版有并发防护)

# 2. 确认临时 root (内置 su 默认 uid=0, 域 u:r:shell:s0):
adb shell "$DEV/su -c id"                    # → uid=0(root)

# 3. 加载 KernelSU —— 模块一律经 ksud insmod (裸 insmod 不做 UAPI 校验/初始化, 不能用)
adb shell "mkdir -p /data/adb/ksu/bin && cp $DEV/ksud /data/adb/ksu/bin/ksud && chmod 755 /data/adb/ksu/bin/ksud"
adb shell "$DEV/u0 $DEV/ksud insmod $DEV/kernelsu-vivo.ko allow_shell=1"
adb shell "$DEV/su_ksu -c '$DEV/ksud insmod $DEV/unpatch.ko'"
adb shell "$DEV/su_ksu -c '$DEV/ksud insmod $DEV/vrpatch.ko'"

# 4. 结束临时 root (系统只剩 KSU):
adb shell "killall -9 exploit_vivo_neo9"

# 5. 验证:
adb shell "$DEV/su_ksu -c id"                # → uid=0(root) u:r:ksu:s0
# Termux 内: tsu  (com.termux 已被 allowlist 预授权)
# 之后: 软重启 (ksud soft-reboot / Manager 按钮) 安全; 完整重启后重新走 1-5
```

## 📱 适用系统版本 (系统版本绑定产物)

> **本工程已适配两版，都是完整适配**（`tier=full`：exploit + 3 个内核模块）。
> exploit 的版本绑定常量按 profile 分支写在 `neo9-root/exploit/fw_profile.h` 里。
>
> | 系统版本 | 系统 / 内核 | 3 个内核模块怎么来的 | 真机验证 |
> |---|---|---|---|
> | `PD2338_A_15.1.14.7.W10.V000L1` | OriginOS 5 / Android 15<br>`5.15.178-gaacdc35637c4-dirty` | **真重编**（官方内核源码包 + clang 18） | 2026-09-27 全链路 |
> | `PD2338_A_14.0.17.2.W10.V000L1` | OriginOS 4 / Android 14<br>`5.15.137-gc870e76526d2-dirty` | `kernelsu-vivo.ko` = 上面那份做 **vermagic 等长对齐**<br>（该固件是 vivo 自研内核、无公开源码树，无法重编）<br>`vrpatch.ko` / `unpatch.ko` = 按 137 profile **重编** | 2026-09-28：LPE 走通<br>+ 3 个模块全部 `Live` |
>
> 14.0.17.2 的实测证据：
> - 三个版本绑定偏移（vhangup `+0x5d2db4` / cap_bprm `+0x92937c` / kptr_restrict `+0x2ccdcb4`）
>   写下去即校验通过，`uid=0(root)` / 域 `u:r:shell:s0`
> - `./su -c "./ksud insmod …/kernelsu-vivo.ko"` → `/proc/modules` 里 `kernelsu` / `vrpatch` /
>   `unpatch` 全部 `Live`，`id` → `context=u:r:ksu:s0`
> - 详见各版本目录的 `SYSTEM.txt` 与 `apk/ksuonetap/README_KSUOneTap.md` 的「真机验证记录」
>
> 机型均为 iQOO Neo9 `PD2338` / `V2338A`。
>
> ⚠️ **仍未验证**：两版都还没用 v1.0.6 跑一遍**由 App 自动完成的完整流程**
> （14.0.17.2 的模块加载是手工 `ksud insmod` 验证的；15.1.14.7 那条还没用 v1.0.6 回归）。

随包产物按"是否绑定系统版本"分两处存放：

| 产物 | 绑定什么 | assets 内位置 |
|---|---|---|
| `exploit_vivo_neo9` | 内核**物理布局**与**符号/字段偏移**：`KERNEL_PHYS_BASE` / `CHEESE_STEXT_PA` / `gPhyAddrs[]` / `NEO9_*_OFFSET` / spray | `assets/<系统版本>/`（两版各一份） |
| `kernelsu-vivo.ko` | vermagic + `struct module` 布局（LTO/CFI/BTF）+ `KSU_VERSION` | 两版各一份（14.0.17.2 那份是 vermagic 对齐过的） |
| `unpatch.ko` | 同上 + `CAP_BPRM_PA`（= stext_pa + 符号偏移） | 两版各一份 |
| `vrpatch.ko` | 同上 + vr.ko 内部检测函数偏移 `VR_DETECT_OFFSET` | 两版各一份 |
| `u0` / `su_ksu` / `ksud` / `resukisu-manager.apk` | **不绑系统版本**（只绑 KSU 版本与 UAPI） | `assets/` 顶层 |

App 取件路径由**设备自己报的软件版本号**决定（`DeviceGate.resolveBuildDir()` →
`DeployScript.assetPath()`）—— 注意**不能**用 `Build.DISPLAY`：vivo 的 `ro.build.display.id`
在 OriginOS 4 上是 AOSP build id（`UP1A.231005.007 release-keys`），不含版本号；只能读
`ro.vivo.default.version` 这类 prop 再归一化成版本核比对。所以结构上不可能把别的系统版本的
偏移推到本机；版本不匹配时部署会在推文件之前就明确报错并拒绝。
每个版本目录的 `SYSTEM.txt` 里有 `tier=` 一行，声明这一版是 `full` 还是 `lpe-su`
（App 与同步闸门都按它决定"必须齐备哪些产物"）。当前两版都是 `full`；`lpe-su`
是留给"先只做 LPE、内核模块留待后续"的新版本的档位。

**适配一个新的系统版本**：

1. 从该固件的 `boot.img` 抽出内核镜像，用
   `neo9-root/tools/fw_kernel_derive.py` 重推全部版本绑定常量
   （完整流程 + 每个值的取证判据见 `neo9-root/tools/README.md` 的「换系统版本怎么重推」）
2. 在 `neo9-root/exploit/fw_profile.h` 加一个 `#if defined(FW_<新版本>)` 分支，并补上
   `exploit_vivo.c` 的 `_Static_assert`、`fw_profile.py` / `verify_bins.py` 的期望表、
   `build_exploit_stable.sh` 的 `FW_TABLE`
3. `./build.sh exploit --fw <新版本>` → `./build.sh verify --fw <新版本>`；
   要上 KernelSU 还得按新内核重编 3 个 `.ko`
4. 在 `apk/ksuonetap/assets/` 下新建以该版本**软件版本号**命名的目录，放入产物
   + `SYSTEM.txt`（含 `tier=`），并在 `DeviceGate` 的 `ADAPTED_BUILDS`（full）或
   `LPE_ONLY_BUILDS`（lpe-su）加一条。新产物落 `<组件>/out/<版本>/`
   （`neo9-root/exploit/out/`、`test-modules/out/`、`kernel-module/out/`）。
   若该固件没有公开内核源码树、编不出 `kernelsu-vivo.ko`，就用
   `scripts/patch_ko_vermagic.py` 把已有那份的 vermagic **等长**对齐过去
   （14.0.17.2 就是这么做的，实测可加载）
5. 跑 `./build.sh sync` —— 它校验 **目录 ↔ SYSTEM.txt 的 tier ↔ DeviceGate 清单 ↔ md5**
   四处互相覆盖，任何一处不齐都会直接报错

## ⚙️ 设备适配指南 (移植到其他设备)

> 本工程所有值均为 **iQOO Neo9 PD2338C / 内核 5.15.178-gaacdc35637c4-dirty** 实测。
> 移植到其他设备时, 按下表逐项修改 (各脚本内均有标注 `⚙️ 设备适配` 注释)。

| # | 值 | 位置 | 如何确定 |
|---|---|---|---|
| 1 | **adb 路径** | `scripts/app-test/*.py` 与 `scripts/archive/deploy_all.ps1` 的 `$ADB` | 本机 `where adb` |
| 2 | **stext 物理地址** `0xa8010000` | exploit 的 `CHEESE_STEXT_PA`（App 侧在 `Deployer.kt` 启动参数里传） | 设备内核镜像/exploit 分析 (Neo9 无 KASLR 固定值) |
| 3 | **exploit 二进制** `exploit_vivo_neo9` | `apk/ksuonetap/assets/<系统版本>/exploit_vivo_neo9` ← `neo9-root/exploit/out/<系统版本>/exploit_vivo_neo9_stable_su_ndk13` | 目标设备的提权漏洞 exploit |
| 4 | **vermagic** `-gaacdc35637c4-dirty` + `"vivo "` flag | build_ksu_lto2.sh 的 CONFIG_LOCALVERSION | 设备 `cat /proc/vermagic` / boot 镜像 modinfo |
| 5 | **内核 config** (LTO+CFI 等) | build_ksu_lto2.sh 基于 `kernel-module/vivo.config` | 设备 `zcat /proc/config.gz` |
| 6 | **cap_bprm 物理地址** `0xa894cdbc` + 原始指令 | unpatch.c 的 `CAP_BPRM_PA` / `CAP_BPRM_ORIGINAL` | symbols.txt: 符号偏移 + stext_pa; 镜像读原始字节 |
| 7 | **WSL 构建路径** `/root/kernel/*`, `/mnt/d/...` | build_ksu_lto2.sh / unpatch/build.sh | 实际构建环境 |
| 8 | **ksud / Manager** | userspace/ksud, apk/ | 目标设备安装的 Manager APK 提取 |
| 9 | **模块加载参数** `allow_shell=1` | `apk/ksuonetap/src/.../deploy/DeployScript.kt` 的 `ksuLoadCommand()` | 是否需要 shell uid 直接提权 |

**移植验证流程**: ① 改表内所有值 → ② 按 docs/KERNEL_INTEGRATION.md 构建 kernelsu-vivo.ko
→ ③ 构建 unpatch.ko → ④ 跑 deploy_all.ps1 -SoftRebootReady → ⑤ 验证 su + 软重启。

## 关键结论 (踩坑记录)

1. **模块 init 不执行的根因**: 构建时关 LTO → CFI 连带关闭 → struct module 少 8 字节
   (cfi_check 字段) → 内核读 mod->init 偏移错位 → init 静默跳过。修复: LTO+CFI 与设备一致。
2. **ksud 必须真实 uid 0 运行** (u0 工具), vivo 的 /proc/kallsyms 有 uid 检查。
3. **设备在开机几分钟后把 kptr_restrict 设为 2**, 模块加载必须在窗口期内。
4. **vivo 内核抑制模块日志** (dmesg 无模块 pr_info), 调试靠行为验证。
5. **写 tracefs kprobe_events 会立即重启设备** (vivo 防护), 禁止。
6. ⛔ **红线: 未解锁 BL, 禁止 boot patch** (vbmeta 校验, 会变砖)。

## 版本对应

| 组件 | 版本 | 来源 |
|---|---|---|
| **适用系统** | **`PD2338_A_15.1.14.7.W10.V000L1`**（iQOO Neo9 / OriginOS 5 / 内核 5.15.178，`tier=full`）<br>**`PD2338_A_14.0.17.2.W10.V000L1`**（OriginOS 4 / 内核 5.15.137，`tier=full`） | 见上方「适用系统版本」；绑定产物在 `assets/<系统版本>/` |
| 内核模块 | ReSukiSU v4.2.0-rc3 (KSU_VERSION=35184, UAPI v4) | 本工程用官方内核源码包重编 |
| ksud | v4.2.0-rc3 (`4.2.0-rc3-13-gfa8311f6`, uapi 4) | Manager APK lib/arm64-v8a/libksud.so |
| Manager | v4.2.0-rc3 (versionCode **35184**，与驱动同源同提交) | apk/ksuonetap/assets/resukisu-manager.apk（顶层，不绑系统版本） |
| KSUOneTap | v1.0.6 | 本工程 (apk/ksuonetap/) |
| 兼容性 | ✅ 完全兼容 (核心 ioctl 一致) | feature list / sulog / 模块管理实测 |
| **更新原则** | Manager/ksud 可更新, 但 **UAPI 版本必须匹配** (`ksucalls::ensure_uapi_version_matched`, 严格 `!=` 拒绝所有操作) | 驱动侧重编时才需要, 当前 UAPI v4 |

## 授权与使用

**本工具仅供安全研究使用，请勿用于非法用途。**

随包与仓库内引用的第三方组件（KernelSU / ReSukiSU 上游、Shizuku API、自研内核模块、
exploit 的上游血统等）版权归各自作者，许可与源码位置按各自原样保留在对应目录
（例如 `source/resukisu/LICENSE`、`neo9-root/tsu/LICENSE*`），不再逐个抄录。
`apk/ksuonetap/LICENSE`（GPL-3.0 全文，取自上游）与 `NOTICE` 保留。

`source/resukisu/.git` **不能删** —— `KSU_VERSION` 由提交数决定。
