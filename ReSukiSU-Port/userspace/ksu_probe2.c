// ksu_probe2.c - 修正 GRANT_ROOT ioctl 号
#include <stdio.h>
#include <unistd.h>
#include <sys/prctl.h>
#include <sys/syscall.h>
#include <sys/ioctl.h>
#include <string.h>
#include <errno.h>

#define KERNEL_SU_OPTION 0xDEADBEEFUL
#define CMD_GRANT_ROOT 0
#define CMD_GET_VERSION 2

// 真实 ioctl 号: _IOC(dir, 'K', nr, size)
#define KSU_IOCTL_GRANT_ROOT 0x4b01UL          // _IOC(_IOC_NONE,'K',1,0)
#define KSU_IOCTL_GET_INFO 0x80104b02UL        // _IOR('K',2,16)
#define KSU_IOCTL_GET_FEATURE 0xc0184b03UL     // _IOWR('K',3,16) 猜测

struct ksu_get_info_cmd {
    unsigned int version;
    unsigned int flags;
    unsigned int features;
    unsigned int uapi_version;
};

int main(void)
{
    unsigned long result = 0;
    long ret;

    printf("uid=%d euid=%d\n", getuid(), geteuid());

    int fd = -1;
    errno = 0;
    ret = syscall(SYS_reboot, 0xDEADBEEF, 0xCAFEBABE, 0, &fd);
    printf("[1] reboot fd trick: ret=%ld errno=%d fd=%d\n", ret, errno, fd);

    if (fd >= 0) {
        struct ksu_get_info_cmd info;
        memset(&info, 0, sizeof(info));
        errno = 0;
        ret = ioctl(fd, KSU_IOCTL_GET_INFO, &info);
        printf("[2] ioctl GET_INFO: ret=%ld errno=%d version=%u flags=%u "
               "features=%u uapi=%u\n",
               ret, errno, info.version, info.flags, info.features,
               info.uapi_version);

        errno = 0;
        ret = ioctl(fd, KSU_IOCTL_GRANT_ROOT, NULL);
        printf("[3] ioctl GRANT_ROOT: ret=%ld errno=%d (uid=%d euid=%d)\n",
               ret, errno, getuid(), geteuid());

        /* 试试 GRANT_ROOT 后是否能直接变 root (ksu domain creds) */
        if (ret == 0) {
            printf("[4] after grant: uid=%d euid=%d\n", getuid(), geteuid());
        }
        close(fd);
    }

    return 0;
}
