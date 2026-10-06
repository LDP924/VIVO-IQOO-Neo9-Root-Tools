# su_ksu — KernelSU 的 su 客户端

**用途**：KSU 已经加载时，用它以 root 执行命令：

```sh
adb shell "/data/local/tmp/su_ksu -c 'id'"     # → uid=0(root) u:r:ksu:s0
```

请求经内核的 `sucompat`（execve hook）授予 root：本进程只要以允许的 uid 执行
`/system/bin/su`，内核就把执行重定向到 `/data/adb/ksud` 并完成提权。

**不绑系统版本**：它绑的是 **KSU 版本与 UAPI**（不是系统内核版本），换系统版本不影响，
所以留在 `assets/` 顶层。

**设备落点**：`/data/local/tmp/su_ksu`（1,287,056 字节，`assets/su_ksu`）

## 文件

| 文件 | 说明 |
|---|---|
| `su_patched.c` | **本工程的适配版源码**（1417 字节）—— 这就是本目录提供的"源码" |

基底是 ReSukiSU / KernelSU 传统的 `su` 客户端（上游 `userspace/` 只有 `ksud` 与
`ksuinit`，这个 `su` 客户端是小体积独立程序）。本工程改了三处：

| 改动 | 原因 |
|---|---|
| **去掉 legacy `prctl` 门禁** | ReSukiSU 内核没有 prctl supercall，老式的
`prctl(KERNEL_SU_OPTION, ...)` 握手会失败；现在直接靠 execve hook |
| `argv[0] = "/system/bin/su"` + `execve("/system/bin/su", ...)` | 触发内核 sucompat hook 的入口 |
| 给 stdin 的 tty 打上 `u:object_r:devpts:s0` | 交互式 `su` 时终端标签正确，作业控制/回显正常 |

源码全文很短，核心就三行：`setxattr(tty)` → `argv[0] = "/system/bin/su"` →
`execve("/system/bin/su", argv, envp)`。见 `su_patched.c`。

## 构建

单文件 C 程序，交叉编译即可（历史上用 NDK）：

```sh
$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android21-clang \
    -O2 -static -o su_ksu su_patched.c
```

> 注意与 `exploit_vivo_neo9` 的区别：那个是动态 PIE（`NEEDED libc.so/libdl.so`），
> 这个跟 `u0` 一样适合静态编 —— 它要在 KSU 刚起来、环境还最简单的时刻执行。

## 什么时候用它、什么时候不用

**提权客户端的选取规则（全工程统一）**：

| 环境 | 用哪个 |
|---|---|
| **KSU 在线** | `su_ksu -c '...'` |
| **只有临时 root**（未加载 KSU） | `exploit` 内置的 `$DEV/su` —— `su_ksu` **此时根本不存在** |

这条规则在 App 里是 `Terminal`/`Deployer` 选的（`ksuUsable()` 判定），
"清理并重启"也会按它挑客户端 —— 早期写死 `su_ksu` 导致"仅提取 root"模式下按钮必然失败。

例外：「软重启」需要 `ksud`，所以它单独门控在"KSU 在线"上。

## 许可

基底为上游 ReSukiSU / KernelSU 的 `su` 客户端；`su_patched.c` 是本工程的适配修改。

## 权威位置

改代码请改 `userspace/su_patched.c`，本目录是镜像（`./build.sh sync` 校验）。
