// asuid.c - 以指定 uid/gid 执行命令 (rootd 全 caps 上下文使用)
// 用法: asuid <uid> <cmd...>
#include <stdio.h>
#include <unistd.h>
#include <stdlib.h>

int main(int argc, char** argv) {
    if (argc < 3) {
        fprintf(stderr, "usage: asuid <uid> <cmd...>\n");
        return 1;
    }
    long uid = strtol(argv[1], NULL, 10);
    if (setgid(uid) != 0) { perror("setgid"); return 1; }
    if (setuid(uid) != 0) { perror("setuid"); return 1; }
    execv(argv[2], &argv[2]);
    perror("execv");
    return 1;
}
