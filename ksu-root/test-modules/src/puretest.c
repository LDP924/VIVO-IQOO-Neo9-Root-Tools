// puretest.c - 零依赖模块: 仅测试内核正常加载路径是否执行 init
// init 只写一个全局变量 (通过 module_param 暴露)
#include <linux/module.h>

static int magic = 0;
module_param(magic, int, 0644);

static int __init pure_init(void)
{
    magic = 0x5A5A5A5A;
    return 0;
}

static void __exit pure_exit(void)
{
}

module_init(pure_init);
module_exit(pure_exit);
MODULE_LICENSE("GPL");
