# vivo iqoo neo9 root — 项目索引

> vivo iQOO Neo9 (V2338A / PD2338) root 工作区总目录 · 2026-10-06 重写
> 当前固件：PD2338_A_16.2.13.2.W10.V000L1（OriginOS 6 / Android 16，内核 5.15.197-g708015331567-dirty）
> 当前 KSU 体系：**原版 tiann/KernelSU v3.3.0（KSU_VERSION 32601）**——ko / 官方 Manager / 官方 ksud 三者同源
> 主用漏洞：CVE-2026-43499（GhostLock，futex PI 竞态，16.2.13.2 上已真机验证）；研究中：CVE-2026-64560（Zombietick，修复进 5.15.213，本机 5.15.197 在受影响范围，工作区 `ReSukiSU-Port/neo9-root/cve64560/`）。完整候选清单见 `漏洞清单.md`，路线综述见 `root-research-survey.md`

## 目录布局

```
vivo iqoo neo9 root/
├── README.md                     ← 本文件（索引）
├── 漏洞清单.md                   ← 候选 CVE 状态清单
├── root-research-survey.md       ← root 路线调研长文
├── ksuonetap/
│   ├── current -> ReSukiSU-Port/apk/ksuonetap   ← 当前 KSUOneTap 工程（assets 按 16.2.13.2 配）
│   └── archive-README.md         ← 旧归档区清单（归档本体已随 2026-10-05/06 整理清掉，此文档仅留作历史索引）
├── ReSukiSU-Port/                ← KSU 移植主项目（目录名保留历史，2026-10-06 起驱动 = 原版 KernelSU）
│   ├── apk/ksuonetap/            ← KSUOneTap 工程 + 按固件分目录的 assets
│   │   ├── build_ksuonetap.sh    ← 无 gradle 构建入口（aapt2/kotlinc/d8/apksigner）
│   │   ├── out/                  ← 构建产物
│   │   └── assets/
│   │       ├── KernelSU.apk      ← 官方管理器 v3.3.0（10,777,987B，sha256 c197060e…）
│   │       ├── ksud              ← 官方 ksud v3.3.0（6,286,568B，sha256 8614de6c…）
│   │       └── PD2338_A_16.2.13.2.W10.V000L1/
│   │           ├── kernelsu-vanilla-197.ko   ← 原版驱动（6,611,496B，sha256 c2564090…）
│   │           ├── vrpatch.ko                ← vr.ko 检测中和（与 KSU 版本零耦合）
│   │           ├── kernel_profile.conf       ← GhostLock runtime profile
│   │           └── SYSTEM.txt                ← 本固件绑定产物与验证记录（单一来源之一）
│   ├── kernel-module/            ← 驱动产物副本 + VERSION.txt（★驱动版本状态单一来源）
│   ├── source/                   ← KernelSU-upstream -> ~/kernel/KernelSU-upstream（v3.3.0 tag，完整 .git）
│   ├── userspace/                ← ksud（v3.3.0）+ su_ksu/u0/probe（历史遗留）
│   ├── test-modules/             ← vrpatch / vrread / unpatch 源码 + 旧固件 out/（14.0.17.x）
│   ├── neo9-root/                ← exploit 工程、cve64560（Zombietick 主线工作区）、docs、tools
│   ├── docs/                     ← INDEX.md 导航 + ReSukiSU 时代 diff 留档
│   └── scripts/ patches/ build.sh
└── ghostlock-deliverables/       ← 成品 APK + GhostLock 应用源码仓库
    ├── GhostLock-v1.2-vivo-neo9-kernelsu-original-arm64-release.apk
    │                              ← GhostLock App v4（**已真机验证**，18,744,522B，sha256 b620cc3a…）
    ├── KSUOneTap-v1.2.0-kernelsu-original.apk
    │                              ← KSUOneTap v1.2.0（原版 KSU 体系，18,001,832B，sha256 035dc423…，**待真机复验**）
    └── ghostlock-app-for-iqoo-neo9/  ← GhostLock App 源码仓库（git；v4 APK 的源头）
```

> 当前固件的镜像提取树（kernel.elf / vendor_root / dlkm 等）在 **`~/Desktop/pixel-ksu-root/PD2338_A_16.2.13.2.W10.V000L1/`**，不在本目录。

## 当前 KSU 体系（2026-10-06 起）

| 组件 | 版本 | 位置 | 校验 |
|---|---|---|---|
| 内核驱动 | 原版 v3.3.0 / KSU_VERSION 32601（=30000+2601 commits，tag 932014ab） | `ReSukiSU-Port/kernel-module/kernelsu-vanilla-197.ko`（assets 内同文件） | sha256 `c2564090…`，vermagic 与设备逐字一致 |
| 管理器 | 官方 KernelSU_v3.3.0-release.apk（versionCode 32601，**必须官方签名**——ko 内嵌签名白名单） | `ReSukiSU-Port/apk/ksuonetap/assets/KernelSU.apk` | sha256 `c197060e…` |
| ksud | 官方 v3.3.0 | `ReSukiSU-Port/apk/ksuonetap/assets/ksud` | sha256 `8614de6c…` |
| 源码 | `~/kernel/KernelSU-upstream`（v3.3.0，完整 .git）；内核树 `~/kernel/vivo-neo9-16`（drivers/kernelsu 符号链接） | `ReSukiSU-Port/source/KernelSU-upstream` -> 同一位置 | `git describe --tags` = v3.3.0 |

驱动重编配方、与 ReSukiSU 时代的差异（无 +700 版本偏移、无 `allow_shell` 参数、init 收脚本、管理器装法）全部记录在
**`ReSukiSU-Port/kernel-module/VERSION.txt`** 与 **`kernel-module/README-kernelsu-vanilla.md`**。

## 两条交付链路（16.2.13.2）

| | GhostLock App v4 | KSUOneTap v1.2.0-kernelsu-original |
|---|---|---|
| 状态 | ✅ 2026-10-05 22:00 真机验证 | ⚠️ 待真机复验（ko/ksud/管理器已验证，变的是编排） |
| 提权 | CVE-2026-43499 LPE（W1→W2→W3），无 Shizuku | 同一条 LPE 链，无 Shizuku |
| KSU 部署 | root 脚本：vrpatch → packet sepolicy → `ksud insmod`（pre-KSU 顺序）；管理器经 PackageInstaller 免 root 唤起安装 | 「一键 Root」→「激活 KSU」两段式；部署顺序同左，daemon seccomp 逃逸通道执行 |
| 安装 | 需授予「安装未知应用」；新旧签名不同需**卸载重装**（新 keystore `~/ghostlock-vanilla.jks`） | 与旧版 KSUOneTap 同签名（`94122eb6…`），可 `adb install -r` 原地升级 |
| 已知约束 | 本内核 KSU+enforcing 断网（vivo netb 过滤器），exploit W1 已置宽容，**保持宽容模式** | 同左 |

**部署要点（两个 App 通用）**：
- 发射必须**锁屏静置**（mcast route 是 3/4 大核双线程竞态，后台负载下命中率 0）
- per-boot：重启后 root/模块全失，重跑「一键 Root」+「激活 KSU」
- 管理器安装不走 exploit 域 `pm install`（"Can't find service: package" 实测证死），只有 App 侧 PackageInstaller 可用

## 版本划分速查（按固件）

| 固件版本 | 状态 | 产物位置 |
|---|---|---|
| 16.2.13.2（当前） | 活跃 | 本目录两份 APK、`ReSukiSU-Port/apk/ksuonetap/assets/PD2338_A_16.2.13.2…/`、镜像提取树在 pixel-ksu-root |
| 15.1.14.7（内核 5.15.178） | 已淘汰（ReSukiSU 时代） | 源码树备份 `~/kernel/kernelsu-src-backups/resukisu-fa8311f6-full`；产物在系统回收站 |
| 14.0.17.2 / 14.0.17.6 | 已淘汰（更早一代） | 仅 `ReSukiSU-Port/test-modules/out/` 下两版 ko 产物保留 |

## 设备侧速查（16.2.13.2）

```bash
# 首选：App 内完成（无需 adb）
#   GhostLock App v4：打开 → 发射 → (自动) pre-KSU 部署 → 系统安装器装 KernelSU 管理器
#   KSUOneTap v1.2.0：一键 Root → 激活 KSU → 系统安装器装 KernelSU 管理器

# 手动链（已有 root shell / exploit daemon 时）：
adb push kernelsu-vanilla-197.ko vrpatch.ko ksud /data/local/tmp/
su -c 'cp /data/local/tmp/ksud /data/adb/ksud && chmod 755 /data/adb/ksud'
su -c '/data/adb/ksud insmod /data/local/tmp/vrpatch.ko'              # 先中和 vr.ko
su -c '/data/adb/ksud insmod /data/local/tmp/kernelsu-vanilla-197.ko' # 后加载原版驱动
# 验证：grep -E 'kernelsu|vrpatch' /proc/modules；管理器应识别内核 32601

# vr.ko 中和回读验证（可选）：
su -c '/data/adb/ksud insmod /data/local/tmp/vrread.ko'   # 期望: 00 00 80 52 c0 03 5f d6
```

## 整理与备份记录

**2026-10-05**（≈43G 进回收站）：ReSukiSU-Port 三份旧固件目录 ≈33G、pixel-ksu-root 的 6 个 .img ≈10G、旧 APK/归档若干。
确认无误后可 `gio trash --empty` + 清空 `pixel-ksu-root/.Trash-0/` 释放空间。

**2026-10-06**（≈900M 进回收站，目录 1.3G→323M）：fix3/fix4 旧 APK、full-kit.zip 等旧交付物、app 仓库 `build/` 437M 中间件、橘子5 内核源码包 197M、`neo9-root/archive/`、`apk/archive/`、197 系 ReSukiSU ko×3 + out/、197 取证包。
**同时完成 ReSukiSU → 原版 KernelSU 切换**（详见上文）。删除均可恢复（`~/.local/share/Trash/files/`）。

**备份位置**：
- ReSukiSU 完整源码树（fa8311f6，含未提交的 ni_syscall 尾扫修复）：`~/kernel/kernelsu-src-backups/resukisu-fa8311f6-full`；diff 另存 `ReSukiSU-Port/docs/resukisu-local-diff-fa8311f6-20261006.patch`
- `Desktop/ReSukiSU-Port-For-IQOO_Neo9` 是指向本目录 ReSukiSU-Port 的符号链接（旧路径保活）
- GhostLock App v4 的 git 仓库内含 4 个待提交改动 + 新增 `assets/ksu_vivo/`（未 commit）

## 文档索引

| 想看什么 | 去哪 |
|---|---|
| 驱动版本状态 / 重编配方 / 原版与 ReSukiSU 差异 | `ReSukiSU-Port/kernel-module/VERSION.txt`、`README-kernelsu-vanilla.md` |
| 16.2.13.2 绑定产物与真机验证记录 | `ReSukiSU-Port/apk/ksuonetap/assets/PD2338_A_16.2.13.2…/SYSTEM.txt` |
| 仓库导航 / 文档地图 | `ReSukiSU-Port/docs/INDEX.md` |
| vr.ko 绕过方案 | `ReSukiSU-Port/neo9-root/docs/VRKO_BYPASS.md` |
| GhostLock App 源码与 v4 改动 | `ghostlock-deliverables/ghostlock-app-for-iqoo-neo9/`（见 git status） |
| 候选 CVE 状态 | `漏洞清单.md`；root 路线综述 `root-research-survey.md` |
