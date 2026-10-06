// vrread.c - 回读 vr.ko detect 函数前 8 字节（验证 vrpatch 中和是否落盘）
//
// 用法: ksud insmod /data/local/tmp/vrread.ko && dmesg | grep vrread
// 期望:
//   已中和:   00 00 80 52 c0 03 5f d6  (mov w0,#0; ret)
//   未中和:   3f 23 03 d5 ...          (paciasp + 函数序言)
// 编译: make ARCH=arm64 LLVM=1 LLVM_IAS=1 HOSTCC=gcc HOSTCXX=g++ HOSTAR=ar HOSTLD=ld \
//         -C $HOME/kernel/vivo-neo9-16 M=$(pwd) src=$(pwd) modules
#include <linux/module.h>
#include <linux/kernel.h>
#include <linux/init.h>
#include <linux/moduleloader.h>

#define VR_DETECT_OFFSET 0x2eccUL

static int __init vrread_init(void)
{
	struct module *vr = find_module("vr");
	unsigned char *p;

	if (!vr) {
		pr_err("vrread: vr module not found\n");
		return -ENOENT;
	}
	p = (unsigned char *)vr->core_layout.base + VR_DETECT_OFFSET;
	pr_info("vrread: vr base=0x%lx size=%zu detect=0x%lx\n",
		(unsigned long)vr->core_layout.base,
		vr->core_layout.size,
		(unsigned long)vr->core_layout.base + VR_DETECT_OFFSET);
	pr_info("vrread: bytes@detect: %02x %02x %02x %02x %02x %02x %02x %02x\n",
		p[0], p[1], p[2], p[3], p[4], p[5], p[6], p[7]);
	pr_info("vrread: patched=00 00 80 52 c0 03 5f d6 (mov w0,#0; ret)\n");
	pr_info("vrread: original starts 3f 23 03 d5 (paciasp)\n");
	return 0;
}

static void __exit vrread_exit(void)
{
}

module_init(vrread_init);
module_exit(vrread_exit);
MODULE_LICENSE("GPL");
MODULE_DESCRIPTION("read back vr.ko detect bytes (verification helper)");
