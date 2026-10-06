# CVE-2026-64560（Zombietick）移植到 16.2.13.2 — 工作区

目标：在 iQOO Neo9 / PD2338_A_16.2.13.2.W10（内核 **5.15.197-g708015331567-dirty**）上拿到临时 root。

## 0. 状态

| 阶段 | 状态 |
|---|---|
| 材料盘点 / 工具链 | 完成 |
| 目标事实导出（符号+结构偏移+竞态偏移+sysctl 表） | 完成 |
| 竞态可达性 | **无 root 时无上游 oracle**（kprobe/dmesg 都要 root）→ 需 in-band 判据 |
| reclaim / cross-cache | 未开始 |
| bridge（kernel R/W） | 未开始（原版未验证，须自建） |
| root stage | 未开始（可复用项目 cred patch + tsu） |

## 1. 上游材料及其缺口（重要）

`~/Desktop/cve-2026-64560/` 是**碎片**（22 文件，来自 NebuSec/CyberMeowfia 公开 exploit 的移植）。
缺失：`TARGET_FACTS_HEADER`、`lib/`（14 个模块：timing / futex_bucket / physmap / nameblob /
slabpage / physlayout / cred_image / sockpool / crosscache / ashmem / pipe_rw …）、`runner/`、
`gen-cve64560-offsets.py`。README 自述原移植（panther/Pixel）**bridge descriptor 未验证**
⇒ 不存在可直接编译使用的端到端链，bridge 必须自建。

## 2. 目标事实（本目录）

- `target_facts_syms.txt` — 链接期符号地址（base 0xffffffc008000000，运行时 = base + KASLR slide）
- `../test-modules/offprobe/offsets-5.15.txt` — 66 项结构偏移（vivo 5.15 树导出，与 5.15.137 真机验证值一致）

关键值：

```
sizeof(k_itimer)=0x108   k_itimer.it(union)=0x78   timerqueue_node.expires=+0x18
k_itimer->cpu_timer.head = +0x98        (上游 race-detector 的 HEAD_OFF=152 一致)
miscdevice.fops=0x10     sizeof(pipe_buffer)=0x28
cred: uid 0x04  securebits 0x24  caps 0x28/0x30/0x38/0x40/0x48  security 0x78  group_info 0x98
task_struct: tasks 0x4d0  mm 0x520  pid 0x5d8  real_cred 0x790  cred 0x798  comm 0x7a8  sighand 0x7f8
```

**竞态判据（本内核实测反汇编）**：

```
posix_cpu_timer_del = 0xffffffc0082baea8
  +0x0e8  cbz x0, +0x290        ; lock_task_sighand() == NULL 分支
  +0x290  ldr x8, [x20, #0x98]  ; ctmr->head   ← 上游 kprobe 的 RACE_OFF 落点
  +0x294  cbz x8, +0x148
  +0x298  brk #0x800            ; WARN_ON_ONCE（漏洞分支）
  ⇒ RACE_OFF = 0x290, HEAD_OFF = 0x98, head 基址寄存器 = x20（上游 panther 是 x19）
```

**加固面（真机 /proc/config.gz）**：`CFI_CLANG=y`、`SLUB_MIRROR=y`、`BUG_ON_DATA_CORRUPTION=y`、
`INIT_ON_ALLOC_DEFAULT_ON=y`、`SLAB_FREELIST_HARDENED=y`、`RANDOM=y`、`KFENCE=y`(500ms 采样)、
`ASHMEM=y`、`RANDOMIZE_BASE=y`、`KALLSYMS_ALL=y`（符号可解析）、`DEBUG_INFO_BTF=y`
（但镜像已剥离 .BTF；`/sys/kernel/btf/vmlinux` 需 root）。

**sysctl 表（kern_table @0xffffffc00adc0d10，stride 0x40）**：已完整 dump（idx 0..70），
含 `randomize_va_space`(55) / `dmesg_restrict`(44) / `kptr_restrict`(45) / `panic`(13) /
`core_pattern`(15) / `modprobe`(26) 等；stage0 的 KASLR 泄漏需要一个 maxlen=4/8 且 shell 可读的条目。

## 3. 镜像提取配方（本轮踩坑修正）

- 真实 arm64 Image 起始 = boot.img **文件偏移 0x1000**（boot header 的 `hsize=1584` 之后还有 0x9d0 前缀，
  直接按 hsize 切片会整体移位 → 数据符号全错）。
  可靠做法：`start = boot.img.find(b"ARM\x64") - 0x38`。
- boot header `kernel_size`(0x3044a00) == `_edata - _text`（不含 .bss）；arm64 header 的
  `image_size`(0x31e0000) == `_end - _text`。
- 早先的 `kernel.bin`（45MB，从 magic-0x38 切）**文本映射正确但被截断**（缺 0xadc0000 之后的数据）；
  完整版是 `work/kernel_img.bin`（50.6MB）。做数据类分析必须用完整版。

## 4. 验证手段的约束（关键）

设备当前**无 root**：`adb root` 被拒（production build）、`dmesg` 权限拒绝、kprobe/tracefs 不可用。
⇒ 上游 `race-detector.sh`（kprobe）与 WARN 观察**都不可用**；成功判据必须内建（in-band），
例如把原语结果写到 shell 可读的 `/proc/sys/<条目>`（stage0 的读回设计天然满足这一点）。

## 5. 计划（按依赖顺序）

1. 竞态触发移植（`trigger.c` 参数按 SM8550 拓扑调参：8 核，deleter 2-6 / exec 7）
2. reclaim + cross-cache（注意 `SLUB_MIRROR` 与 `INIT_ON_ALLOC`）
3. **bridge 自建**：伪造 ops 表须满足 kCFI 签名校验（原版失败的最可能原因）
4. root stage：复用项目已验证的 cred patch / tsu
