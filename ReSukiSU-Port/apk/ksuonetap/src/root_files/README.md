# root_files — App 推送到设备的每个文件的源码

本目录回答一个问题：**App 会往设备上推哪些文件，每个文件的源码在哪、怎么重新编出来。**

> **与 `assets/` 的分工**
> - `apk/ksuonetap/assets/` = **二进制**，打包进 APK，部署时推送到 `/data/local/tmp/`
> - `apk/ksuonetap/src/root_files/` = 这些二进制的**源码与构建说明**（本目录）
>
> 本目录**不参与 APK 打包** —— `build_ksuonetap.sh` 只收集 `src/**/*.kt`，
> 所以这里放 `.c` / `.h` / `.s` / 配置都不会影响构建。

## 清单

**适用系统版本：`PD2338_A_15.1.14.7.W10.V000L1`**（iQOO Neo9 `PD2338` / `V2338A`，
OriginOS 5 / Android 15 / 内核 `5.15.178-gaacdc35637c4-dirty`）。
带 🅥 的是**系统版本绑定产物**，在 `assets/<系统版本>/` 下；不带的是通用件，留在 `assets/` 顶层。

| 资产 | 设备落点 | 二进制来源（打包用） | 源码在哪 | 本目录 |
|---|---|---|---|---|
| 🅥 `exploit_vivo_neo9` | `/data/local/tmp/` | `assets/<系统版本>/exploit_vivo_neo9` ← `neo9-root/exploit/out/<系统版本>/exploit_vivo_neo9_stable_su_ndk13` | `neo9-root/exploit/exploit_vivo.c` | [`exploit_vivo_neo9/`](exploit_vivo_neo9/) |
| 🅥 `kernelsu-vivo.ko` | `/data/local/tmp/` | `assets/<系统版本>/kernelsu-vivo.ko` ← `kernel-module/` | **`source/resukisu/kernel/`**（仓库内，未改动） + vivo 内核集成补丁 | [`kernelsu-vivo.ko/`](kernelsu-vivo.ko/) |
| `ksud` | `/data/local/tmp/` | `assets/ksud` ← manager APK 里的 `lib/arm64-v8a/libksud.so` | **上游 ReSukiSU 仓库**（本仓库不含） | [`ksud/`](ksud/) |
| `su_ksu` | `/data/local/tmp/` | `assets/su_ksu` ← `userspace/` | ReSukiSU 的 `userspace/su` 的**适配版** → `userspace/su_patched.c` | [`su_ksu/`](su_ksu/) |
| `u0` | `/data/local/tmp/` | `assets/u0` ← `neo9-root/client/` | `neo9-root/client/u0.c` | [`u0/`](u0/) |
| 🅥 `unpatch.ko` | `/data/local/tmp/` | `assets/<系统版本>/unpatch.ko` ← `test-modules/unpatch/` | `test-modules/unpatch/unpatch.c` | [`unpatch.ko/`](unpatch.ko/) |
| 🅥 `vrpatch.ko` | `/data/local/tmp/` | `assets/<系统版本>/vrpatch.ko` ← `test-modules/vrpatch/` | `test-modules/vrpatch/vrpatch.c` | [`vrpatch.ko/`](vrpatch.ko/) |

**为什么只有 4 个是版本绑定**（每一条都是"换版本会直接内核 panic 或加载失败"）：

| 产物 | 绑定的具体内容 |
|---|---|
| `exploit_vivo_neo9` | `KERNEL_PHYS_BASE=0xa8000000` / `CHEESE_STEXT_PA=0xa8010000` / `gPhyAddrs[]` / `kFakeGpuAddr` / spray 布局 |
| `kernelsu-vivo.ko` | vermagic + `struct module` 布局（LTO/CFI/BTF）+ `KSU_VERSION` |
| `unpatch.ko` | 同上 + `CAP_BPRM_PA`（= stext_pa + 符号偏移 `0x93cdbc`）+ 原始指令字 |
| `vrpatch.ko` | 同上 + vr.ko 内检测函数偏移 `VR_DETECT_OFFSET=0x2ecc` |

通用件不绑系统版本：`u0` 只是 `setgid(0)+setuid(0)+execv`；`su_ksu` / `ksud` 绑的是
**KSU 版本与 UAPI**（换系统版本不影响）；manager 是通用 APK。版本标注与各产物 md5 写在
`assets/<系统版本>/SYSTEM.txt`，`./build.sh sync` 会逐项核对。

推送时机：完整模式推 8 个（含 `resukisu-manager.apk` 与上面 7 个）；「仅提取 Root」模式
只推 `exploit_vivo_neo9`（其余会清掉，见 `DeployScript.ASSETS_ROOT_ONLY`）。
设备侧的落点路径**不含版本目录** —— App 取件时按版本目录读，推过去仍是 `/data/local/tmp/<名>`，
所以设备侧命令与历史日志逐字不变。

## ⚠️ 本目录是**镜像**，不是权威副本

权威源码在各自的原始目录（表里"源码在哪"那一列）。本目录的文件是**副本**，为的是
"App 工程自包含：拿到 App 目录就能看清每个部署物的来龙去脉"。

复制会造成漂移风险，所以：

```sh
./build.sh sync      # 校验 assets 二进制 与 本目录源码镜像 是否都与原处一致
```

改代码请改**原处**，然后重跑 `./build.sh sync`（它会告诉你哪份该同步）。
`scripts/check-assets-sync.sh` 里 `MIRROR` 段定义了本目录的镜像清单。

## 版权与血统

各文件的版权归各自作者：`source/resukisu/` 与 ksud 属 ReSukiSU 上游；`unpatch/vrpatch`
与 `u0` 为本工程自研；`neo9-root/exploit/exploit_vivo.c` 衍自**未附 LICENSE** 的上游研究
项目（zhuowei/cheese、sarabpal-dev/cheese-cake），血统说明保留在该文件头。
本目录仅供安全研究用途，许可不再逐项抄录。

## 三个常见误区

1. **"内核源码不在仓库所以没法重编驱动"** —— 不对。驱动源码 `source/resukisu/kernel/`
   **就在仓库里**（1.1MB / 110 文件，`git status` 干净）。不在仓库的是**编译宿主
   （vivo 内核树）**，那是另一回事，见 `kernelsu-vivo.ko/README.md`。
2. **"`.ko` 编出来就能用"** —— 内核模块必须与目标内核的 LTO/CFI 设置一致，否则
   `struct module` 布局不同、init 偏移错位，模块会"Live 但完全失效"。
3. **"改了版本号要重编"** —— 看是哪种改动。只对齐**版本号**不需要重编：
   `scripts/patch_ko_version.py` 能等长改写 `.ko` 里的版本码（历史上 35077 → 35140 → 35179
   就是这么对齐的）；但要让**功能改动**真正进到 `.ko` 里就必须重编 ——
   **35184 就是这么来的：真编，不是改写**（`source/` 更新 + 官方内核源码包重编）。
