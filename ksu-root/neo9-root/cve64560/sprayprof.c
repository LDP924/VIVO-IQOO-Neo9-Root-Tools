/*
 * sprayprof — 实测「哪种 spray 尺寸落进哪个 slab cache」。
 *
 * 动机：CVE-2026-64560 的回收要求我们的内容出现在刚释放的 posix_timers slab 上。
 * 该 slab = objsize 264 / 31 obj / 2 页 = **order-1 (8KB)**。
 * 只有落在「同阶数」的分配才可能直接吃下那些页（buddy 的 free_area[1] LIFO）。
 *
 * /proc/slabinfo 对 shell 可读，所以可以逐项量出 slab 数变化。
 * 本工具只做分配 + 读计数，不碰漏洞路径，安全。
 */
#define _GNU_SOURCE
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <errno.h>
#include <fcntl.h>
#include <sched.h>
#include <sys/socket.h>
#include <sys/mman.h>
#include <sys/types.h>

#define MAXC 512

struct slot {
	char name[48];
	long objs;      /* num_objs */
	long slabs;     /* num_slabs */
	int objsize;
	int objper;
	int pagesper;
	int used;
};

static struct slot tbl[MAXC];
static int nslot;

static int parse_slabinfo(void)
{
	FILE *f = fopen("/proc/slabinfo", "r");
	char line[1024];

	nslot = 0;
	if (!f)
		return -1;
	/* 跳过前两行 */
	if (!fgets(line, sizeof line, f))
		goto out;
	if (!fgets(line, sizeof line, f))
		goto out;
	while (fgets(line, sizeof line, f) && nslot < MAXC) {
		char name[48];
		long act, num, osz, op, pp;
		char *sd, *p;

		if (sscanf(line, "%47s %ld %ld %ld %ld %ld", name, &act, &num, &osz, &op, &pp) != 6)
			continue;
		sd = strstr(line, "slabdata");
		if (!sd)
			continue;
		p = sd + 8;
		{
			long as = 0, ns = 0;

			if (sscanf(p, "%ld %ld", &as, &ns) != 2)
				continue;
			snprintf(tbl[nslot].name, sizeof tbl[nslot].name, "%s", name);
			tbl[nslot].objs = num;
			tbl[nslot].slabs = ns;
			tbl[nslot].objsize = (int)osz;
			tbl[nslot].objper = (int)op;
			tbl[nslot].pagesper = (int)pp;
			tbl[nslot].used = 1;
			nslot++;
		}
	}
out:
	fclose(f);
	return 0;
}

static struct slot *find(const char *name)
{
	int i;

	for (i = 0; i < nslot; i++)
		if (!strcmp(tbl[i].name, name))
			return &tbl[i];
	return NULL;
}

static int pin(int cpu)
{
	cpu_set_t s;

	if (cpu < 0)
		return 0;
	CPU_ZERO(&s);
	CPU_SET(cpu, &s);
	return sched_setaffinity(0, sizeof s, &s);
}

#define MAXSOCK 4096
static int socks[MAXSOCK];
static long g_sent, g_fds;

static void spray_sock(int size, int ns, int nmsgs, unsigned char *buf)
{
	int i, m;

	for (i = 0; i < ns && i < MAXSOCK; i++) {
		int sv[2];

		if (socketpair(AF_UNIX, SOCK_STREAM, 0, sv))
			break;
		fcntl(sv[0], F_SETFL, O_NONBLOCK);
		socks[i] = sv[0];
		/* 另一端保留 fd 不放，否则缓冲被回收 */
		{
			static int keep[MAXSOCK];
			keep[i] = sv[1];
		}
		for (m = 0; m < nmsgs; m++) {
			ssize_t r = send(sv[0], buf, (size_t)size, MSG_DONTWAIT);

			if (r != size)
				break;
			g_sent++;
		}
	}
	g_fds = i;
}

static void spray_pipe(int size, int np, int nmsgs, unsigned char *buf)
{
	int i, m;

	for (i = 0; i < np && i < MAXSOCK; i++) {
		int pv[2];

		if (pipe(pv))
			break;
		fcntl(pv[1], F_SETFL, O_NONBLOCK);
		for (m = 0; m < nmsgs; m++)
			if (write(pv[1], buf, (size_t)size) != size)
				break;
	}
}

int main(int argc, char **argv)
{
	int size = argc > 1 ? atoi(argv[1]) : 7872;
	int ns = argc > 2 ? atoi(argv[2]) : 128;
	int nm = argc > 3 ? atoi(argv[3]) : 8;
	int cpu = argc > 4 ? atoi(argv[4]) : 2;
	const char *mode = argc > 5 ? argv[5] : "sock";
	unsigned char *buf;
	int i;

	buf = malloc((size_t)size);
	if (!buf)
		return 1;
	memset(buf, 0x41, (size_t)size);

	pin(cpu);
	if (parse_slabinfo())
		return 1;

	struct slot before[MAXC];
	memcpy(before, tbl, sizeof tbl);
	int nbefore = nslot;

	printf("=== sprayprof mode=%s size=%d socks=%d msgs=%d cpu=%d ===\n",
	       mode, size, ns, nm, cpu);
	if (mode[0] == 'p')
		spray_pipe(size, ns, nm, buf);
	else
		spray_sock(size, ns, nm, buf);
	printf("spray 完成：发送 %ld 条 x %d 字节 = %.1fMB，%ld 个 socket，缓冲保持不读回。\n",
	       g_sent, size, (double)g_sent * size / 1048576.0, g_fds);

	if (parse_slabinfo())
		return 1;

	printf("%-24s %8s %6s %6s %10s %10s\n",
	       "cache", "objsize", "obj/pl", "pg/pl", "d_objs", "d_slabs");
	for (i = 0; i < nbefore; i++) {
		struct slot *now = find(before[i].name);

		if (!now)
			continue;
		if (now->objs != before[i].objs || now->slabs != before[i].slabs)
			printf("%-24s %8d %6d %6d %10ld %10ld\n",
			       before[i].name, before[i].objsize, before[i].objper,
			       before[i].pagesper, now->objs - before[i].objs,
			       now->slabs - before[i].slabs);
	}
	fflush(stdout);
	{ int h = getenv("SPRAY_HOLD") ? atoi(getenv("SPRAY_HOLD")) : 2;
	  printf("hold %ds\n", h); fflush(stdout); sleep(h); }
	return 0;
}
