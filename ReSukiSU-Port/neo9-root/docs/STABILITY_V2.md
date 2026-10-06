# 临时 Root 稳定性层 v2 (STAB) — iQOO Neo9 PD2338C

> 2026-09-25。对象：`neo9-root/exploit/exploit_vivo.c`（CVE-2025-21479 / Adreno SDS GPU 物理读写）。
> 目标：把 X Fold+ 那篇 `__do_sys_capset` patch 方案的三级校验、残留救援、fork 消除经验，
> 移植到 Neo9 现有的 vhangup-stub + caps-root 流程上。
>
> **硬约束（本轮的边界）：不改动任何漏洞命中相关地址/偏移。**
>
> 配套文档：[`SU_BUILTIN.md`](SU_BUILTIN.md) —— 内置 su（socket 命令服务，不再需要 rootc + rootd）。

---

## 0. 一句话结论

漏洞命中链路（spray 布局、候选集、fake TT0、stext/符号偏移）**一个字节都没动**；
新增的是一层"写-校验 + 前置校验 + 残留救援 + fork 消除"的防护，作用是把
**"patch 没落地却继续往下跑" 和 "fork 后拿失效 GPU context 灌命令" 这两类必然 panic 的路径
变成显式失败/降级**。

---

## 1. 地址不变清单（逐项核对）

| 项目 | 值 | 本轮是否改动 |
|---|---|---|
| `gPhyAddrs[]` 候选集 | 44 项（0xfebeb000 … 0xfc400000） | ❌ 未动 |
| `KERNEL_PHYS_BASE` | `0xa8000000` | ❌ 未动 |
| `kFakeGpuAddr` | `0x40403000` | ❌ 未动 |
| spray 布局 | `NPBUFS=384` × 16MB = 6GB，页内 [1][2][4] 偏移 | ❌ 未动 |
| `CHEESE_STEXT_PA` 默认 | `0xa8010000` | ❌ 未动 |
| `NEO9_VHANGUP_OFFSET` | `0x5dc898` | ❌ 未动 |
| `NEO9_PREP_KRED_VA` / `NEO9_COMMIT_CREDS_VA` | `…0081ae31c` / `…0081aef14` | ❌ 未动 |
| cap_bprm 偏移 | `+0x93cdbc` | ❌ 未动 |
| kptr_restrict 偏移 | `+0x2d6dd14` | ❌ 未动（只改写入宽度，见 §3.6）|
| kprobe_dispatcher 偏移 | `+0x37cecc` | ❌ 未动 |
| `GPU_MARKER_OFFSET` / payload / marker 协议 | `0x100` / `0x8000` | ❌ 未动 |

---

## 2. 与 X Fold+ 文章的逐条对照

| X Fold+ 文章的失效点 | Neo9 v1 是否同样存在 | 本轮实现 |
|---|---|---|
| 失败一：CPU 经窗口自读自证，"日志全绿、DRAM 纹丝未动" | ✅ 存在：`gpu_pwrite_u64_wait` 只等 marker，**从不读回**；`write_to_page_cache_verified` 用 CPU 别名读回自证 | `gpu_pwrite_u64_verified()`：marker 之后 **GPU 读回权威校验** + 重写（默认 3 次）；读回不可用才降级 |
| 失败二：`dc cvau` 只清到 PoU，GPU(PoC) 看不到 | ✅ 存在：`write_to_page_cache` 用 `dc cvau` | 改 `dc cvac`（clean to PoC），保留 `ic ivau` |
| 失败四：fork 破坏一切（COW 毁窗口 / put_page 释放父页表页 / 子进程退出杀 GPU 通道） | ✅ 存在：v1 在 patch 后 `fork()` 出 root shell；daemon 也靠 fork | 默认 **单进程提权**（不 fork）；fork 前落 **GPU 硬闸门**；提权后 **显式释放 GPU** |
| fork 后仍灌命令 → PANIC（硬闸门缺失） | ✅ 存在：`kernel_pwrite_u64` / `DoWrite` 无条件发命令 | `g_gpu_disabled` 在 `DoWrite` / `ReadPhys32` / `ReadPhys64` / `kernel_pread_u64` / `kernel_pwrite_u64` / `gpu_pwrite_u64_wait` 全部短路 |
| 残留救援：`.text` 里是自己的 shellcode 才敢动，否则拒绝 | ⚠️ 部分：v1 无条件重写，且**完全不知道**目标区是什么 | `stab_vhangup_stub_state()` 四态判定 + sidecar 原始值备份 |
| 前置安全校验：PA 范围、首指令、`0x0/0xFFFFFFFF` 拒绝 | ❌ 缺失：v1 直接按硬编码偏移盲写 `.text` | `prepatch_guard()`：地址窗口 + 对齐 + 首指令 + 读 fault 特征，四道关 |
| 信号应急还原 | ❌ 缺失 | `stab_install_handlers()`，在"已 patch 但 root 未建立"窗口内登记 `cap_bprm/kptr/kprobe` |
| fast_rw 拆除校验（PFN 比较、4 次重试） | ❌ 缺失：`dirtypt_teardown` 用无返回值的 `kernel_pwrite_u64` | `stab_pte_pfn_restored()` + 4 次重试 + GPU 校验，失败明确告警 |
| 提权后状态机收敛等待（1~2 分钟再碰 KSU） | ❌ 缺失 | `CHEESE_SETTLE_MS`（默认 0，推荐 60000~120000） |
| 单次运行约束（同内核生命周期反复跑会累积损伤） | ⚠️ 文档未写 | §6 明确写入；幂等跳过 + sidecar 让重跑不再"脏" |

---

## 3. 本轮新增机制

### 3.1 写-校验闭环（核心）
```
gpu_pwrite_u64_verified(cheese, pa, val, label)
  ├─ gpu_pwrite_u64_wait()        # SMMU_TABLE_UPDATE + MEM_WRITE + marker
  ├─ ReadPhys64(pa) 读回比对       # 独立第二次传输，可捕获 "marker 置位但数据未落"
  ├─ 不符 -> 重写（默认 3 次）
  └─ 读回不可用（调用失败 / 0x0 / ~0）-> 连续 3 次后降级 marker-only 并告警
```
- 判定语义：**"不符" = 硬失败**（中止，绝不带病继续）；**"读不到" = 降级**（不阻塞设备上本来能成功的流程）。
- 统计输出：`[stab] 写校验统计: 通过 N 次, 降级(读回不可用) M 次, 读故障 K 次`。

### 3.2 读路径可用性探针（分级前提）
设备历史上 GPU 读路径不稳（v1 源码里就有"符号校验读可能失败 … 容错继续"）。
因此在 patch 流程之前用已知内容探一次：读 `stext_pa` 首指令，期望 `0xd503233f`（`FW_STEXT_WORDS[0]`）。
- 探通 → 严格档：写前内容校验 + 残留判定 + 写后整段确认全部启用。
- 探不通 → 降级档：只保留**纯算术**的地址窗口校验 + marker-only 写校验，并把降级打明。
- 需要"降级就拒绝"时：`CHEESE_REQUIRE_PREREAD=1`。

### 3.3 写前安全校验（防盲写）
四道关（任一不过即拒绝）：
1. 4 字节对齐；
2. `pa ∈ [stext_pa, stext_pa + 0x4000000)`（覆盖 .text/.data，实测所有目标偏移都在窗口内：vhangup 0x5dc898、cap_bprm 0x93cdbc、kptr 0x2d6dd14、kprobe 0x37cecc）；
3. 首指令可读且非 `0x00000000` / `0xFFFFFFFF`（GPU 读 fault 特征）；
4. 首指令 == 期望值（vhangup 期望 `0xd503233f`，来源 `docs/EXPLOIT0_FIX.md` 判据 3 的实测值）。

### 3.4 残留 / 半写救援（四态判定）
| 状态 | 判据 | 动作 |
|---|---|---|
| 0 原始 | 首 word = `paciasp`，非首 word 命中 < 2 | 正常写入 |
| 1 完整 stub | 全部 word 一致 | **幂等跳过**（重复跑不再叠加写） |
| 2 半写残片 | 首 word = `paciasp` 且非首 word 命中 ≥ 2 | **全量补齐自愈** |
| 3 未知代码 | 首 word ≠ `paciasp` | **拒绝覆盖**（NOT our patch） |

> 实现要点：所有 stub 首 word 都是 `paciasp`，与原始序言相同 ——
> 若按"全部 word 命中数"分类，任何 `paciasp` 开头的区域都会被误判成残片，
> "拒绝覆盖未知代码"就形同虚设。因此只以**非首 word 命中数**作为残片判据。

### 3.5 原始值 sidecar 备份
X Fold+ 方案能从已读的 43MB 内核镜像里按偏移取回原始代码；本工程没有镜像 buffer，
因此以 **首次 patch 前 GPU 读回的字节** 为唯一可信来源，落盘保存：
`<ROOTD_DIR>/.cheese_stab_backup.bin`（magic `STAB`、最多 8 条 `{pa, orig}`）。
- 用途一：`CHEESE_RESTORE_ALL=1` / `CHEESE_RESTORE_VHANGUP=1` 的自包含还原（替代一部分 `unpatch.ko` 的活）。
- 用途二：重跑时发现目标已是补丁态 → 不会把补丁态当成"原始值"存进备份。
- 读不到原始值时不落盘、也不允许还原（拒绝盲写）。

### 3.6 kptr_restrict 只写低 32 位
v1 写 8 字节全 0，会顺带清掉相邻的 `ptr_key`（`+0x2d6dd18` 前 4 字节）。
本轮改为 `new = orig & 0xffffffff00000000`，把低 32 位清零、保留 `ptr_key`。
（`kptr_restrict=0` 时 `ptr_key` 本就不参与运算，所以这是"无害→更干净"，不是行为变更。）

### 3.7 单进程提权
```
patch vhangup / cap_bprm / kptr
  → 本进程直接 syscall(58) 触发 stub          # 不 fork
  → 功能性验证（CapEff/Secbits 打印；MODE=1 用 0x1234 返回值硬验证）
  → 按需还原 / 关守卫登记
  → 显式释放 GPU：撤 fast_rw → 销毁 kgsl ctx → 关 fd → munmap 6GB spray+payload → 落硬闸门
  → [收敛等待] → 三选一：
       ① 内置 su：本进程开 socket 命令服务（CHEESE_SU=1 / _su_* 构建，见 SU_BUILTIN.md）
       ② 内嵌 daemon：同进程跑文件队列（CHEESE_DAEMON=1，rootc 客户端）
       ③ 一次性：exec shell（ROOT_CMD，默认交互 sh）
```
收益：
- 消除 fork 的 COW / `put_page` / KGSL release 三类隐患；
- 6GB spray 与 kgsl context **在 root 阶段前就还回去了**（v1 是父进程抱着直到 shell 退出），
  LMK 压力与"劫持态页表长期存在"的风险一并消失；
- 旧 fork 路径保留为 `CHEESE_SINGLE_PROC=0`（回归对比用），并在 fork 前落硬闸门。

### 3.8 顺带修掉一个潜在越界（v1 latent bug）
`patch_vhangup_stub` 原实现按 `i += 2` 成对写 u64：
`vhangup_stub[i]` + `vhangup_stub[i+1]`。对**奇数长度** stub（`STUB_MODE=5` 17 words、`STUB_MODE=8` 7 words）
会读出数组外一个 word 当作高 32 位写入目标 —— 既越界读，又会向 `.text` 多写 4 字节。
本轮改为：偶数部分走 u64 写，**尾部奇数字走单 dword 写**（新增 `gpu_pwrite_u32_wait`）。

### 3.9 fast_rw 拆除校验
`dirtypt_teardown` 里 PTE-B 的还原：
- 由 `kernel_pwrite_u64`（无返回值、看不到失败）改为 `gpu_pwrite_u64_verified`；
- 还原后 GPU 读回，**只比 PFN 与 valid 位**（避开 AF/DBM 硬件动态位）；
- 4 次重试，仍不通过 → 明确告警"exec 前请勿继续"。
（当前 caps 流程并不打开 fast_rw 窗口，`dirtypt_ro_setup` 只被未接线的 init-hook 路径调用；
这属于把定时炸弹先拆掉。）

---

## 4. 新增环境变量

| 变量 | 默认 | 作用 |
|---|---|---|
| `CHEESE_SINGLE_PROC` | `1` | 单进程提权（=0 回到 fork 路径） |
| `CHEESE_VERIFY_RETRIES` | `3` | 单次写入的"写→读回"重试次数 |
| `CHEESE_STAB_VERBOSE` | `0` | 打印每次校验通过/短路细节 |
| `CHEESE_GUARD` | `1` | 装信号兜底还原（SIGINT/TERM/SEGV/BUS/ILL/ABRT/ALRM） |
| `CHEESE_GUARD_KEEP` | `0` | 提权成功后**保持**守卫登记（退出时还原 cap_bprm/kptr） |
| `CHEESE_REQUIRE_PREREAD` | `0` | 前置读失败时改为**硬拒绝**（严格档） |
| `CHEESE_SELFTEST` | `0` | 碰 `.text` 前跑 GPU 写自检（诊断用） |
| `CHEESE_ALLOW_SELFTEST_FAIL` | `0` | 自检失败仍继续（风险自负） |
| `CHEESE_SETTLE_MS` | `0` | 提权后收敛等待毫秒数（推荐 60000~120000 再碰 KSU） |
| `CHEESE_RESTORE_VHANGUP` | `0` | 提权成功后从 sidecar 还原 vhangup 序言（清 `.text` 残留） |
| `CHEESE_RESTORE_ALL` | `0` | 从 sidecar 全量还原（自包含 unpatch；**勿与 daemon 同用**） |

原有变量（`CHEESE_STEXT_PA` / `CHEESE_DAEMON` / `CHEESE_PATCH_CAP` / `CHEESE_PATCH_KPROBE` /
`CHEESE_ATTEMPT` / `ROOTD_DIR` / `CHEESE_LOCK` / `ROOT_CMD`）行为不变。

---

## 5. 构建与使用

```sh
# 构建（需 NDK r29；脚本按候选路径自动探测，也可 NDK= 显式指定）
bash neo9-root/exploit/build_exploit_stable.sh
#   -> exploit_vivo_neo9_stable_ndk13 / _ndk10 / _ndk1(诊断)
#   -> exploit_vivo_neo9_stable_su_ndk13 / _su_ndk10        (内置 su 版)
#   -> exploit_vivo_neo9_stable[_su]                        (静态 gcc，需 aarch64-linux-gnu-gcc)
#   脚本末尾自动跑: 语法预检 -> 两个回归测试 -> verify_bins.py 产物校验
```

```sh
# 改过 stub 状态判定 / 新增 STUB_MODE 后先跑这个 (不需设备, 只读源码)
python3 neo9-root/exploit/test_stub_state.py
#   -> 6 个场景 (原始/完整/半写x2/未知x2) x STUB_MODE 10,13 全部命中期望状态
```

### 偏移锁与产物校验（"地址不变"不是靠自觉）

两层机制，构建时自动跑：

1. **编译期断言锁**：源码里对所有实测确定的地址/偏移做了 `_Static_assert`
   （`NEO9_VHANGUP_OFFSET`、`NEO9_PREP_KRED_VA`、`NEO9_COMMIT_CREDS_VA`、
   `KERNEL_PHYS_BASE`、`FW_*_VA`、`task_struct.cred/real_cred`、`cred.cap_*` 等）。
   一旦有人改动，**构建立刻失败**并指出是哪一项。反向验证过：把 `0x5dc898` 改成 `0x5dc8b8`
   即报 `static assertion failed: "NEO9_VHANGUP_OFFSET changed"`。
2. **产物校验 `verify_bins.py`**（需要 NDK 的 llvm 工具，构建脚本会自动调用）：
   - ELF 架构 AArch64、bionic 解释器 `/system/bin/linker64`、`DT_NEEDED` 只含 `libc.so`/`libdl.so`
   - **各 `STUB_MODE` 的 vhangup stub 整段机器码逐字节命中**（不是"看起来在"，是逐字比对；
     `STUB_MODE=1` 的 8 字节小数组会被 clang 常量折叠，改为按立即数"能否合成"核对）
   - **偏移闸门**：反汇编 `patch_vhangup_stub` / `patch_cap_bprm`，把所有立即数（含 `lsl` 位移）
     收成候选集，验证 `0x5dc898` / `0xd503233f` / `0x93cdbc` / `0x2d6dd14` / `0x37cecc`
     能由单个候选或两候选之和合成 —— 该判定与优化档无关（ndk13 编成 `movk #0x5d,lsl16 + #0xc898`，
     ndk1 编成 `#0x5dc,lsl12 + #0x898`，两者都能通过）
   - 内置 su 变体额外核对协议/CLI 字符串（`su.sock`/`PING`/`EXEC`/`SHELL`/`--u0`/`--no-path`）

```sh
# 冷窗口 (<5min) 标准用法
VER=PD2338_A_15.1.14.7.W10.V000L1
adb push neo9-root/exploit/out/$VER/exploit_vivo_neo9_stable_ndk13 /data/local/tmp/
adb shell "cd /data/local/tmp && CHEESE_STEXT_PA=0xa8010000 CHEESE_DAEMON=1 \
  CHEESE_PATCH_CAP=1 CHEESE_STAB_VERBOSE=1 nohup ./exploit_vivo_neo9_stable_ndk13 \
  > stab.log 2>&1 &"
# 看日志关键行:
#   [stab] 稳定性层 v2: single_proc=1 verify_retries=3 guard=1
#   [stab] 读路径探针: 可用
#   cap_bprm_creds_from_file patched (exec keeps caps, 读回校验通过)
#   root ready (caps mode, ...) / GPU 资源已释放
# 等 rootd_ready.txt -> rootc "命令"
```

```sh
# 想"提权 90 秒后再碰 KSU"（对齐 X Fold+ 的状态机收敛建议）
CHEESE_SETTLE_MS=90000 ...
```

### 回滚
v1 源码已归档：`neo9-root/archive/exploit_vivo_v1_prestab.c`（md5 `9e7c2dd8e49ca959d9485db88c31ef6b`）。
回滚 = 用它覆盖 `exploit/exploit_vivo.c` 重新构建；运行期回滚 = `CHEESE_SINGLE_PROC=0`。

---

## 6. 未覆盖项 / 风险边界（诚实清单）

1. **spray 命中率没动**。全灭（44 候选全失败）仍是概率问题，缓解手段依旧是"刚重启、内存干净时跑"。
   本轮**没有**改候选集/spray 布局——这是硬约束，但也意味着这一项风险未降低。
2. **读路径降级 = 校验能力下降**。若探针报"不可用"，写前内容校验与残留判定自动跳过，
   只剩地址窗口校验，回到"信任硬编码偏移"的状态（日志会明确写出来）。
3. **GPU 读回的独立性有限**：读回与写同走 GPU。它能抓"命令流部分执行/未执行"，
   抓不到"CPU 缓存脏行覆盖 GPU 写"这类跨 agent 竞争（Neo9 caps 流程不经 CPU 写 `.text`，
   该风险主要在 fast_rw/init-hook 路径，已由 `dc cvac` + 未接线的现状覆盖）。
4. **信号兜底不是万能的**：`SIGKILL` 捕不到；GPU 释放后（提权成功之后）发生信号时无法还原。
5. **cap_bprm 补丁态在 daemon 期间必须保留**（rootc 命令 exec 后要靠它保 caps），
   所以"完全无痕"只在 `CHEESE_RESTORE_ALL=1` 的一次性场景成立。
6. **`CHEESE_RESTORE_ALL` + `CHEESE_DAEMON` 是错误组合**（会让 rootc 命令丢 caps），代码里只打警告不拦。
7. ~~**未上机验证**~~ → **已上机验证（2026-09-26，第二次尝试命中）**。
   静态侧：宿主 gcc 13.3 对 `STUB_MODE=1/5/8/10/12/13` 全部 `-fsyntax-only` 通过；
   `-Wall` 无新增告警（仅存 3 条 v1 固有告警）；`test_stub_state.py` 12 组断言通过；
   NDK r29 编译 + `verify_bins.py` 产物校验（stub 机器码逐字节命中 + 偏移闸门）通过。

   真机侧（`neo9-root/scripts/logs/su_attempt2.log`，CVE-2025-21479 路径，`CHEESE_PATCH_CAP=1`）：

   | 观测项 | 实际结果 |
   |---|---|
   | 读路径探针 | **可用**（`stext 首指令 = 0xd503233f`）→ 走严格档，非降级 |
   | 写校验统计 | **通过 14 次，降级 0 次，读故障 0 次** |
   | vhangup stub | 12 个 u64 全部"校验通过"（GPU 读回逐字节比对），整段 96 字节落地 |
   | cap_bprm / kptr_restrict | 均"读回校验通过"；`ptr_key` 相邻字段保留（本轮修复点生效） |
   | 单进程模式 | `single_proc=1` → 无 fork 提权，`root ready (caps mode)` |
   | GPU 释放顺序 | `已销毁 ctx / 已关 fd / 已释放 spray` → **硬闸门落下** → 之后才 fork su 服务 |
   | 系统健康 | netd 1533 / zygote64 1534 / system_server 2349 均为开机 PID（未被杀）；load 11.7→1.66；MemAvailable 10.5 GB（6 GB spray 已回收） |

   结论：**本轮新增的四项核心机制（写-校验闭环、单进程提权、GPU 释放闸门、读路径探针）在真机上按设计生效**，
   没有出现 X Fold+ 文章里"patch 成功、restore 失败""fork 后 GPU 通道死亡""用一会就崩"的任何一种。
   含非 UTF-8 日志的驱动崩溃已修复（`run_su_until_root.py`），并固化为回归用例 ⑥。
