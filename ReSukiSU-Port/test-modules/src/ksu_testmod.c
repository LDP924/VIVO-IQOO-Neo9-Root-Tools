// ksu_testmod.c - 设备内核能力探针模块
// 测试: register_kprobe / kallsyms_lookup_name / register_trace_prio_sys_enter
// 结果通过 module_param 暴露到 /sys/module/ksu_testmod/parameters/
#include <linux/module.h>
#include <linux/kprobes.h>
#include <linux/kallsyms.h>
#include <linux/tracepoint.h>
#include <trace/events/syscalls.h>

static int kp_reboot_ret = -999;
module_param(kp_reboot_ret, int, 0644);
static unsigned long kp_reboot_addr = 0;
module_param(kp_reboot_addr, ulong, 0644);

static unsigned long kl_sys_call_table = 0;
module_param(kl_sys_call_table, ulong, 0644);
static unsigned long kl_ni_syscall = 0;
module_param(kl_ni_syscall, ulong, 0644);
static unsigned long kl_reboot = 0;
module_param(kl_reboot, ulong, 0644);

static int tp_ret = -999;
module_param(tp_ret, int, 0644);

static struct kprobe kp_reboot = {
    .symbol_name = "__arm64_sys_reboot",
};

static void test_sys_enter(void *data, struct pt_regs *regs, long id)
{
    /* 什么都不做, 仅测试 tracepoint 注册 */
}

static int __init ksu_testmod_init(void)
{
    kp_reboot_ret = register_kprobe(&kp_reboot);
    if (kp_reboot_ret == 0) {
        kp_reboot_addr = (unsigned long)kp_reboot.addr;
        unregister_kprobe(&kp_reboot);
    }

    kl_sys_call_table = kallsyms_lookup_name("sys_call_table");
    kl_ni_syscall = kallsyms_lookup_name("__arm64_sys_ni_syscall");
    kl_reboot = kallsyms_lookup_name("__arm64_sys_reboot");

    tp_ret = register_trace_prio_sys_enter(test_sys_enter, NULL, INT_MIN);
    if (tp_ret == 0)
        unregister_trace_sys_enter(test_sys_enter, NULL);

    return 0;
}

static void __exit ksu_testmod_exit(void)
{
}

module_init(ksu_testmod_init);
module_exit(ksu_testmod_exit);
MODULE_LICENSE("GPL");
MODULE_DESCRIPTION("ksu test module");
