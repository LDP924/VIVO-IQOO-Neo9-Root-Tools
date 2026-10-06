# neo9-root/tools — 一次性分析工具

这里放**推偏移、做逆向**时用过的一次性脚本。它们不是构建链的一部分（`./build.sh` 不调用
任何一个），价值在于"换个内核版本要重推偏移时，照着当初怎么做的再来一遍"。

整理于 2026-09-27。此前这些脚本里**写死了原分析机的绝对路径**
（Windows 的 `D:\payload-dumper-go\...`），换机器必然跑不起来；现在统一改为：

```python
VRT_ROOT = os.environ.get("VRT_ROOT", "/mnt/d/payload-dumper-go")  # 原分析机为 D:\payload-dumper-go
```

即 **WSL 里的等价路径**（`D:\` ↔ `/mnt/d/`）。换机器时用环境变量覆盖：

```sh
VRT_ROOT=/path/to/payload-dumper-go python3 disasm_vrko_uid.py
```

## ⚠️ 输入文件不在仓库里

脚本读的是**设备固件解包产物**，仓库出于体积与版权都没有收录：

```
$VRT_ROOT/VIVO_Neo_9_PD2338C_GKIv1_5.15.178/
├── For_PD2338C_Kernel/kernel.img                                  # (disasm_kernel.py)
└── vendorboot_For_PD2338C_extracted/lib/modules/vr.ko             # (多数脚本)
$VRT_ROOT/vrtools/{capstone,pyelftools}                           # 第三方库, 需自行 pip 安装
$VRT_ROOT/resukisu_libksud.so                                     # (find_*.py)
```

所以本目录的脚本**默认跑不起来是正常的** —— 缺输入文件。需要时先按注释里的路径备齐。

## 脚本清单

### 换系统版本 / 推新固件常量（主入口）

| 脚本 | 用途 |
|---|---|
| `fw_kernel_derive.py` | **换系统版本时重推 exploit 全部版本绑定常量**。输入 = 从新固件 `boot.img` 抽出的内核 Image；输出 = 符号地址（stext 相对偏移）与结构体字段偏移，每条都附"哪条指令/哪个符号"作证。没有 BTF 也能用（偏移从反汇编读）。子命令：`sym` / `dis` / `find-imm` / `callers` / `selfrefs` / `offsets` |
| `erofs_extract_one.py` | 从 EROFS 镜子里**只抽一个文件**（按 `dump.erofs` 的 extent 逐块 LZ4 解压）。⚠️ 只对 legacy 逐块布局有效；**本项目的 system.img 是 compact/interlaced 格式，会在中间某个 extent 失败并明确报错** —— 那种情况请用 `fsck.erofs --extract=<工作区目录> --no-preserve system.img`（8.7GB，实测 55 秒） |

### init-hook 载荷常量（`/system/bin/init`）

| 脚本 | 用途 |
|---|---|
| `vboot_extract_one.py` | 从 **vendor_boot.img 的 ramdisk** 里只取一个文件（vivo 的 `vr.ko` 只在这里，不在 `/vendor/lib/modules`/system.img）。支持 v3/v4 头、LZ4-legacy(8MB 块, magic 只在开头出现一次)/LZ4-frame/gzip/裸 cpio。取完用 build-id 与设备 `/sys/module/<mod>/notes/.note.gnu.build-id` 对账。用法: `python3 vboot_extract_one.py <vendor_boot.img> lib/modules/vr.ko -o vr.ko`；`--list` 列文件 |
| `init_hook_derive.py` | 从目标固件的 `/system/bin/init` 推 `INIT_TEXT_FILE_VA` / `INIT_HOOK_PATCH_FILE_VA` + 4 条期望指令 / `INIT_HOOK_CAVE_FILE_VA`。做法：可执行 LOAD 的 `p_vaddr` 当基准（`mm->start_code` 的定义见 `fs/binfmt_elf.c`）→ 用 `.rela.plt`+`.dynsym` 认领 PLT 桩（**并要求算出的 GOT 目标与该符号的 `r_offset` 相等**，不是"看着像"）→ 代码洞取可执行段尾的映射填充（`elf_map` 把长度向上取整到页，那一段仍在映射内且读到文件里的 0）。自检不过就退出，不给"看起来对"的值 |

用法与完整推导记录：`neo9-root/docs/DERIVE_PD2338_A_14_0_17_2.md` §9。

> ⚠️ init-hook 这条**路径本身没有接线**（`run_init_hook_lpe` 无调用点），所以补齐常量
> **不会改变产物二进制**（函数被编译器整段删掉）。工具与常量是为将来接线准备的。

### 反汇编 / 反查偏移（读 `vr.ko`）

| 脚本 | 用途 |
|---|---|
| `disasm_vrko.py` | `vr.ko` 的 `.init.text` 反汇编（`init_module` 附近，base 0x2b0 / size 0x600） |
| `disasm_vrko2.py` | 同上，带通用的 `disasm(secname, start, size, label)` 封装 |
| `disasm_vrko_init400.py` | `.init.text` 偏移 `0x400` 附近的例程（定位初始化路径上的调用） |
| `disasm_vrko_text.py` | `.text` 全段反汇编，**输出 `vrko_text_disasm.txt`**（后续静态分析的底稿） |
| `disasm_vrko_uid.py` | 在 `.text` 里找 **euid/uid 判定处** —— `vr.ko` 那个"euid==0 且非白名单域就 kill"的谓词就是这么定位的 |
| `disasm_vrko_trigger.py` | 找**触发/落盘取证**相关代码（确认它到底会不会记录） |
| `vrko_strings.py` | 读 `.init.data` 提取字符串（白名单域名等常量） |
| `reloc_vrko.py` | 解析 `vr.ko` 的符号表与重定位表 —— **重推偏移**的关键一步 |
| `disasm_kernel.py` | 反汇编 `kernel.img` 里的指定符号（`prepare_kernel_cred` / `commit_creds` 等） |
| `verify_stub5.py` | 校验 STUB_MODE=5 的 stub 机器码（逐字比对指令字） |

### 查 `libksud.so` 里的线索

| 脚本 | 用途 |
|---|---|
| `find_kallsyms_ctx.py` | 找 `kallsyms` 解析失败的报错点上下文（判断符号表读取策略） |
| `find_urls.py` | 提取 URL / 域名（确认管理器与 ksud 的下载来源，佐证"官方不发 Release"那条结论） |

### 设备端探针（C + 编译脚本）

| 文件 | 用途 |
|---|---|
| `deploy_once.c` | 一次性工具：触发 vhangup stub → 本进程拿到 root 上下文 → `ksud insmod kernelsu-vivo.ko allow_shell=1` → `ksud insmod unpatch.ko` |
| `vhangup_trigger.c` | 验证内核 vhangup stub 是否生效（可选参数：触发后直接 `init_module` 加载模块） |
| `build_deploy_once.sh` | 用 NDK 编 `deploy_once`（`NDK=<ndk-root>` 可覆盖，产物落在本目录） |
| `build_vhangup_trigger.sh` | 用 NDK 编 `vhangup_trigger`（同上） |

两个 `.sh` 也已参数化：原先写死 `CC=~/android-ndk-cache/...` 且 `cd /mnt/d/payload-dumper-go/ksu-apk`，
现在默认用 `~/android-ndk-cache/android-ndk-r29`、产物落在本目录，找不到编译器时给出明确提示。

## 这些脚本推出来的偏移已经"上锁"

分析结论不是靠脚本运行时保证的，而是固化成了两道闸门 —— 所以日常构建不需要跑本目录：

1. **编译期**：`neo9-root/exploit/fw_profile.h` 的常量 + `exploit_vivo.c` 的 `_Static_assert`
   （值一改就编不过，**每个 profile 有自己的一组期望值**）
2. **产物层**：`neo9-root/exploit/verify_bins.py`（`./build.sh verify`）——
   先核对 `fw_profile.h` 与它自己的期望表逐项一致，再反汇编核对
   `patch_vhangup_stub: 0x5d2db4` 这类偏移与 stub 机器码整段

要动这些值，先读 `neo9-root/docs/vrko_static_analysis.md` 与 `docs/DEBUG_RECORD.md`，
再用本目录的脚本在新内核上重推，**最后同步改 `_Static_assert`**。

---

## 换系统版本怎么重推（完整流程）

以 2026-09-27 适配 `PD2338_A_14.0.17.2.W10.V000L1`（内核 5.15.137）为例。
推导**不需要设备**；但产物最终必须真机验证（见第 6 节）。

### 0. 先决条件：拿到内核镜像

固件全量包（`*-update-full.zip`）里取 `boot.img`。Android boot header v4：
`kernel_size` 在 header 偏移 `0x08`，内核数据在**紧接 header 的第一个 4KB 页**
（`header_size` 1584 → 内核从 `0x1000` 开始）。

```sh
python3 - <<'PY'
import struct
d=open("PD2338C_A_14.0.17.2.W10.V000L1_boot.img","rb").read()
assert d[:8]==b'ANDROID!'
ks,rs,osv,hs=struct.unpack_from('<IIII',d,8)
open("kernel.img","wb").write(d[((hs+4095)//4096)*4096:][:ks])
print("kernel_size",ks,"header_size",hs)
PY
```

确认内核版本（**最关键的判断**：内核 release 不同 ⇒ 全部偏移都得重推）：

```sh
strings -a kernel.img | grep -m3 'SMP preempt mod_unload'
# 5.15.137-gc870e76526d2-dirty SMP preempt mod_unload modversions vivo aarch64
```

> ⚠️ 同机型不同 OriginOS 大版本的内核可以完全不同：
> `PD2338_A_15.1.14.7` = `5.15.178-gaacdc35637c4-dirty`，
> `PD2338_A_14.0.17.2` = `5.15.137-gc870e76526d2-dirty`。
> **vermagic 不同 ⇒ 内核模块必须跟着重编**。137 那版的处理（2026-09-28 已完成）：
> `vrpatch.ko` / `unpatch.ko` 按 137 profile 重编；`kernelsu-vivo.ko` 因该固件是
> **vivo 自研内核、没有公开源码树**，用 `scripts/patch_ko_vermagic.py` 把已有那份的
> vermagic **等长**对齐过去（实测可加载）—— 所以那一版最终也是 `tier=full`。

### 1. 取符号地址（kallsyms 重建）

```sh
python3 fw_kernel_derive.py kernel.img sym _stext prepare_kernel_cred commit_creds \
    __arm64_sys_vhangup selinux_state swapper_pg_dir init_task init_cred \
    cap_bprm_creds_from_file kptr_restrict kprobe_dispatcher
```

首次会自动调 `vmlinux-to-elf` 重建带 ~18.7 万符号的 ELF（约 24s，缓存成
`kernel.img.vmlinux`）。基址应为 `ffffffc008000000`（`_stext = +0x10000`）。

**必须自己验一遍**：反汇编几个符号，确认每个都以 `paciasp`（`0xd503233f`）开头 ——
符号表整体错位时这里一眼能看出来。

```sh
python3 fw_kernel_derive.py kernel.img dis prepare_kernel_cred:6 commit_creds:6
```

### 2. 取结构体偏移（没有 BTF 就从反汇编读）

先确认镜像里到底有没有 BTF：查内嵌符号里有没有 `__start_BTF`。没有就只能反汇编取证
（`offsets` 子命令已把下面这些做成一条命令；本表是它的"判据"说明）：

| 字段 | 取证函数 | 判据 |
|---|---|---|
| `task_struct.cred` | `__arm64_sys_getuid` | `mrs x8,sp_el0; ldr x8,[x8,#0x798]` |
| `task_struct.real_cred` | `commit_creds` | 开头 `ldr x19,[x20,#0x790]` |
| `cred.uid` / `euid` | `__arm64_sys_getuid` / `geteuid` | 随后的 `ldr w8,[x8,#4]` / `#0x14` |
| `cred.fsuid` / `fsgid` | `__sys_setfsuid` / `setfsgid` | `ldr w25,[x19,#0x1c]` / `#0x20` |
| `cred.cap_effective` | `cap_capable` | 位测试前的 `ldr w8,[x8,#0x38]` |
| `cred.securebits` / 其余 cap | `cap_bprm_creds_from_file` | `ldrb [cred,#0x24]`、`ldp [cred,#0x28]`… |
| `cred.security` | `selinux_cred_getsecid` | `ldr x9,[x0,#0x78]` + `selinux_blob_sizes.lbs_cred` |
| `task_struct.mm` | `get_task_mm` | `ldr x20,[x21,#0x520]` |
| `mm_struct.pgd` | `init_mm` 静态数据 | `.pgd` 是页对齐指针；相邻 `mm_users=2`/`mm_count=1` 与内核初始化式逐字对应 |
| `task_struct.tasks` | 任一 `for_each_process` 使用者 | 扫 `sub xN, xN, #0x4d0` 形态（十几个函数会命中） |
| `task_struct.pid` | `check_hung_task` | `INFO: task %s:%d` 的 `%d` 实参 = `ldr w2,[x19,#0x5d8]` |

辅助子命令：

```sh
python3 fw_kernel_derive.py kernel.img selfrefs init_task   # 自指 list_head ⇒ 直接读出偏移
python3 fw_kernel_derive.py kernel.img callers pgd_free 6    # 看某符号的调用点怎么装参数
python3 fw_kernel_derive.py kernel.img find-imm 0x5d8        # 谁在访问 [reg,#0x5d8]
```

**旁证（很值钱，别跳过）**：内核里内嵌了 IKCONFIG。解出来与旧固件对比，看**影响结构体
布局的开关**是否变化 —— 若 `LOCKDEP / DEBUG_LOCK_ALLOC / KEYS / SECURITY_SELINUX /
DEBUG_CREDENTIALS / THREAD_INFO_IN_TASK / PGTABLE_LEVELS / VA_BITS` 一致，那么
`task_struct` / `cred` / `mm_struct` 布局大概率未变（本次适配即如此：24 个字段偏移逐一吻合）。

```sh
python3 - <<'PY'
import gzip
d=open("kernel.img","rb").read()
s=d.index(b'IKCFG_ST')+8; e=d.index(b'IKCFG_ED',s)
open("config.txt","wb").write(gzip.decompress(d[s:e]))
PY
diff <(grep -v '^#' config.txt | grep -v '^$' | sort) \
     <(grep -v '^#' ~/kernel/vivo-neo9-android15/.config | grep -v '^$' | sort)
```

### 3. 落到代码里（5 处，缺一处闸门就会报出来）

1. `neo9-root/exploit/fw_profile.h`：加一个 `#if defined(FW_<新版本>)` 分支，填常量
   （符号 VA 与 `stext+off` 两种写法都写清楚）
2. `exploit_vivo.c` 的 `_Static_assert` 块：加该 profile 的一组期望值
3. `verify_bins.py` 的 `PROFILES` **期望表**（独立写死的一份，头文件漂移靠它抓）
   ＋ `fw_profile.py` 的 `PROFILE_KEYS`
4. `build_exploit_stable.sh` 的 `FW_TABLE` 加一行
5. `SYSTEM.txt` + `DeviceGate.kt` + `check-assets-sync.sh` 的 `VERSIONED`（见第 5 节）

### 4. 构建 + 两道闸门

```sh
./build.sh exploit --fw <新版本>      # 产物在 neo9-root/exploit/out/<Build.DISPLAY>/
./build.sh verify  --fw <新版本>      # 头文件一致性 + stub 机器码 + 地址闸门
```

### 5. 接入 assets（tier 决定放几件）

```sh
VER=<Build.DISPLAY>
mkdir -p apk/ksuonetap/assets/$VER
cp neo9-root/exploit/out/$VER/exploit_vivo_neo9_stable_su_ndk13 \
   apk/ksuonetap/assets/$VER/exploit_vivo_neo9
md5sum apk/ksuonetap/assets/$VER/exploit_vivo_neo9      # 写进 SYSTEM.txt
```

写 `assets/$VER/SYSTEM.txt`（照抄现有那份改版本号与 md5），**关键是 `tier=` 那一行**：

- `tier=full` —— 4 件绑定产物齐备（exploit + 3 个内核模块）
- `tier=lpe-su` —— 只有 exploit（先只做 LPE + 临时 root su，模块留待后续）

模块怎么来（按可行性递减）：

1. 有该内核的源码树 → `FW=<版本> bash test-modules/build.sh` 重编 `vrpatch.ko` / `unpatch.ko`；
   `kernelsu-vivo.ko` 走 `scripts/build_ksu_module.sh`
2. **没有公开源码树**（vivo 自研内核就是这种）→ `kernelsu-vivo.ko` 只能用
   `scripts/patch_ko_vermagic.py` 把已有那份的 vermagic **等长**对齐到目标内核
   （`--release <目标 uname -r>`；先 `cmp -l` 确认只差那 76 字节里的十几字节），
   产物落 `kernel-module/out/<版本>/`；`vrpatch.ko` / `unpatch.ko` 仍然可以重编
   （它们只需要一个符号偏移/地址，不依赖内核源码）
3. 两条都不行 → 该版本就先停在 `tier=lpe-su`

然后 `DeviceGate.kt`（`full` → `ADAPTED_BUILDS`，`lpe-su` → `LPE_ONLY_BUILDS`）
与 `check-assets-sync.sh` 的 `VERSIONED` 表各加一条，最后 `./build.sh sync` ——
三处不互相对齐会直接报错。

### 6. 真机验证（**必须**，离线推不出来）

```sh
adb push neo9-root/exploit/out/$VER/exploit_vivo_neo9_stable_su_ndk13 /data/local/tmp/exploit_vivo_neo9
adb shell "cd /data/local/tmp && CHEESE_SU=1 CHEESE_PATCH_CAP=1 CHEESE_STEXT_PA=0xa8010000 \
  nohup ./exploit_vivo_neo9 > exploit_daemon.log 2>&1 &"     # 冷窗口(<5min)内起
adb shell cat /data/local/tmp/exploit_daemon.log              # 看 [stab] 行与 stext 自证
adb shell /data/local/tmp/su -c id                            # → uid=0(root)
```

失败时按这个顺序看日志：

1. `FW _stext verification failed` / `quick FW _stext scan failed` —— **物理基址不对**。
   设备无 KASLR，但物理加载地址由 bootloader 决定；`KERNEL_PHYS_BASE=0xa8000000` 只对
   Neo9 成立。可用 `CHEESE_CHAIN_FULL_SCAN=1` 全扫兜底。
2. `selinux_state first bytes ... do not match` —— `FW_SELINUX_STATE_VA` 不对。
3. `vhangup 入口不是原始序言 -> 拒绝覆盖` —— `NEO9_VHANGUP_OFFSET` 不对
   （这条是**保护**不是 bug：宁可拒绝也不盲写 .text）。
4. 全灭（spray 不命中）与偏移无关，reboot 重试即可。

