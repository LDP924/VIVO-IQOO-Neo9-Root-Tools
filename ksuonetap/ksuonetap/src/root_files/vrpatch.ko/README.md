# vrpatch.ko — 中和 vivo `vr.ko` 的 root 检测

**用途**：vivo 的 `vr.ko`（反 root 检测模块）在 `avc_has_perm` / `do_init_module` 等处
挂 kprobe，判定条件是 **`current->cred->euid == 0` 且 SELinux 域不是白名单 `vrp`**，
命中就 `force_sig(SIGABRT)`。结果是软重启时 `zygote` / `netd`（uid 0）被误杀，系统
半启动卡死。

本模块把这个检测函数**直接改成 `mov w0,#0; ret`**（恒返回"无异常"），
从而不再击杀任何 uid 0 进程。

**适用系统版本**：**`PD2338_A_15.1.14.7.W10.V000L1`** —— 除 vermagic 外还依赖 vr.ko
内部检测函数偏移 `VR_DETECT_OFFSET=0x2ecc`；vr.ko 随系统版本变就必须重推。

**设备落点**：`/data/local/tmp/vrpatch.ko`
（240,784 字节，打包源 `assets/PD2338_A_15.1.14.7.W10.V000L1/vrpatch.ko`）

## 文件

| 文件 | 说明 |
|---|---|
| `vrpatch.c` | 源码。`find_module("vr")` 拿模块 text 基址 → 加偏移定位检测函数 → `set_memory_rw`（模块内存是 vmalloc，可以用这个 API）→ 打补丁 → 恢复 RO + flush icache |
| `Makefile` | 外部模块编译 |
| `build.sh` | 便捷构建 |

## 关键：一个设备特定常量

```c
VR_DETECT_OFFSET   // vr.ko 的 .text 内，检测函数的偏移; Neo9: 0x2ecc
```

这个 `0x2ecc` 是逆向出来的，分析过程与依据在：

- `neo9-root/docs/vrko_static_analysis.md` —— vr.ko 静态逆向（符号、混淆、SELinux 自定义类）
- `neo9-root/docs/VRKO_BYPASS.md` —— 绕过方案总纲（检测条件 / 三级绕过 / 实测数据）
- `neo9-root/docs/DEBUG_RECORD.md` §12 —— 检测函数与 euid 语义

重推偏移可用 `neo9-root/tools/disasm_vrko*.py`（输入 `vr.ko` 不在仓库）。

## 为什么它比"换 SELinux 域"更值

本工程的临时 root 把提权后的域改成 `u:r:shell:s0`（因为 exploit 默认落的 `u:r:vrp:s0`
权限太窄，连 servicemanager 都访问不到，Axeron 之类的工具起不来）。代价就是**放弃了
vr.ko 的白名单**。`vrpatch.ko` 把检测函数打掉之后，这个代价就没了 —— 域可以按功能需要
随便选，不再受 vr.ko 的白名单约束。

## 加载

```sh
adb shell "$DEV/su_ksu -c '$DEV/ksud insmod $DEV/vrpatch.ko'"
```

**软重启不重载内核模块**，所以 patch 是持久生效的；**硬重启后需要重新部署**。

## 许可

本工程自研内核模块。

## 权威位置

改代码请改 `test-modules/vrpatch/vrpatch.c`，本目录是镜像（`./build.sh sync` 校验）。
