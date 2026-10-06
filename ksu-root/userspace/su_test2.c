// su_test2.c - 测试内核 sucompat execve hook: execve("/system/bin/su")
#include <stdio.h>
#include <unistd.h>
#include <string.h>
#include <errno.h>

int main(void)
{
    printf("before: uid=%d euid=%d\n", getuid(), geteuid());
    fflush(stdout);
    char *argv[] = {"/system/bin/su", "-c", "id; echo SU_EXEC_OK", NULL};
    char *envp[] = {"PATH=/system/bin:/data/local/tmp", NULL};
    execve("/system/bin/su", argv, envp);
    printf("execve failed: errno=%d (%s)\n", errno, strerror(errno));
    return 1;
}
