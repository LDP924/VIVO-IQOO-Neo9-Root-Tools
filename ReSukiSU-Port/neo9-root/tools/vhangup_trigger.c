// vhangup_trigger.c - 验证内核 vhangup stub (STUB_MODE=10) 是否生效
// 用法: vhangup_trigger [module.ko]  (可选: 触发后直接 init_module 加载模块)
// 编译: NDK aarch64-linux-android21-clang -O2 -o vhangup_trigger vhangup_trigger.c
#include <unistd.h>
#include <sys/syscall.h>
#include <stdio.h>
#include <string.h>
#include <errno.h>
#include <fcntl.h>
#include <stdlib.h>

static void show_status(void) {
    FILE* f = fopen("/proc/self/status", "r");
    if (!f) { printf("status open fail\n"); return; }
    char line[256];
    while (fgets(line, sizeof(line), f)) {
        if (!strncmp(line, "Uid:", 4) || !strncmp(line, "Gid:", 4) ||
            !strncmp(line, "CapEff:", 7) || !strncmp(line, "CapPrm:", 7) ||
            !strncmp(line, "CapBnd:", 7)) {
            printf("%s", line);
        }
    }
    fclose(f);
    FILE* g = fopen("/sys/fs/selinux/enforce", "r");
    if (g) { char c = fgetc(g); printf("selinux_enforce=%c\n", c); fclose(g); }
}

int main(int argc, char** argv) {
    printf("before: ");
    show_status();
    long r = syscall(58);   // vhangup -> patched stub
    printf("vhangup=%ld errno=%d\n", r, errno);
    printf("after: ");
    show_status();

    if (argc > 1) {
        // 直接 init_module (不 exec, 保持当前 caps)
        int fd = open(argv[1], O_RDONLY);
        if (fd < 0) { printf("open %s fail: %s\n", argv[1], strerror(errno)); return 1; }
        off_t sz = lseek(fd, 0, SEEK_END);
        lseek(fd, 0, SEEK_SET);
        void* img = malloc(sz);
        if (!img) { printf("malloc fail\n"); return 1; }
        if (read(fd, img, sz) != sz) { printf("read fail\n"); return 1; }
        close(fd);
        long mr = syscall(__NR_init_module, img, sz, "");
        printf("init_module=%ld errno=%d (%s)\n", mr, errno, strerror(errno));
        if (mr != 0) {
            // 失败时再试 finit_module
            fd = open(argv[1], O_RDONLY);
            if (fd >= 0) {
                long fr = syscall(__NR_finit_module, fd, "", 0);
                printf("finit_module=%ld errno=%d (%s)\n", fr, errno, strerror(errno));
                close(fd);
            }
        }
        free(img);
    }
    return 0;
}
