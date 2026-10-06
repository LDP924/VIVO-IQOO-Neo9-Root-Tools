// offprobe: 结构体偏移探针（不装载，只编出 .ko 供离线读取数值）
#include <linux/module.h>
#include <linux/sched.h>
#include <linux/sched/signal.h>
#include <linux/cred.h>
#include <linux/pipe_fs_i.h>
#include <linux/miscdevice.h>
#include <linux/posix-timers.h>
#include <linux/signal.h>

#define P(x) (unsigned long)(x)

__attribute__((used)) const unsigned long offprobe_values[] = {
    /* cred */
    P(sizeof(struct cred)),
    P(offsetof(struct cred, uid)),
    P(offsetof(struct cred, gid)),
    P(offsetof(struct cred, suid)),
    P(offsetof(struct cred, sgid)),
    P(offsetof(struct cred, euid)),
    P(offsetof(struct cred, egid)),
    P(offsetof(struct cred, fsuid)),
    P(offsetof(struct cred, fsgid)),
    P(offsetof(struct cred, securebits)),
    P(offsetof(struct cred, cap_inheritable)),
    P(offsetof(struct cred, cap_permitted)),
    P(offsetof(struct cred, cap_effective)),
    P(offsetof(struct cred, cap_bset)),
    P(offsetof(struct cred, cap_ambient)),
    P(offsetof(struct cred, security)),
    P(offsetof(struct cred, user)),
    P(offsetof(struct cred, user_ns)),
    P(offsetof(struct cred, ucounts)),
    P(offsetof(struct cred, group_info)),
    /* task_struct */
    P(sizeof(struct task_struct)),
    P(offsetof(struct task_struct, tasks)),
    P(offsetof(struct task_struct, pid)),
    P(offsetof(struct task_struct, tgid)),
    P(offsetof(struct task_struct, comm)),
    P(sizeof(((struct task_struct *)0)->comm)),
    P(offsetof(struct task_struct, real_cred)),
    P(offsetof(struct task_struct, cred)),
    P(offsetof(struct task_struct, mm)),
    P(offsetof(struct task_struct, group_leader)),
    P(offsetof(struct task_struct, sighand)),
    P(offsetof(struct task_struct, thread_pid)),
    /* pipe_buffer */
    P(sizeof(struct pipe_buffer)),
    P(offsetof(struct pipe_buffer, page)),
    P(offsetof(struct pipe_buffer, offset)),
    P(offsetof(struct pipe_buffer, len)),
    P(offsetof(struct pipe_buffer, ops)),
    P(offsetof(struct pipe_buffer, flags)),
    P(offsetof(struct pipe_buffer, private)),
    /* miscdevice */
    P(sizeof(struct miscdevice)),
    P(offsetof(struct miscdevice, fops)),
    P(offsetof(struct miscdevice, minor)),
    P(offsetof(struct miscdevice, name)),
    P(offsetof(struct miscdevice, list)),
    P(offsetof(struct miscdevice, nodename)),
    /* k_itimer */
    P(sizeof(struct k_itimer)),
    P(offsetof(struct k_itimer, it_lock)),
    P(offsetof(struct k_itimer, kclock)),
    P(offsetof(struct k_itimer, it_clock)),
    P(offsetof(struct k_itimer, it_id)),
    P(offsetof(struct k_itimer, it_interval)),
    P(offsetof(struct k_itimer, it_pid)),
    P(offsetof(struct k_itimer, it_active)),
    P(offsetof(struct k_itimer, it.cpu)),
    P(offsetof(struct k_itimer, it.real)),
    P(offsetof(struct k_itimer, it.alarm)),
    /* sighand / cpu_timer 基元 */
    P(sizeof(struct sighand_struct)),
    P(offsetof(struct sighand_struct, siglock)),
    P(sizeof(struct timerqueue_node)),
    P(offsetof(struct timerqueue_node, node)),
    P(offsetof(struct timerqueue_node, expires)),
    P(sizeof(struct rb_node)),
    P(offsetof(struct rb_node, __rb_parent_color)),
    P(offsetof(struct rb_node, rb_right)),
    P(offsetof(struct rb_node, rb_left)),
    P(sizeof(struct hrtimer)),
};

__attribute__((used)) const char offprobe_names[] =
    "sizeof_cred\0"
    "cred_uid\0""cred_gid\0""cred_suid\0""cred_sgid\0""cred_euid\0""cred_egid\0"
    "cred_fsuid\0""cred_fsgid\0""cred_securebits\0"
    "cred_cap_inheritable\0""cred_cap_permitted\0""cred_cap_effective\0"
    "cred_cap_bset\0""cred_cap_ambient\0"
    "cred_security\0""cred_user\0""cred_user_ns\0""cred_ucounts\0""cred_group_info\0"
    "sizeof_task_struct\0"
    "task_tasks\0""task_pid\0""task_tgid\0""task_comm\0""task_comm_len\0"
    "task_real_cred\0""task_cred\0""task_mm\0""task_group_leader\0"
    "task_sighand\0""task_thread_pid\0"
    "sizeof_pipe_buffer\0"
    "pipe_page\0""pipe_offset\0""pipe_len\0""pipe_ops\0""pipe_flags\0""pipe_private\0"
    "sizeof_miscdevice\0""misc_fops\0""misc_minor\0""misc_name\0""misc_list\0""misc_nodename\0"
    "sizeof_k_itimer\0"
    "kit_lock\0""kit_kclock\0""kit_clock\0""kit_id\0""kit_interval\0""kit_pid\0""kit_active\0"
    "kit_it_cpu\0""kit_it_real\0""kit_it_alarm\0"
    "sizeof_sighand\0""sighand_siglock\0"
    "sizeof_timerqueue_node\0""tq_node\0""tq_expires\0"
    "sizeof_rb_node\0""rb_parent_color\0""rb_right\0""rb_left\0"
    "sizeof_hrtimer\0";

static int __init offprobe_init(void) { return 0; }
static void __exit offprobe_exit(void) {}
module_init(offprobe_init);
module_exit(offprobe_exit);
MODULE_LICENSE("GPL");
