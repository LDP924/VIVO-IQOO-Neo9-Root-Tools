# vivo 内核源码集成修改说明 (iQOO Neo9 PD2338C / 5.15.178)

> **编译前先确认驱动版本码**：`KSU_VERSION = 30000 + git 提交数 + 700`，只由提交数决定，
> 且**无法从 .ko 反查**。当前锁定 `35184`（`main @ fa8311f6`，提交数 4484）。
> 核对：`bash scripts/build_ksu_module.sh --version-only`。详见 [RESUKISU_VERSION.md](RESUKISU_VERSION.md)。
>
> 内核集成方式（下方 §1 的 4 处修改）自 v4.2.0-rc1 起**未发生变化**，升级源码后依然适用。

> 目标内核源码树: 仓库根 `android_15.0_kernel_SM8550.tar.gz`（197MB / 7.4 万文件）→
>   解包到 `$HOME/kernel/vivo-neo9-android15`（1.3GB）+ 打 `patches/kernel-integration.patch`
> 构建环境: Linux 主机 gcc + **clang 18**（`$HOME/toolchains/ndk-r27`，NDK r27 自带 18.0.1）
>   （早期版本在 WSL Ubuntu-24.04 `/root/kernel/source` + clang 18.1.3 上编过；路径现已参数化）

## 1. 必需修改 (4 处)

### 1.1 vermagic 标识 (设备要求 "vivo" flag)

`include/linux/vermagic.h` 第 46 行, 在 `MODULE_VERMAGIC_MODVERSIONS` 后追加 `"vivo "`:

```c
#define VERMAGIC_STRING 						\
	UTS_RELEASE " "							\
	MODULE_VERMAGIC_SMP MODULE_VERMAGIC_PREEMPT 			\
	MODULE_VERMAGIC_MODULE_UNLOAD MODULE_VERMAGIC_MODVERSIONS "vivo "	\
	MODULE_ARCH_VERMAGIC						\
	MODULE_RANDSTRUCT_PLUGIN
```

> 设备 `vermagic`: `5.15.178-gaacdc35637c4-dirty SMP preempt mod_unload modversions vivo aarch64`
> "vivo" 不在公开发布源码中, 必须手动加入, 否则模块加载报 vermagic 不匹配。

### 1.2 Kconfig 集成 (否则 CONFIG_KSU 会被 olddefconfig 剥离)

`drivers/Kconfig` 第 239 行追加:

```kconfig
source "drivers/kernelsu/Kconfig"
```

`drivers/Makefile` 第 191 行追加:

```makefile
obj-$(CONFIG_KSU) += kernelsu/
```

`drivers/kernelsu` 符号链接指向 ReSukiSU 内核源码:

```sh
ln -sf <ReSukiSU路径>/kernel drivers/kernelsu
```

> ⚠️ 仅用 `scripts/config --module KSU` 会被 olddefconfig 撤销,
> 必须先把 Kconfig source 集成进 drivers/, 再启用 CONFIG_KSU。

### 1.3 构建配置 (.config, 已存档为 kernel-module/vivo.config)

```kconfig
CONFIG_LOCALVERSION="-gaacdc35637c4-dirty"   # 手动设置 (tarball 无 .git, LOCALVERSION_AUTO 不可用)
CONFIG_LTO_CLANG_FULL=y                       # ⚠️ 必须与设备一致!
CONFIG_CFI_CLANG=y                            # ⚠️ 必须与设备一致!
CONFIG_CFI_CLANG_SHADOW=y                     # 自动选择
CONFIG_MODVERSIONS=y
CONFIG_KSU=m
CONFIG_KSU_TRACEPOINT_HOOK=y
# CONFIG_KSU_DEBUG is not set
```

> **⚠️ 关键教训**: LTO/CFI 决定 `struct module` 布局!
> 设备内核 `CONFIG_CFI_CLANG=y` → struct module 含 8 字节 `cfi_check` 字段,
> `init` 成员偏移 0x178。若构建时关掉 LTO (LTO_NONE) 连带 CFI 被关,
> 模块的 init 偏移变 0x170, 内核读错偏移 → **模块 init 从不执行** (Live 但完全失效)。
> 必须 LTO_CLANG_FULL + CFI_CLANG 与设备一致重建。

### 1.4 Windows 拷贝后的符号链接修复

从 Windows 解压 tarball 会破坏符号链接 (变成 10 字节文本文件):

```sh
cd <kernel源码>
rm -rf include/uapi && ln -s ../../uapi include/uapi    # 若被破坏
# include/asm 同理: ln -s ../arch/arm64/include/asm include/asm
```

## 2. 模块构建命令 (LTO+CFI, 见 scripts/build_ksu_lto2.sh)

```sh
export ARCH=arm64 LLVM=1 LLVM_IAS=1
cd <kernel源码>
# 1) 配置 (vivo.config 为基础, 启用 KSU + LTO_CLANG_FULL + CFI_CLANG)
# 2) 关键: touch .config && make syncconfig   # 刷新 auto.conf (LTO 标志)
# 3) SELinux 头:
mkdir -p security/selinux/include/generated
scripts/selinux/genheaders/genheaders \
    security/selinux/include/generated/flask.h \
    security/selinux/include/generated/av_permissions.h
# 4) 编译:
make ARCH=arm64 LLVM=1 LLVM_IAS=1 \
    KCFLAGS="-I<kernel>/security/selinux/include/generated" \
    M=<ReSukiSU>/kernel src=<ReSukiSU>/kernel modules -j24
```

产物: `<ReSukiSU>/kernel/kernelsu.ko` (12.3MB, LTO+CFI 版)
校验: `.rela.gnu.linkonce.this_module` 的 `init_module` 重定位必须在 **0x178**
(用 `readelf -r kernelsu.ko | grep this_module` 检查)。

## 3. 设备加载前提

| 项目 | 要求 | 原因 |
|---|---|---|
| kptr_restrict | 开机后 ≤5 分钟内加载 (值 0) | vivo 几分钟后设 2, ksud 读不到 kallsyms 地址 |
| ksud 运行身份 | 真实 uid 0 (用 u0) | vivo 对 /proc/kallsyms 加 uid 检查, 仅 caps 不够 |
| 加载参数 | `allow_shell=1` | 允许 uid 2000 (adb shell) 直接 su |
| 禁止操作 | 写 tracefs kprobe_events | 设备会立即重启 (vivo 防护) |
