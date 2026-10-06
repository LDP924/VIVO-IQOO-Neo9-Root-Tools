// proctest.c - init 创建 /proc/ksu_init_marker, 用于验证 init 是否执行
#include <linux/module.h>
#include <linux/proc_fs.h>
#include <linux/seq_file.h>

static int magic = 0;
module_param(magic, int, 0644);

static int marker_show(struct seq_file *m, void *v)
{
    seq_printf(m, "INIT_RAN magic=0x%x\n", magic);
    return 0;
}

static int marker_open(struct inode *inode, struct file *file)
{
    return single_open(file, marker_show, NULL);
}

static const struct proc_ops marker_ops = {
    .proc_open = marker_open,
    .proc_read = seq_read,
    .proc_lseek = seq_lseek,
    .proc_release = single_release,
};

static int __init proctest_init(void)
{
    magic = 0x5A5A5A5A;
    proc_create("ksu_init_marker", 0666, NULL, &marker_ops);
    return 0;
}

static void __exit proctest_exit(void)
{
    remove_proc_entry("ksu_init_marker", NULL);
}

module_init(proctest_init);
module_exit(proctest_exit);
MODULE_LICENSE("GPL");
