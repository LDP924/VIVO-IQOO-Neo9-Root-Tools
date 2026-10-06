# vr.ko 绕过方案 (VRKO_BYPASS)

> 本文简述在 iQOO Neo9 (PD2338C) 上绕过 vivo vr.ko 反 root 检测的完整方案。
> 详细逆向分析见 `vrko_static_analysis.md`，实验记录见 `DEBUG_RECORD.md`。

## 1. vr.ko 是什么

vivo 内核反 root 模块 (`vr`，[permanent] 不可卸载)。它通过 **kprobe** 挂在
`do_init_module` / `avc_has_perm` / `module_memfree` + tracepoint
(`initcall_finish`)，检测条件：

```
current->cred->euid == 0          # 进程是 root
&& cred->security->sid != vrp 域   # 且不在白名单域 (u:r:vrp:s0)
→ force_sig(SIGABRT, SI_QUEUE)    # 击杀 (fatal + 落盘取证)
```

**触发场景**：
- `insmod` 内核模块（模块加载检测，不看调用者 uid）→ 设备重启
- KSU 加载后**软重启**：zygote/netd（uid 0）重新 exec → KSU execve hook
  触发大量 SELinux 检查 → vr.ko 的 `avc_has_perm` kprobe 检测到
  zygote/netd（uid 0 非 vrp）→ 击杀 → **netd SIGABRT 循环 / zygote 起不来**。

## 2. 三级绕过方案

### 第一级：euid 保持非 0（STUB_MODE=10/13 的根基）

vr.ko 检测 `euid == 0`。exploit 提权 stub **不改 euid**（保持 2000 或
app uid），只写 `fsuid/fsgid=0 + 全 caps (0x1fffffffff)`：
- `fsuid=0` → 文件系统操作全放行（DAC 视角是 root）
- `euid!=0` → vr.ko 不触发 ✅
- 配合 `CHEESE_PATCH_CAP=1`（patch `cap_bprm_creds_from_file` → exec 保留 caps）

→ 实现: `exploit_vivo.c` 的 `STUB_MODE=10`（euid 保持 + fsuid/caps）
  与 `STUB_MODE=13`（+ cred->security->sid=kernel，SELinux 放行 module_load）。

### 第二级：SELinux sid=kernel（STUB_MODE=13）

仅 fsuid/caps 不够：`insmod` 还要过 SELinux `module_load` 检查。
STUB_MODE=13 额外写 `cred->security->osid/sid = 1 (SECINITSID_KERNEL)`：
- 进程被视为 kernel 域 → **SELinux 全放行**（module_load 通过）✅
- euid 仍非 0 → vr.ko 仍不触发 ✅
- 代价：kernel-sid 的 rootd 在 KSU 加载后无法再 exec/写文件（本次移植的
  已知边界，已通过两阶段部署规避——rootd 只加载 kernelsu，其余由
  shizuku+su_ksu 完成）。

### 第三级：vrpatch.ko（软重启时 netd/zygote 被杀的解药）

KSU 加载后软重启时，第一、二级管不住 vr.ko 对**系统进程**（zygote/netd）
的误杀。治本方案 **`test-modules/vrpatch/vrpatch.ko`**：

```c
struct module *vr = find_module("vr");
unsigned long detect = (unsigned long)vr->core_layout.base + VR_DETECT_OFFSET;
// VR_DETECT_OFFSET = vr.ko .text 内检测函数入口偏移（**随固件走**，见下表与 §5）
set_memory_rw(detect, 1);          // 模块内存是 vmalloc, set_memory_rw 可用
*(u32 *)detect       = 0x52800000; // mov w0, #0
*(u32 *)(detect + 4) = 0xd65f03c0; // ret
flush_icache_range(detect, detect + 8);
set_memory_ro(detect, 1);
```

**`VR_DETECT_OFFSET` 是版本绑定值**（vr.ko 随固件发布，代码位置会变）：

| 固件（软件版本号） | 内核 | 该固件 vr.ko | 检测函数偏移 |
|---|---|---|---|
| `PD2338_A_15.1.14.7.W10.V000L1` | 5.15.178 | 389,952 B | `.text+0x2ecc` |
| `PD2338_A_14.0.17.2.W10.V000L1` | 5.15.137 | 385,008 B，md5 `87cfed37827f74b8fb69790cc7a8b222`，build-id `22565b1e…` | `.text+0x2ecc`（**同值**，2026-09-28 重新取证）|

两版恰好相同是因为检测函数的代码与 `.text` 内的相对位置没变 —— **但不能因此假设下一个固件也一样**，
换固件必须按 §5 重推。

- 检测函数直接返回 0（"无异常"）→ **vr.ko 不再击杀任何 uid 0 进程**。
- 软重启**不重载内核模块** → patch 持久生效（硬重启后需重新部署）。
- 只动 vr.ko 自身，**不影响全局 kprobe_dispatcher**（`CHEESE_PATCH_KPROBE=1`
  会 patch 全局 dispatcher 导致 init 崩溃，已禁用）。

## 3. 部署顺序（KSUOneTap 一键流程）

```
[1] exploit13 (STUB_MODE=13): euid 保持 + fsuid/caps + sid=kernel
    → 内核写: vhangup stub + cap_bprm patch + kptr_restrict=0
[2] rootd (kernel-sid) 只执行: u0 ksud insmod kernelsu-vivo.ko allow_shell=1
    (KSU 加载后 kernel-sid 无法再 exec, 所以到此为止)
[3] App 通过 shizuku + su_ksu (shell 域):
    su_ksu -c "ksud insmod unpatch.ko"   # 恢复 cap_bprm (软重启保护)
    su_ksu -c "ksud insmod vrpatch.ko"   # 中和 vr.ko 检测 ← 本方案核心
    su_ksu -c "killall exploit_vivo_neo9" # KSU 接管
[4] 软重启: su_ksu -c "ksud soft-reboot"  # 完整 boot 事件流, 无 netd 崩溃
```

## 4. 实测数据

| 方案 | netd SIGABRT/5min | zygote | 软重启恢复 |
|---|---|---|---|
| 无 vrpatch | 26+ 次 | restarting 循环 | 慢/卡半启动 |
| **+ vrpatch** | **0 次** | running 稳定 | uptime 连续, 快速 |

## 5. 换固件时怎么重推 `VR_DETECT_OFFSET`（2026-09-28 实测流程）

vr.ko 在 **vendor_boot 的 ramdisk** 里（`lib/modules/vr.ko`），不在 `/vendor/lib/modules`，
也不在 system.img —— 所以要用 vendor_boot.img：

```sh
# 1. 从该固件的 vendor_boot.img 取出 vr.ko（只读、不需要设备）
python3 neo9-root/tools/vboot_extract_one.py <vendor_boot.img> lib/modules/vr.ko -o vr-<版本>.ko
# 2. 与"设备上真正在跑的那份"对账（必须一致，否则 offset 白推）
#    设备侧(cat /sys/module/vr/notes/.note.gnu.build-id 或 /sys/module/vr/srcversion)
#    与本地 readelf -n 的 Build ID / modinfo 的 srcversion 逐字比
# 3. 扫检测函数指纹（见下），取该指纹前最近的 paciasp = 函数入口
# 4. 填进 vrpatch.c 的 profile 分支 + 改 _Static_assert
FW=14.0.17.2 MODULE=vrpatch bash test-modules/build.sh     # 产物在 test-modules/out/<版本>/
```

**指纹**（euid 检查是检测函数的第一件事）：

```
mrs  xN, sp_el0          ← 取 current
ldr  xM, [xN, #0x798]    ← current->cred   (task_struct.cred 偏移)
ldr  wK, [xM, #0x14]     ← cred->euid
cbnz wK, ...             ← euid != 0 直接返回（只检查 euid==0 的进程）
```

两个偏移（`0x798` / `0x14`）在 5.15 的 vivo 内核上是稳定的（与本工程 exploit 用的
`OFFSETOF_TASK_STRUCT_CRED` / `OFFSETOF_CRED_EUID` 同源），所以这条指纹可复用；
命中后**往前找最近的 `paciasp`** 就是函数入口（`.text` 是第一个 exec-alloc 段，
所以 `core_layout.base` = `.text` 起点 ⇒ 该地址的段内偏移就是 `VR_DETECT_OFFSET`）。

⚠️ 目标设备若**没有** vr.ko（其他厂商），本模块不需要加载。

## 6. 移植到其他设备（其他厂商）

1. 先确认设备上有等价的"反 root 检测模块"（`/proc/modules` 里找），逆向它的检测函数
   （思路同上：找读 `cred->euid` 并与自己的域比较、命中就杀进程的函数）。
2. 改 `vrpatch.c` 的 profile 分支 + `_Static_assert`。
3. `FW=<新版本> MODULE=vrpatch bash test-modules/build.sh` 重建。
4. 无此类模块则不加载。
