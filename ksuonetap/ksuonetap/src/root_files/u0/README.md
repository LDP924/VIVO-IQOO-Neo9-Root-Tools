# u0 — setuid(0) 提权辅助

**用途**：在「euid=2000 + 全部 caps」的环境里把进程真正提升到 `uid=0`。
它只做三件事：`setgid(0)` → `setuid(0)` → `execv(命令)`。

**不绑系统版本**：只做 `setgid(0)` + `setuid(0)` + `execv`，不依赖任何内核偏移或内核版本，
换系统版本不用重做 —— 所以留在 `assets/` 顶层（`assets/u0`），不放进版本目录。

**设备落点**：`/data/local/tmp/u0`（688KB，`assets/u0`）

## 文件

| 文件 | 说明 |
|---|---|
| `u0.c` | 唯一源码（544 字节） |

源码全文（便于核对，实际文件见 `u0.c`）：

```c
int main(int argc, char** argv) {
    if (setgid(0) != 0) { perror("setgid"); return 1; }
    if (setuid(0) != 0) { perror("setuid"); return 1; }
    if (argc < 2) {
        char* args[] = {"/system/bin/sh", NULL};
        execv("/system/bin/sh", args);     // 无参 → 起一个 root shell
        return 1;
    }
    execv(argv[1], &argv[1]);              // 有参 → 直接执行（argv[1] 起全是命令）
    return 1;
}
```

## 两个必须注意的行为

1. **不搜 PATH**，`argv[1]` 必须是绝对路径 —— 所以调用要写
   `u0 /system/bin/sh -c '...'` 或 `u0 /data/local/tmp/ksud insmod ...`。
2. **不做词法拆分**：它把 `argv[1..]` 原样交给 `execv`。要跑管道 / 重定向 / 多命令，
   必须自己包一层 `sh -c`：

   ```sh
   u0 /system/bin/sh -c 'cat /proc/modules | grep kernelsu'
   ```

## 谁在用它

| 场景 | 命令 |
|---|---|
| **激活 KSU**（App 与手动流程） | `u0 ksud insmod kernelsu-vivo.ko allow_shell=1`（经 rootd 队列投递） |
| 手动调试 | `u0 /system/bin/sh -c '<任意命令>'` |

> 注：`exploit_vivo_neo9` 的**内置 su** 已经覆盖了绝大多数场景（`su -c '...'` 直接
> `uid=0`），`u0` 保留是因为 KSU 驱动加载那一步需要一个"已提权且能 exec 绝对路径"的
> 小工具 —— 那时 KSU 还没起来，`su_ksu` 也不存在。

## 构建

`u0.c` 是自包含的单文件程序，本仓库没有为它单独留构建脚本（历史上是一次性交叉编译）。
要点是**必须静态链接** —— 实测产物 `704,128` 字节、**无动态段**（`readelf -d` 报
"There is no dynamic section"），而 `exploit_vivo_neo9` 是动态 PIE（`NEEDED libc.so /
libdl.so`）。这个差别有意义：`u0` 要在 KSU 起来之前的早期环境里执行，静态省掉对运行时
库的依赖。

```sh
# 任选一个 aarch64 交叉工具链，关键是加 -static
$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android21-clang \
    -O2 -static -o u0 u0.c
file u0                     # 期望: ELF 64-bit LSB executable, ARM aarch64, statically linked
readelf -d u0               # 期望: There is no dynamic section in this file
```

> 现有那份产物的 `file` 描述是 `for GNU/Linux 3.7.0` + 带 BuildID，看起来是
> aarch64-linux-gnu 系工具链编的；**具体用的是哪个工具链本仓库没有记录**，
> 但静态属性是可验证的（上面两条命令），重新编译只要保持静态即可。

## 许可

本工程自有代码。

## 权威位置

改代码请改 `neo9-root/client/u0.c`，本目录是镜像（`./build.sh sync` 校验）。
