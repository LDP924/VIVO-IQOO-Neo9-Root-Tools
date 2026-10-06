// u0.c - setuid(0) 提升工具 (rootd 全 caps 环境使用)
// 用法: u0 <命令...>  -> 以 euid=0 执行
#include <unistd.h>
#include <stdio.h>
#include <stdlib.h>

int main(int argc, char** argv) {
    if (setgid(0) != 0) { perror("setgid"); return 1; }
    if (setuid(0) != 0) { perror("setuid"); return 1; }
    if (argc < 2) {
        char* args[] = {"/system/bin/sh", NULL};
        execv("/system/bin/sh", args);
        perror("execv sh");
        return 1;
    }
    execv(argv[1], &argv[1]);
    perror("execv");
    return 1;
}
