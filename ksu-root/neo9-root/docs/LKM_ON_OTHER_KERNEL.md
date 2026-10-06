# 把 KSU 的内核模块装到"没有对应源码"的另一版内核上

> 场景：本工程的 `kernelsu-vivo.ko` 是为 `5.15.178-gaacdc35637c4-dirty`（OriginOS 5 /
> `PD2338_A_15.1.14.7.W10.V000L1`）编的。现在要把它用在 `5.15.137-gc870e76526d2-dirty`
> （OriginOS 4 / `PD2338_A_14.0.17.2.W10.V000L1`）上，而这一版**拿不到对应的内核源码**。
>
> 结论先写在这里：**"改 vermagic"是可行但不够，而且它不是必须的手段 —— KSU 自带一条
> 更强、且专门为这种情况设计的通路（`ksuinit` / `ksud late-load`），它自动改 vermagic
> 并用 kallsyms 重定位符号。** 详见 §3。

## 1. 内核装载器（`insmod` / `finit_module`）这条路上要过的门

实测数据（目标内核 = 14.0.17.2 的 `5.15.137`，镜像来自 `boot.img`，
`neo9-root/tools/fw_kernel_derive.py` 同一套方法重建符号表）：

| 门 | 5.15.137 vs 5.15.178 | 能不能绕 |
|---|---|---|
| **vermagic** | 不同：`5.15.178-gaacdc35637c4-dirty …` vs `5.15.137-gc870e76526d2-dirty …` | ✅ **正好等长（各 76 字节）、且 ko 里只出现 1 次**（文件偏移 `0x22773`）⇒ 可原地改；重建时对齐 `CONFIG_LOCALVERSION` 也行 |
| `MODULE_INIT_IGNORE_VERMAGIC` | — | ❌ **本内核无效**：`check_modinfo()` 把 `modmagic` 置 NULL 后会走 `try_to_force_load()`，而 `CONFIG_MODULE_FORCE_LOAD` 两边都没开 ⇒ 直接 `-ENOEXEC`（`kernel/module.c` 实测） |
| `CONFIG_MODVERSIONS` 的 CRC | ko 的 `__versions` 段**大小为 0** | ✅ 无 CRC 表 ⇒ `same_magic()` 走"整串比较"（因为 `has_crcs=false`），CRC 校验也不生效 |
| **`struct module` 布局/大小** | ko 的 `.gnu.linkonce.this_module` = `0x3c0` 字节、`init_module` 在 **+0x178**；两版**决定这些的 config 开关逐个相同**（KALLSYMS / KALLSYMS_ALL / JUMP_LABEL / TRACEPOINTS / EVENT_TRACING / FTRACE / KPROBES / KRETPROBES / LIVEPATCH=off / SYSFS / KASAN…）；137 还**多开** `CONFIG_MODULE_ALLOW_BTF_MISMATCH=y`（比 178 更宽松） | ✅ 大概率兼容（要更硬的证据就得把运行内核的 `struct module` 偏移从反汇编里抠出来对账） |
| CFI / LTO | 两边都 `CONFIG_CFI_CLANG=y` + `CONFIG_LTO_CLANG_FULL=y` | ⚠️ 但 **137 是 clang 14 编的、178 是 clang 18** ⇒ 用 clang 18 编出来的 CFI 模块有"类型哈希不一致 → CFI failure"的风险（要真编模块就用 **clang 14 / NDK r25c**） |
| **未定义符号** | ko 依赖 215 个 GLOBAL 且**全部被重定位引用**的符号；用 137 镜像的 `__ksymtab` 逐条对账，其中 52 个在 137 上**没有导出** | ✅ **实测不成问题** —— 见下面的「为什么」|

**为什么"未导出符号"最终不是障碍（2026-09-28 实测 + 源码确认）**：
设备侧装模块走的是 **`ksud insmod`**，而它内部就是 **`ksuinit::load_module()`**（与 late-load 同一个
用户态手工装载器，`ksud/src/android/debug.rs:55` → `ksuinit::load_module`）。该函数做两件事：

```rust
// ksuinit/src/lib.rs::load_module()
// ① 把每个 UND 符号用 /proc/kallsyms 查到地址后, 直接把符号改成绝对地址
for_each_kernel_symbols(|(name, addr)| {
    if let Some((mut sym, off)) = unresolved_symbols.remove(name) {
        sym.st_shndx = SHN_ABS as usize;     // ← 不再走内核的符号解析
        sym.st_value = *addr;
        buffer.pwrite_with(sym, off, ctx)?;
    }
})
// ② 首次 init_module 失败 → 读 kmsg 拿内核要求的 vermagic → 内存里替换 → 重试
match init_module(&buffer, params) {
    Ok(()) => Ok(()),
    Err(e) => { let v = extract_required_vermagic(&read_new_kmsg(&mut kmsg)?)…;
                replace_module_vermagic(&mut buffer, &v)?; init_module(&buffer, params)? }
}
```

⇒ **内核导不导出这些符号与能否装载无关**（ksud 直接填绝对地址），**vermagic 也不用我们手工改**
（第一次失败后它自己对齐再重试）。上面那张表里的"52 个未导出"仍然是对 137 内核导出面的
**准确测量**，只是它不再是这条路线的门槛。

**实测链路**（同一台 iQOO Neo9，137 内核）：
1. `ksud late-load --kmi android14-5.15` → **内核 panic**（GKI 模块与厂商内核的 `struct module`
   布局/CFI 不匹配）—— 这条路**不要走**
2. `./su -c "./ksud insmod /sdcard/kernelsu-vivo.ko"` → `Loaded kernel module: …`，
   `/proc/modules` 里 `kernelsu 229376 1 - Live` ✅

## 2. 为什么 `ksud late-load` 一开始就报 KMI 错

日志：

```
[late-load start] pid=…, uid=0, selinux=u:r:shell:s0
Error: Failed to detect current KMI version
Caused by: Failed to get KMI from boot/modules
```

`ksud/src/boot_patch.rs` 的实现：

```rust
let re = Regex::new(r"(.* )?(\d+\.\d+)(\S+)?(android\d+)(.*)")?;   // 必须带 androidNN 标记
fn get_current_kmi() -> Result<String> { parse_kmi_from_uname().or_else(|_| parse_kmi_from_modules()) }
```

- `parse_kmi_from_uname()`：拿 `uname -r` = **`5.15.137-gc870e76526d2-dirty`** → 串里**没有 `androidNN`** → 失败
- `parse_kmi_from_modules()`：拿 `/vendor/lib/modules/` 里任一个 `.ko` 的 `modinfo` vermagic →
  vivo 自研模块同样不带 `androidNN` → 失败
- 源码里对这种情况的显式出路：`bail!("please specify kmi manually")`

**⇒ 与 vermagic、与符号都无关，纯粹是"厂商自研内核没有 GKI 的 KMI 标记"。**

## 3. 正解：`ksuinit`（`ksud late-load`）这条通路 —— 它自己就会改 vermagic

`ksud/src/android/late_load/mod.rs`：

```rust
let kmi = kmi.map_or_else(|| boot_patch::get_current_kmi()…, Ok)?;   // ← 就是上面失败的那步
let ko_name = format!("{kmi}_kernelsu.ko");
let ko_data = assets::get_asset(&ko_name)?;                          // ← 取自 ksud **内嵌资产**
ksuinit::load_module(&ko_data, params)?;                             // ← 用户态手工装载
```

`ksuinit/src/lib.rs` 里 `load_module()` 干的三件事：

1. **`extract_required_vermagic(kmsg)`** —— 先试装，从内核报的
   `version magic 'X' should be 'Y'` 里把**内核自己要求的串 Y** 抠出来
2. **`replace_module_vermagic(buffer, Y)`** —— 在**内存里**把 ko 的 `.modinfo` 改成 Y
   （重建 `.modinfo` 段并更新段头，**不要求等长**；磁盘上的 ko 一个字节都不动）
3. **`relocate_with_kallsyms`** —— 用 `/proc/kallsyms` 把未定义符号**在用户态逐个重定位**，
   然后才 `init_module()`

**这条路的含义**：
- ✅ vermagic 不用手工改（它自己改）
- ✅ **§1 里那 52 个"未导出"符号不再是问题** —— 只要名字在 `/proc/kallsyms` 里就行
  （本内核 `CONFIG_KALLSYMS_ALL=y`，169,722 个符号）
- ✅ 绕开 GKI 的 CRC / KMI 校验（本来就是为非 GKI 内核设计的）
- ⚠️ 真正剩下的风险：`struct module` 布局（见 §1）、CFI 编译器一致性、`init_module` 的
  SELinux 域权限（`u:r:shell:s0` 不一定有 `kernel_load_module`）

### 命令（设备侧，需 root）

```sh
# KMI 手动指定 —— 8 份内嵌模块里对应本机的是 android14-5.15
#   （ksud 内嵌：android12-5.10 / android13-5.10 / android13-5.15 / android14-5.15 /
#                android14-6.1 / android15-6.6 / android16-6.12 / android17-6.18）
ksud late-load --kmi android14-5.15 --package-name com.resukisu.resukisu
# 需要 root 域时加 --allow-shell；走 magica 时另有 --magica / --post-magica
```

**注意**：late-load 只从 **ksud 自己的内嵌资产**取 `<kmi>_kernelsu.ko`，**不接受外部文件**
⇒ 它用的是**官方为 GKI `android14-5.15` 编的那份 KSU 模块**。

⚠️ **实测：这条路在 vivo 137 内核上会 panic**（2026-09-28）。原因大概率是官方 GKI 模块是
按 GKI 的 `struct module` 布局/CFI 编的，ksuinit 的手工装载只保证**改 vermagic + 填符号**，
并不能把 `struct module` 布局差异也"改"掉。**结论：厂商内核上要用 `ksud insmod` 装
自己编的模块，不要用 `late-load` 装官方 GKI 模块。**

## 4. 两条路对照

| | 内核装载器（现在的 App 路径） | `ksuinit`（`ksud late-load`） |
|---|---|---|
| 命令 | `u0 ksud insmod /data/local/tmp/kernelsu-vivo.ko allow_shell=1` | `ksud late-load --kmi android14-5.15 --package-name …` |
| 装载哪个 ko | **本工程自编的 `kernelsu-vivo.ko`**（带 vivo 适配：vr.ko 绕过等） | ksud 内嵌的**官方 GKI `android14-5.15_kernelsu.ko`** |
| vermagic | **必须一致**（可等长改，或重建时对齐 LOCALVERSION） | 自动改（不用担心） |
| 未定义符号 | **必须全部由内核导出**（§1 末条存疑，52 个缺口） | kallsyms 用户态重定位（**不要求导出**） |
| 主要风险 | 52 个符号 + `struct module` 布局 | `struct module` 布局 + CFI 编译器一致性 + SELinux 域 |
| 附带能力 | 本工程那套（vrpatch / 内置 su / App 一键流程） | 官方 KSU 那一套（与本工程 App 的部署链不耦合） |

## 5. 建议的推进顺序（2026-09-28 修正）

1. ~~先试 `ksud late-load --kmi android14-5.15`~~ ❌ **实测 panic，别走**
2. ✅ **走 `ksud insmod` 装自编模块**（就是本工程 App 的那条路，也是唯一可行的）：
   模块用 5.15.178 的树编（clang 18 + LTO/CFI，与本项目既有产物一致），
   装的时候 ksud 会自动处理 vermagic 与符号。
3. 换固件后要重建的是**版本绑定常量**：exploit 走 `fw_profile.h`，
   `vrpatch.ko` 走 `VR_DETECT_OFFSET`，`unpatch.ko` 走 `CAP_BPRM_PA`
   （流程见 `VRKO_BYPASS.md` §5 与各模块源码里的 profile 分支）。
4. **正路（要长期支持某版）**：kernel.org 的对应 stable 源码 + 本机 `/proc/config.gz`
   + `LOCALVERSION` 对齐 + 与设备一致的 clang —— 只在需要重编内核本体时才做；
   现在（LKM 路线）**不需要**。
