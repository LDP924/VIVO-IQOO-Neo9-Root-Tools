// unpatch.c - 还原 exploit 对 cap_bprm_creds_from_file 的内核 patch (软重启兼容)
// exploit (CHEESE_PATCH_CAP) 把 cap_bprm_creds_from_file 入口改为 mov w0,#0; ret
// -> exec 保留全 caps -> 软重启时 zygote 带异常 caps 崩溃。
//
// 本模块手动修改 kernel text 页表权限 (绕过 set_memory_rw 的 vmalloc-only 限制),
// 写回原始指令后还原权限。不依赖 GPU/exploit 进程, 杀 rootc 后仍可用。
// 加载: rootc 'u0 ksud insmod /data/local/tmp/unpatch.ko' (kallsyms 解析未导出符号)
//
// ⚠️ 适用系统版本: 见下面的 profile 分支（本模块是**系统版本绑定**产物）
//   产物按版本存放: test-modules/out/<软件版本>/unpatch.ko → apk/ksuonetap/assets/<同版本>/
//
// ⚙️ 设备适配 (移植到其他设备/固件时改这两个值):
//   - CAP_BPRM_PA: cap_bprm_creds_from_file 的**物理地址** = stext_pa + 符号偏移。
//     符号偏移 = 符号 VA - _stext VA（本工程 stext_pa=0xa8010000、_stext VA=0xffffffc008010000）。
//     也有等效的线上来源: exploit 打同一个补丁点，`exploit_daemon.log` 里会打印
//     `patching cap_bprm_creds_from_file @ 0x...` —— 那就是这个地址（实测一致，可互相印证）。
//   - CAP_BPRM_ORIGINAL: 该函数入口的原始 8 字节（`paciasp; sub sp,sp,#0xNN` 序言），
//     从该固件的内核镜像读出：文件偏移 = (VA - 0xffffffc008010000) + 0x10000
//     （0x10000 = SAMSUNG_STEXT_OFFSET，即 _stext 在 Image 里的位置）。
//   - 若目标内核无 cap_bprm patch 需求（非 exploit 提权），本模块可跳过。
#include <linux/module.h>
#include <asm/memory.h>
#include <asm/pgtable.h>
#include <asm/tlbflush.h>
#include <asm/cacheflush.h>

/* ---- 版本绑定: cap_bprm_creds_from_file 的物理地址 + 原始序言（构建时 -DFW_PD2338_A_14_0_17_6 选 137/17.6 那版）---- */
// 14.0.17.6（2026-09-28 重推）: cap_bprm_creds_from_file @ stext+0x92937c，
// 反汇编序言 paciasp; sub sp,sp,#0x90 与 17.2 逐字相同 ⇒ CAP_BPRM_PA/ORIGINAL 沿用。
#if defined(FW_PD2338_A_14_0_17_2) || defined(FW_PD2338_A_14_0_17_6)
// PD2338_A_14.0.17.2.W10.V000L1（OriginOS 4 / 内核 5.15.137-gc870e76526d2-dirty）
// stext_pa 0xa8010000 + 符号偏移 0x92937c；原始序言 = paciasp; sub sp,sp,#0x90
// 物理地址由真机 exploit 日志印证（`patching cap_bprm_creds_from_file @ 0xa893937c`，写下即校验通过）
#define CAP_BPRM_PA       0xa893937cUL
#define CAP_BPRM_ORIGINAL 0xd10243ffd503233fULL
#else
// PD2338_A_15.1.14.7.W10.V000L1（OriginOS 5 / 内核 5.15.178-gaacdc35637c4-dirty）
// stext_pa 0xa8010000 + 符号偏移 0x93cdbc；原始序言 = paciasp; sub sp,sp,#0x90
#define CAP_BPRM_PA       0xa894cdbcUL
#define CAP_BPRM_ORIGINAL 0xd10243ffd503233fULL
#endif
_Static_assert(CAP_BPRM_PA == 0xa893937cUL || CAP_BPRM_PA == 0xa894cdbcUL,
               "CAP_BPRM_PA 变了 —— 必须重新取证后再改");
_Static_assert((CAP_BPRM_PA & 3UL) == 0, "CAP_BPRM_PA 必须 4 字节对齐");
_Static_assert(CAP_BPRM_ORIGINAL == 0xd10243ffd503233fULL,
               "CAP_BPRM_ORIGINAL 变了 —— 与内核镜像对账后再改");

/* 未导出符号: 由 ksud insmod 从 kallsyms 解析填充地址 */
extern void flush_icache_range(unsigned long start, unsigned long end);

/* arm64 PTE: bit7 = AP[2] (0=EL1 可写, 1=只读) */
#define AP_RDONLY_BIT 7UL

static void pmd_set_rw(pmd_t *pmd, int rw)
{
    unsigned long v = pmd_val(*pmd);
    if (rw)
        v &= ~(1UL << AP_RDONLY_BIT);
    else
        v |= (1UL << AP_RDONLY_BIT);
    set_pmd(pmd, __pmd(v));
}

static void pte_set_rw(pte_t *pte, int rw)
{
    unsigned long v = pte_val(*pte);
    if (rw)
        v &= ~(1UL << AP_RDONLY_BIT);
    else
        v |= (1UL << AP_RDONLY_BIT);
    set_pte(pte, __pte(v));
}

static int __init unpatch_init(void)
{
    unsigned long va = __phys_to_kimg(CAP_BPRM_PA);
    pgd_t *pgd = pgd_offset_k(va);
    p4d_t *p4d = p4d_offset(pgd, va);
    pud_t *pud = pud_offset(p4d, va);
    pmd_t *pmd = pmd_offset(pud, va);
    pte_t *pte = NULL;
    bool sect = false;

    if (pmd_sect(*pmd)) {
        sect = true;
        pmd_set_rw(pmd, 1);
    } else if (!pmd_none(*pmd) && !pmd_bad(*pmd)) {
        pte = pte_offset_kernel(pmd, va);
        if (pte_none(*pte))
            return -ENXIO;
        pte_set_rw(pte, 1);
    } else {
        return -ENXIO;
    }

    flush_tlb_kernel_range(va, va + PAGE_SIZE);
    *(u64 *)va = CAP_BPRM_ORIGINAL;
    flush_icache_range(va, va + 16);

    /* 还原权限 */
    if (sect)
        pmd_set_rw(pmd, 0);
    else
        pte_set_rw(pte, 0);
    flush_tlb_kernel_range(va, va + PAGE_SIZE);
    return 0;
}

static void __exit unpatch_exit(void)
{
}

module_init(unpatch_init);
module_exit(unpatch_exit);
MODULE_LICENSE("GPL");
MODULE_DESCRIPTION("restore cap_bprm_creds_from_file (soft-reboot compat)");
