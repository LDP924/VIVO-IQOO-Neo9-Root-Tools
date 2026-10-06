// ksu_feature_probe.c - 直接测试 GET_FEATURE ioctl
#include <stdio.h>
#include <unistd.h>
#include <sys/syscall.h>
#include <sys/ioctl.h>
#include <string.h>
#include <errno.h>

// _IOWR('K', 13, 0)
#define KSU_IOCTL_GET_FEATURE 0xC0004B0DUL
// _IOW('K', 14, 0)
#define KSU_IOCTL_SET_FEATURE 0x40004B0EUL

#pragma pack(push, 1)
struct ksu_get_feature_cmd {
    unsigned int feature_id;
    unsigned long long value;
    unsigned int supported;
};
#pragma pack(pop)

int main(void)
{
    printf("uid=%d euid=%d\n", getuid(), geteuid());

    int fd = -1;
    syscall(SYS_reboot, 0xDEADBEEF, 0xCAFEBABE, 0, &fd);
    printf("[1] fd=%d\n", fd);
    if (fd < 0)
        return 1;

    for (int id = 0; id < 5; id++) {
        struct ksu_get_feature_cmd cmd;
        memset(&cmd, 0, sizeof(cmd));
        cmd.feature_id = id;
        errno = 0;
        long ret = ioctl(fd, KSU_IOCTL_GET_FEATURE, &cmd);
        printf("[2] GET_FEATURE id=%d ret=%ld errno=%d value=%llu supported=%u\n",
               id, ret, errno, cmd.value, cmd.supported);
    }
    close(fd);
    return 0;
}
