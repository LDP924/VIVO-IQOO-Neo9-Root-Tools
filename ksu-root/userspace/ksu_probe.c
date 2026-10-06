// ksu_probe.c - raw KernelSU supercall tester
// Tests: reboot-magic fd trick, ioctl GET_INFO, prctl legacy supercall
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

// _IOC(_IOC_NONE, 'K', 1, 0) = 0x40014b01 ; _IOR('K',2,16) = 0x80104b02
#define KSU_IOCTL_GRANT_ROOT 0x40014b01UL
#define KSU_IOCTL_GET_INFO 0x80104b02UL

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

    /* 1. legacy prctl GET_VERSION */
    result = 0;
    ret = syscall(SYS_prctl, KERNEL_SU_OPTION, CMD_GET_VERSION, 0, 0,
                  (unsigned long)&result);
    printf("[1] prctl GET_VERSION: ret=%ld errno=%d result=0x%lx\n", ret,
           errno, result);

    /* 2. reboot magic -> ksu driver fd */
    int fd = -1;
    errno = 0;
    ret = syscall(SYS_reboot, 0xDEADBEEF, 0xCAFEBABE, 0, &fd);
    printf("[2] reboot fd trick: ret=%ld errno=%d fd=%d\n", ret, errno, fd);

    if (fd >= 0) {
        struct ksu_get_info_cmd info;
        memset(&info, 0, sizeof(info));
        errno = 0;
        ret = ioctl(fd, KSU_IOCTL_GET_INFO, &info);
        printf("[3] ioctl GET_INFO: ret=%ld errno=%d version=%u flags=%u "
               "features=%u uapi=%u\n",
               ret, errno, info.version, info.flags, info.features,
               info.uapi_version);

        /* 3b. GRANT_ROOT via ioctl */
        errno = 0;
        ret = ioctl(fd, KSU_IOCTL_GRANT_ROOT, NULL);
        printf("[4] ioctl GRANT_ROOT: ret=%ld errno=%d (after: uid=%d "
               "euid=%d)\n",
               ret, errno, getuid(), geteuid());
        close(fd);
    }

    /* 5. prctl GRANT_ROOT legacy */
    result = 0;
    errno = 0;
    ret = syscall(SYS_prctl, KERNEL_SU_OPTION, CMD_GRANT_ROOT, 0, 0,
                  (unsigned long)&result);
    printf("[5] prctl GRANT_ROOT: ret=%ld errno=%d result=0x%lx\n", ret,
           errno, result);

    return 0;
}
