// su.c (ReSukiSU 适配版) - 去掉 legacy prctl 门禁, execve /system/bin/su 触发内核 sucompat hook
#include <string.h>
#include <stdio.h>
#include <unistd.h>
#include <sys/prctl.h>
#include <sys/ioctl.h>
#include <sys/xattr.h>
#include <limits.h>
#include <errno.h>
#include <stdlib.h>
#include <termios.h>

#define KERNEL_SU_OPTION 0xDEADBEEF

int main(int argc, char **argv, char **envp) {
    // ReSukiSU 内核无 prctl supercall; root 由内核 execve hook (sucompat) 授予:
    // 只要本进程以允许的 uid 执行 /system/bin/su, 内核会重定向到 /data/adb/ksud 并提升为 root。

    struct termios term;
    if (ioctl(STDIN_FILENO, TCGETS, &term) == 0) {
        char tty_path[PATH_MAX];
        ssize_t len = readlink("/proc/self/fd/0", tty_path, sizeof(tty_path) - 1);
        if (len > 0) {
            tty_path[len] = '\0';
            const char *selinux_ctx = "u:object_r:devpts:s0";
            setxattr(tty_path, "security.selinux", selinux_ctx, strlen(selinux_ctx) + 1, 0);
        }
    }

    const char *default_args[] = { "/system/bin/su", NULL };
    if (argc < 1 || !argv) {
        argv = (char **)default_args;
    } else {
        argv[0] = "/system/bin/su";
    }

    execve("/system/bin/su", argv, envp);

    const char *error = "Error: Failed to execve /system/bin/su (kernel hook not active?)\n";
    write(STDERR_FILENO, error, strlen(error));
    return 1;
}
