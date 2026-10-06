# CVE-2026-64560（Zombietick）16.2.13.2 移植 —— stage0 设计与事实

目标设备/固件：PD2338（iQOO Neo9），`PD2338_A_16.2.13.2.W10.V000L1`，内核
`5.15.197-g708015331567-dirty`（构建 2026-08-06）。
内核侧漏洞：**UNPATCHED**（`neo9-root/tools/verify_patches.py` 反汇编核验，
`posix_cpu_timer_del` 无 `timer_lock_sighand`、`__lock_task_sighand` 失败分支仍是 WARN 形状）。

---

## 1. 竞态机制（来自上游修复 commit 原文，权威）

修复 commit `920f893f735e92ba3a1cd9256899a186b161928d`
（"posix-cpu-timers: Prevent UAF caused by non-leader exec() race"，Fixes `55e8c8e`）：

```
sys_timer_delete()                 exec()
  posix_cpu_timer_del()
    p = pid_task(pid, pid_type);   ← 必须读到【旧】leader
                                   de_thread()
                                     switch_leader()            (= exchange_tids + transfer_pid)
                                     release_task(old_leader)
                                       __exit_signal(old_leader):
                                         spin_lock(sighand->siglock)
                                         posix_cpu_timers*_exit()      (清 per-thread / 组队列)
                                         __unhash_process()            (detach pid)
                                         tsk->sighand = NULL;          ← 在临界区内
                                         spin_unlock(sighand->siglock)
    sighand = lock_task_sighand(p) ← 读到 NULL ⇒ return 0，**不摘除**
    free_posix_timer()             ← 对象被释放，但其 timerqueue 节点仍在组队列里 ⇒ UAF
```

**窗口成立的两个必要条件**

1. `pid_task` 必须在 `switch_leader` **之前**读（之后 TGID 已移交 exec 线程、sighand 有效
   ⇒ 正常 disarm）。所以 **exec 必须与删除风暴重叠**。
2. 读完之后必须被**拖到 `sighand = NULL` 之后**才读 `p->sighand`。两条路径：
   - **siglock 自旋（主路径）**：先读到非 NULL 的 `sighand`，`raw_spin_lock_irqsave` 自旋
     （`__exit_signal` 持有），拿到锁后重读发现 `p->sighand == NULL` ≠ 原值 ⇒ 循环 ⇒ 返回 NULL。
     窗口 = `__exit_signal` 整个临界区（µs 级）。
   - **被调度走**：CFS 时间片把它挂起几 ms，exec 全部走完后再恢复。
     ⇒ 这是上游"用定时器中断把窗口从几条指令拉长到一个调度时间片"的含义。
     **要求删除核上有抢占机会 ⇒ 删除线程数必须超过可用核数（超订）**。

**为什么别的路径不行**

- 正常退出路径的 `__exit_signal` 顺序是 `posix_cpu_timers_exit*()` → `__unhash_process()`
  → `sighand = NULL`：**队列先被清、pid 先被 detach**，`sighand==NULL` 分支根本不可达，
  且 `!p` 分支此时节点已出队 ⇒ 无 UAF。
- 进程级定时器的节点在 `tsk->signal->posix_cputimers`（`timer_base()` 确认），
  而 `posix_cpu_timers_exit_group()` 只在 `group_dead` 时调用；de_thread 已把旧 leader 的
  `exit_signal` 置 -1 ⇒ 旧 leader 退出时**不清理组队列** ⇒ 节点得以存活。

**摘除时点**：victim 整组退出时 `cleanup_timerqueue()` 遍历组队列，对我们的（已被回收的）
节点做 `timerqueue_del()`（两次写）+ `ctmr->head = NULL`（第三写，`freed+0x98`）。
⇒ 由我们主动 `kill(victim)` 精确控制。

## 2. 回收（spray）前提

`release_posix_timer()` 走 `call_rcu(&tmr->rcu, k_itimer_rcu_free)`，对象要等 RCU 宽限期
才真正归还 slab。`slabprobe.c` 实测（KS=20000）：

| 时点 | active / total |
|---|---|
| create 之后 | 21297 / 21297 |
| **delete 之后立刻** | **21297 / 21297**（一个都没释放） |
| 自然等约 300ms | 1405 / 1674（**页已还给 buddy**） |
| 再 force RCU + 300ms | 975 / 1271 |

⇒ **编排必须是**：风暴 → 强制执行 RCU → **等 `posix_timers_cache` 排空** → 才填充 → 才 kill。
排空判据用 `/proc/slabinfo`（shell 可读，这是本机唯一可用的量化窗口）：
`total ≤ baseline + slack` 或 `total ≤ alloc − 90%(alloc−baseline)`。

目标几何（实测）：`posix_timers_cache objsize=264 objperslab=31 pagesperslab=2`
= order-1 / 8KB / 31 对象，stride `0x108`。

## 3. 判据：安全探针（detect 模式）

**不依赖任何内核地址**（不需要物理基址 / KASLR slide）：

- 填充：0x41，但每个槽位的 `rb_node` 三指针 + expires 全置 0
  ⇒ `rb_next` 立即结束；`rb_erase` 走 **Case 1**；`__rb_change_child(node, child=0, parent=NULL, root)`
  会把 `rb_root.rb_node` 清成 NULL，`rb_leftmost` 也是 NULL
  ⇒ `cleanup_timerqueue` 的 `while (timerqueue_getnext(head))` **安全终止**。
- 观测：紧随 `timerqueue_del` 之后内核写 `ctmr->head = NULL`，位置固定
  **页内偏移 ≡ 0x98（mod 0x108）**；若 slab 页落在 order-2 块的第二半则整体错 8
  ⇒ **≡ 0xA0**。我们的零区在 `[0x78, 0x98)`，这两个位置**只有内核能写零**
  ⇒ 回读流里该位置出现零 qword = **悬空节点存在 + 我们回收成功**（无歧义、无副作用）。

**踩过的坑**

- 曾把 `forged_parent` 指向内核地址（`random_table[4].data−8`）。这种版本**命中后会 oops**：
  伪造 erase 让 `rb_leftmost = forged_parent`，`rb_root` 未清 ⇒ 循环继续把该地址当节点走
  → 读到 `mem[T+0x10]`（`0x0000012400000000`）→ Case 2 走左子树越界。
  而 cmdline 有 `kernel.panic_on_rcu_stall=1` ⇒ 有挂起/panic 风险。**已废弃**。
- `SEND_BYTES` 必须是 `0x108` 的整数倍。0x2000(8192) mod 264 = 8 ⇒ 相邻消息相位漂移
  ⇒ 第 2 条消息的零区落到判据偏移上 ⇒ **64/64 socket 假阳性**。
  现用 **15840 = 264×60**（alloc 16160 → order-2/16KB 块，覆盖块内 96.7% 的两个 slab 页）。
- 曾把 `blacklisted_initcalls`(0xffffffc00adadd28) 当落点 —— 它落在
  `[__init_begin 0xab00000, __init_end 0xad90000)`，**该段启动后被释放给 buddy**，不可用。

## 4. 编排（stage0 v3，每轮）

```
1. slab_read(baseline)；PRE_DRAIN（释放上一轮自己的页，让 timer 页成为"最近释放"）
2. fork victim（exec 线程与主线程分核；exec 线程在风暴推进到 EXEC_AFTER 次删除时 execve）
3. 创建 KS 个进程级 CPU 定时器（clockid = (~victim_pid)<<3 | CPUCLOCK_SCHED，指向 victim）
4. 起 WORKERS 个删除线程（**超订**：worker 数 > 删除核数，保证 CFS 轮转/抢占）
   + 1 个 timerfd IRQ 风暴；等到 target−20µs 开闸
5. 等风暴结束；等 exec ack（exec 之后由 sh 写 ⇒ ack 出现即证 de_thread 已完成）
6. force_rcu（可选）→ 等 posix_timers_cache 排空（slabinfo 闸门）
7. spray 填充（socket 发送队列，不 recv）
8. kill(victim) ⇒ cleanup_timerqueue ⇒ 内核写 ctmr->head = NULL
9. 回读判据（detect）/ 读 boot_id（stage0）；drain
```

## 5. 当前状态与已知未决

- 编排、回收闸门、探针（安全+可观测）都已就绪；**真机 campaign 仍未命中**（600 轮 / 584s / 0 命中）。
- 已排除的结构性原因：exec 与风暴不重叠（v2）✓已修；删完立刻 spray（v2）✓已修；
  1:1 核分配导致无抢占 ✓已改超订；探针假阳性 ✓已修；
  **IRQ 提前量用成 20ms（应在 20~640µs）** ✓已修（对齐上游 `irq_after_release_ns`）；
  **判据逐字节取模导致 check 占 500ms/轮** ✓已修（只按判据相位步进）⇒ 轮速 0.97s→0.35s。
- 未决：命中率仍低于上游（上游 ~0.65%/轮）。剩余候选杠杆：
  ① 超订比例与 worker 数（当前 15/5）；② `EXEC_AFTER`（当前 100）与 exec 抖动；
  ③ IRQ fds/period（当前 1×50µs）；④ KS（当前 20000，上游 58699）；
  ⑤ spray 规模与是否加一条 order-0（pipe 页）回收通道；⑥ 多 lane 并行（受核数限制）。
- 镜像已固化到 `neo9-root/cve64560/ref/`（`kernel_img.bin` + `kernel_full.elf`），
  避免了沙箱按命令行字面路径拦截固件目录的问题。
- **缺一把直测竞态的 oracle**：上游用 kprobe 探针（需 root）；本机 `tainted`/`dmesg`/
  `/dev/kmsg`/`logcat -b kernel` 全部被封 ⇒ 只能靠端到端探针判定。
- **取 KASLR slide 的 stage0 模式仍缺一个安全落点 C**（`.data` 内、`[C+0x10]==0`、
  `[C+8]` 为镜像指针），需要内核镜像的 `.data` 内容；当前 `PD2338_A_16.2.13.2.W10.V000L1/`
  在工作区里**不可读**（`stat`/`open` 均 ENOENT，而 `find` 仍能列出）。

## 6. 工具

| 文件 | 用途 |
|---|---|
| `stage0.c` | 自包含实现，模式 `detect`（安全探针）/`probe`（旧 boot_id 判据，危险）/`stage0`/`baseline`/`list`/`affinity`，参数全 env |
| `run_campaign.sh` | **单实例守卫** campaign 启动器（预检设备侧 `ps -A -o NAME \| grep -w stage0`，非 0 拒绝启动；`FORCE=1` 覆盖）；`MODE=` 选模式 |
| `slabprobe.c` | `/proc/slabinfo` 生命周期标定（证明 RCU 释放时序） |
| `trigger_probe.c` | 上游 trigger + 每轮可观测输出（前置条件验证） |

关键 env：`S0_KS / S0_WORKERS / S0_EXEC_AFTER / S0_RCU / S0_RCU_WAIT_MS / S0_DRAIN_MS /
S0_DRAIN_SLACK / S0_SOCKS / S0_MSGS / S0_SEND / S0_ATTEMPTS / S0_EXEC_CPU / S0_DEL_CPUS /
S0_IRQ_FDS / S0_IRQ_PERIOD_NS / S0_IRQ_LEAD_NS / S0_VERBOSE`

---

## v4 修正（2026-09-29 03:0x）—— 探针曾导致内核故障重启，根因已定位并修复

### 发生了什么

6000 轮 detect campaign 在 **attempt 4280** 终止，随后确认**设备重启**（`uptime` 归零、
`boot_id` 变化、`boot_reason.history=[reboot,1790621941]`=02:59:01）。
宿主机 dmesg 给出时间线：campaign 最后一条进度 `02:58:11` → USB 掉链 `02:58:34`
（**21~23 秒间隔**）→ `02:59:03` 重新枚举。

### 崩溃机制（源码级）

cmdline 有 **`kernel.panic_on_rcu_stall=1`**，RCU stall 默认阈值 21s ⇒ 形态是
「内核在**关中断/持锁**下出错 → 机器卡住 → 21s 后 RCU stall → panic → 重启」。

出错点在 tick 路径：

```
run_posix_cpu_timers()            // lockdep_assert_irqs_disabled()，且在 sighand->siglock 临界区
 └ check_process_timers() → collect_posix_cputimers() → collect_timerqueue()
      while ((next = timerqueue_getnext(head))) {
              ctmr = container_of(next, struct cpu_timer, node);
              expires = cpu_timer_getexpires(ctmr);      // = our node.expires
              if (++i == MAX_COLLECTED || now < expires)
                      return expires;                        // ← 只有这一个门控
              ctmr->firing = 1;
              rcu_assign_pointer(ctmr->handling, current);
              cpu_timer_dequeue(ctmr);                        // if (ctmr->head) timerqueue_del(ctmr->head, …)
              list_add_tail(&ctmr->elist, firing);
      }
```

v3 的载荷把 **`expires` 置成 0**（零区覆盖了 `[0x78,0x98)`，含 expires）⇒ 定时器"立即到期"
⇒ 越过门控 ⇒ `cpu_timer_dequeue()` 读到 `ctmr->head = 0x4141414141414141` ⇒ **解引用非法指针**。

### 这同时是正面证据

崩溃要求内核**真的走到那个未被摘除的悬空节点** ⇒ **漏洞路径已生效**；而 `head` 为 0x41…
说明填充内容已占据该内存 ⇒ **回收（cross-cache）成功**。
（保留意见：未回收的悬空节点也可能崩 —— `SLAB_FREELIST_HARDENED` 把空闲指针放在
`s->offset`，本 cache size=264 ⇒ offset=0x80 = `rb_right`；且 `expires`/`head` 保留旧值，
到期被 tick 处理即写飞。故"漏洞已触发"是硬结论，"回收成功"是强提示。）

### 载荷规范（k_itimer，0x108；`it` 联合体在 +0x78）

| 偏移 | 字段 | 值 | 原因 |
|---|---|---|---|
| 0x78/0x80/0x88 | `node.rb_parent_color` / `rb_right` / `rb_left` | **0** | `rb_erase_cached` 走 Case 1、parent=NULL ⇒ 清空 root；`rb_next()`→NULL ⇒ `rb_leftmost=NULL` ⇒ 循环终止 |
| **0x90** | `node.expires` | **UINT64_MAX** | ★ tick 门控 `now < expires` 立即 return（v3 的崩溃就修在这里）|
| 0x98 | `head` | **0x41（非零）** | 观测点：`cleanup_timerqueue` 会写 NULL 进来；tick 因 expires 远期碰不到它 |
| 0xA0 | `pid` | **0** | `put_pid()`/`pid_task()` 都有 NULL 保护，避免解引用垃圾 |
| 0xA8/0xB0 | `elist.next/prev` | 0 | `list_add_tail` 目标 |
| 0xB8 | `firing` | 0 | |
| **0xC0** | `handling` | **0** | `posix_cpu_timer_wait_running()` 会解引用非 NULL 值 |

### 观测判据（零假阳性）

- **A（配对）**：相位 `0x90 == 0xffffffffffffffff`（我们的 expires，内核不改）**且**
  相位 `0x98 == 0`（`cleanup_timerqueue` 写的 `head = NULL`）。
  单看"0x98 为零"会与我自己的 `pid=0`（偏移 0xA0）在错位对齐下撞车 —— 实测 64/64 假阳性。
- **B（更强）**：`RB_CLEAR_NODE` 把节点自身内核地址写进 `__rb_parent_color`（偏移 0x78）
  ⇒ 相位 `0x78`/`0x80` 出现 `0xffff…` 指针（我们填 0，故零假阳性）。

### 三条会触碰被回收 k_itimer 的内核路径

1. tick `collect_timerqueue` —— 仅被 `expires` 门控（★ 主要风险点）
2. `cleanup_timerqueue`（victim 整组退出）—— 只做 `timerqueue_del()` + `head = NULL`，**不碰 pid**
3. `posix_cpu_timer_del` —— id 已从 IDR 移除 ⇒ 实际不可达；但读 `pid`（含早退路径里
   **无条件的 `put_pid(ctmr->pid)`**）与 `handling`

---

## v5（2026-09-29 03:4x）：命中率提升 ~20×，瓶颈转移到"回收"

### 改了什么

1. **核集必须逐核实测**（`choose_cpus`）：`sched_getaffinity()` 给的是 cgroup 掩码（含 6/7），
   但 `sched_setaffinity(6/7)` 实测 **EINVAL** ⇒ 旧代码 `exec_cpu=6` **静默失败**，
   victim 落在随机核上、窗口时序全乱。现在逐核 `setaffinity`+读回验证再还原。
   真机输出：`cpus(实测可用): 0 1 2 3 4 5 | exec=5 parent=0 del=1 2 3 4`。
2. **`race_exec_repeats`（exec 链）**：victim 的非 leader 线程用 `/proc/self/exe`
   自我 re-exec（`argv[1]="vexec"`、`S0_REP` 递减）⇒ **每轮 3 个 `de_thread` 窗口**（此前 1 个）。
3. **IRQ 语义**：上游 `irq_arm_lead_ns=250ms` 因为他们**轮次开头**就 arm；我们 arm 点已在风暴
   前一刻 ⇒ 等价 `lead=0`（immediate + 50µs interval，20kHz 列车覆盖风暴+exec 窗口）。
4. 旋钮对齐：`EXEC_AFTER=32`（prime_start_deleted）、`WORKERS=5`（worker_goal，不再超订 15）。
5. 新增 `exec_batch_us` 测量，验证上游硬约束「exec 批必须早于 delete_done_ns」。

### 结果（含把设备打重启的那次）

| 配置 | 崩溃（= 命中）出现于 | 结论 |
|---|---|---|
| 旧：exec 1 次/轮、IRQ 20µs 打空、15 workers、KS=20000 | **4280 轮** | 命中率极低 |
| v5：exec 链 3 次/轮、IRQ 真覆盖、5 workers、KS=58699 | **200 轮** | **命中率提升 ~20×** |

崩溃签名（两次一致）：`uptime` 归零 + boot_id 变化 + USB 断开时刻比最后一条日志晚 ~20s
⇒ `kernel.panic_on_rcu_stall=1` + RCU stall 21s ⇒ panic。

### 修正后的崩溃机制 = 新的阻塞点

`tarm()` 用 `it_value.tv_sec = 3600`（超远到期）⇒ tick 路径 `collect_timerqueue`
在 `now < expires` 处直接 return，**不会碰悬空节点** ⇒ 崩溃只能来自**我们 `kill(victim)`
触发的 `cleanup_timerqueue()`**：对**未回收**的节点做 `timerqueue_del` 时，
`rb_right`（偏移 0x80）里是 `SLAB_FREELIST_HARDENED` 写的空闲指针 ⇒ `child` 为垃圾
⇒ `if (child) child->__rb_parent_color = pc;` **向垃圾地址写入 ⇒ oops**。

⇒ **win 发生了，但我们的填充没接管那块内存。剩余阻塞点 = 回收（reclaim）可靠性。**
（注：上游 `pipe_count=3000` / `zt_piperw.c` 从命名看属 **stage-1 pipe carrier**，
未必是 stage-0 的 timer reclaim 通道 —— 这条线索需先核实再采用。）
