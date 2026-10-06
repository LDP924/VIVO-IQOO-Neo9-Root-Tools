# unpatch.ko — 还原 exploit 对 `cap_bprm` 的内核 patch

**用途**：`exploit_vivo_neo9` 带 `CHEESE_PATCH_CAP=1` 时，会把
`cap_bprm_creds_from_file` 的入口改成 `mov w0,#0; ret` —— 于是**任何 exec 都保留全 caps**。
副作用是软重启时 zygote 带异常 caps 崩溃。本模块把那条指令**写回原样**。

**适用系统版本**：**`PD2338_A_15.1.14.7.W10.V000L1`** —— 除 vermagic 外还硬编码了
`CAP_BPRM_PA`（= stext_pa + 符号偏移 `0x93cdbc`）与原始指令字，换内核版本必须重算。

**设备落点**：`/data/local/tmp/unpatch.ko`
（142,840 字节，打包源 `assets/PD2338_A_15.1.14.7.W10.V000L1/unpatch.ko`）

**什么时候需要它**：走了「提取 root 并部署 KSU」的**完整模式**（默认开 `CHEESE_PATCH_CAP`）
就必须加载它，否则软重启（`ksud soft-reboot` / `stop && start`）会半启动卡死。
「仅提取 Root」模式不 patch cap，也就不需要。

## 文件

| 文件 | 说明 |
|---|---|
| `unpatch.c` | 源码。核心是手动改内核 text 页表权限（绕过 `set_memory_rw` 只支持 vmalloc 的限制），写回原始指令后还原权限 |
| `Makefile` | 外部模块编译（`obj-m`） |
| `build.sh` | 便捷构建（内部就调 Makefile） |

## 关键：两个设备特定常量

源码里 `⚙️ 设备适配` 标注处：

```c
CAP_BPRM_PA        // cap_bprm_creds_from_file 的**物理地址**
                   // = stext_pa + 符号偏移; Neo9: stext_pa=0xa8010000, 偏移 0x93cdbc
CAP_BPRM_ORIGINAL  // 该函数入口的原始 8 字节序言
                   // Neo9: 0xd10243ffd503233f  (bti c; sub sp,sp,#0x90)
```

怎么推：

1. 从目标设备的 `symbols.txt` / `/proc/kallsyms` 取 `cap_bprm_creds_from_file` 地址，
   减去 `_text` 得偏移；
2. `stext_pa + 偏移` = 物理地址（Neo9 无 KASLR，`stext_pa` 固定 `0xa8010000`）；
3. 原始指令从设备内核镜像对应偏移读 8 字节。

> 若目标设备不需要 cap patch（不走 exploit 提权的 caps 模式），本模块可以直接不加载。

## 加载

**必须经 `ksud insmod`**（裸 `insmod` 不做 ksud 的 UAPI 校验与初始化，不能用）：

```sh
# KSU 起来之后（此时 su_ksu 才存在）
adb shell "$DEV/su_ksu -c '$DEV/ksud insmod $DEV/unpatch.ko'"
```

顺序上有依赖：**先**加载 `kernelsu-vivo.ko`（用 `u0 ksud insmod ... allow_shell=1`），
**再**加载 `unpatch.ko` / `vrpatch.ko`（用 `su_ksu -c 'ksud insmod ...'`）。
原因见 `../kernelsu-vivo.ko/README.md` 里的"为什么分两种提权方式"。

## 许可

本工程自研内核模块。

## 权威位置

改代码请改 `test-modules/unpatch/unpatch.c`，本目录是镜像（`./build.sh sync` 校验）。
