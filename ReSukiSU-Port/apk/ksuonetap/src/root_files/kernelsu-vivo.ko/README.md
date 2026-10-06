# kernelsu-vivo.ko — KernelSU (ReSukiSU) 驱动

**用途**：KSU 的内核模块（LKM）。加载后提供 `supercall`（管理器的 ioctl 入口）、
`sucompat`（execve hook 提权）、模块管理。它是 App 一键流程的终点。

**适用系统版本**：**`PD2338_A_15.1.14.7.W10.V000L1`** —— vermagic 与 `struct module`
布局（LTO/CFI/BTF）都绑内核，换版本必须重编。

**设备落点**：`/data/local/tmp/kernelsu-vivo.ko`（**13,270,840 字节**；
打包源 `assets/PD2338_A_15.1.14.7.W10.V000L1/kernelsu-vivo.ko`）

## 源码在哪

| 部分 | 位置 | 状态 |
|---|---|---|
| **驱动源码** | **`source/resukisu/kernel/`**（本仓库内，1.1MB / 110 文件） | `git status` **干净** = 与上游 `fa8311f6` 完全一致，**本工程未改动** |
| 版本 = 提交数 | `source/resukisu/.git`（**不能删**） | `rev-list --count HEAD` = **4484** → `KSU_VERSION = 30000 + 4484 + 700 = 35184` |
| 编译宿主 | **vivo 内核源码树** —— 包就在**仓库根**：`android_15.0_kernel_SM8550.tar.gz` | 解包到 `$HOME/kernel/vivo-neo9-android15` 后打 3 处集成（见下） |

> **驱动源码在仓库里，编译宿主现在也在仓库里**（197MB 的官方开源包）。
> 内核模块必须在目标内核树内编译（要用它的头文件、`include/config/auto.conf`、
> `scripts/selinux/genheaders`），所以"没法重编"现在只差**解包 + 打补丁**这一步。

## 当前这份 .ko：真重编（2026-09-27）

| 项 | 值 |
|---|---|
| KSU_VERSION | **35184**（`KSU_VERSION_FULL = v4.2.0-rc3-fa8311f6@ReSukiSU`） |
| 体积 / 指纹 | 13,270,840 B / md5 `e38573bc0793e1d98651b79eda7ca207` |
| vermagic | `5.15.178-gaacdc35637c4-dirty SMP preempt mod_unload modversions vivo aarch64`（与设备逐字一致） |
| 布局指纹 | `init_module` @**0x178** / `cleanup_module` @**0x378**（与设备在用那份一致） |

它包含 `0e469895..fa8311f6` 的全部改动（驱动侧是 `kernel/manager/pkg_observer.c` 的
`track_throne` 同步修复）。**不再有"版本号改写过、代码是旧的"这个问题** ——
那段历史留在 `docs/RESUKISU_VERSION.md §7`，重编过程与四个坑在 §8。

## 本目录文件

| 文件 | 说明 |
|---|---|
| `vivo.config` | **设备内核配置**（从设备 `/proc/config.gz` 取得，216KB）。构建基础 |
| `build_ksu_module.sh` | ★ **可移植重编脚本**（参数化 + 前置检查 + 工具链预检 + 版本校验 + 布局校验） |
| `build_ksu_lto2.sh` | 最初那份 WSL 专用脚本（路径写死 `/root/kernel/source`），保留作对照 |
| `patch_ko_version.py` | 版本码改写工具（不重编，见 [B2]） |
| `kernel-integration.patch` | **对 vivo 内核的 3 处源码修改**（"部分修改过的内核源代码"的可交付形式） |
| `extract_integration.sh` | 从一份已有内核树里把集成点**提取出来核对**（grep + ls，只读） |

## [A] 前置：解包内核树 + 打集成

```sh
# 1) 解包（约 1.3GB / 7.4 万文件；别放 /tmp —— 那可能是 10MB tmpfs）
mkdir -p ~/kernel/vivo-neo9-android15
tar xzf android_15.0_kernel_SM8550.tar.gz -C ~/kernel/vivo-neo9-android15

# 2) 集成（3 处源码 + 1 个符号链接）
cd ~/kernel/vivo-neo9-android15
patch -p1 < <repo>/patches/kernel-integration.patch
ln -sfn <repo>/source/resukisu/kernel drivers/kernelsu

# 3) 配置：设备配置打底，改 4 项
cp <repo>/kernel-module/vivo.config .config
scripts/config --file .config --set-str LOCALVERSION "-gaacdc35637c4-dirty" \
               --module KSU --enable KSU_TRACEPOINT_HOOK --disable KSU_DEBUG
export PATH=$HOME/toolchains/ndk-r27/android-ndk-r27/toolchains/llvm/prebuilt/linux-x86_64/bin:$PATH
make ARCH=arm64 LLVM=1 LLVM_IAS=1 CROSS_COMPILE=aarch64-linux-gnu- \
     HOSTCC=gcc HOSTCXX=g++ HOSTAR=ar HOSTLD=ld olddefconfig
```

> ⚠️ **`ARCH=arm64` 不能漏**。漏了会退回宿主 x86 的 Kconfig，把 `ARM64_PAN` / `ARM64_MTE` /
> `KASAN_HW_TAGS` 全判为无效项丢掉 → `HAS_LTO_CLANG` 不成立 → **LTO/CFI 静默关掉** →
> 模块 `init` 偏移错位、加载后"Live 但 init 从不执行"。这是 2026-09-27 实际踩过的坑
> （`docs/RESUKISU_VERSION.md §8.1`）。`olddefconfig` 之后自查：
> `grep -E 'LTO_CLANG_FULL|CFI_CLANG|KASAN_HW_TAGS|DEBUG_INFO_BTF_MODULES' .config` 应全为 `=y`。

## [B] 两条"更新"路径

### B1. 重编（唯一能真正升级代码的路）

```sh
export PATH=$HOME/toolchains/ndk-r27/android-ndk-r27/toolchains/llvm/prebuilt/linux-x86_64/bin:$PATH
bash scripts/build_ksu_module.sh        # 自动探测内核树；KERNEL_TREE= 可显式指定
```

脚本依次做：前置检查（clang 能否编 CFI+LTO）→ `olddefconfig` + `syncconfig` →
`prepare modules_prepare` → selinux headers → 清旧 obj → 编译 → 三步验收：

| 校验 | 期望 |
|---|---|
| `[6]` vermagic | `5.15.178-gaacdc35637c4-dirty SMP preempt mod_unload modversions vivo aarch64` |
| `[6b]` 布局 | `init_module` @0x178、`cleanup_module` @0x378；不符直接报错 |
| `[7]` 版本 | Kbuild 打印的 `version code:` == `EXPECT_VERSION`（默认 35184） |

然后替换两份并跑同步闸门：

```sh
cp kernel-module/kernelsu-vivo.ko \
   apk/ksuonetap/assets/PD2338_A_15.1.14.7.W10.V000L1/kernelsu-vivo.ko
# 并同步该目录 SYSTEM.txt 里的 md5.kernelsu-vivo.ko（否则 sync 会报）
./build.sh sync
```

> 换成**别的系统版本**时不要覆盖这个目录 —— 那会让目录名与产物不再对应。
> 新建 `assets/<新版本 Build.DISPLAY>/` 并把重编产物放进去，再在
> `DeviceGate.ADAPTED_BUILDS` 里加一条。

### B2. 只改版本码（不重编）

适用：只需要让版本号对齐，或手上只有一份 vermagic 正确的 ko 而不想重建编译环境。

```sh
python3 scripts/patch_ko_version.py <ko> --to 35184 --dry-run
python3 scripts/patch_ko_version.py <ko> --to 35184 --out /tmp/new.ko
```

原理：数字码编译成 `mov wN, #<版本码>` 立即数（`.text` / `.init.text` 共 3 处），版本名是
`.rodata` 里的明文串（`v4.2.0-rc3-fa8311f6@ReSukiSU`）—— 都是纯数据，等长替换不动任何逻辑，
文件大小与段表完全不变。

**反面**：要修 bug / 要新功能 / 要跟随上游安全修复 → 只能走 B1。

## 为什么部署时它排在最前面

App 的部署顺序是：`u0 ksud insmod kernelsu-vivo.ko allow_shell=1` → 等 `su_ksu` 可用 →
再 `su_ksu -c 'ksud insmod unpatch.ko / vrpatch.ko'`。

分两种提权方式的原因：加载驱动时 KSU 还没起来、`su_ksu` 不存在，只能用 `u0`；
驱动起来后 `su_ksu` 才有意义。**模块一律经 `ksud insmod`** —— 裸 `insmod` 不做 ksud 的
UAPI 校验与初始化，不能用。

## 许可

上游 ReSukiSU（`source/resukisu/`）；版权归上游作者。
`vivo.config` 来自设备（vivo 内核配置）；`kernel-integration.patch` 是对 vivo 内核源码的
修改（内核自身 GPL-2.0-only）。

## 权威位置

`vivo.config` 的权威副本是 `kernel-module/vivo.config`；脚本在 `scripts/`；补丁在 `patches/`。
本目录是镜像（`./build.sh sync` 校验）。
