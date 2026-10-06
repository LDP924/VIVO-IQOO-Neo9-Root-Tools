# 原版 KernelSU（tiann/KernelSU）LKM — PD2338 16.2.13.2 (5.15.197)

> 日期：2026-10-05
> 上游：github.com/tiann/KernelSU @ **08b2e9e45132**（2026-10-05，v3.3.0 之后 master）
> KSU_VERSION = **32661**（30000 + 2661 commits，Manager 握手用）
> 内核目标：`5.15.197-g708015331567-dirty`（vivo iQOO Neo9, OriginOS 6 / 16.2.13.2）

## 产物

| 文件 | 说明 |
|---|---|
| `kernelsu-vanilla-197.ko` | 6,850,552 B，vermagic `5.15.197-g708015331567-dirty SMP preempt mod_unload modversions vivo aarch64`（与设备逐字一致） |
| `SHA256SUMS` | `c25640904a493b8f897d2983955217a86060e61221874d6e983aee6042a6d3cb` |

## 编译参数（复现用）

- 混血树：`~/kernel/vivo-neo9-16`，`drivers/kernelsu` → 软链 `~/kernel/KernelSU-upstream/kernel`
- 上游 clone：`~/kernel/KernelSU-upstream`（完整历史，版本号依赖 rev-list count，勿删 .git）
- 工具链：NDK r27 clang 18（`~/toolchains/ndk-r27/.../linux-x86_64/bin`），`LLVM=1`
- 命令：
  ```bash
  export PATH=~/kernel/fakebin:<ndk>/bin:/usr/bin:/bin:$PATH
  make -C ~/kernel/vivo-neo9-16 ARCH=arm64 LLVM=1 \
    KCFLAGS="-I security/selinux/include/generated" \
    M=~/kernel/KernelSU-upstream/kernel modules
  ```
- fake pahole（`~/kernel/fakebin/pahole`）：`--version` 回 v1.29、其余 exit 0（保 `BTF_MODULES=y` 检测过，模块 BTF 合并跳过——与 10-04 ReSukiSU 版同配方）
- 209 个未解析符号 = 预期（ksud insmod 时 kallsyms→SHN_ABS 填充；MODVERSIONS CRC 缺 178 symvers，modpost warning 不阻塞）

## 加载与配对注意

1. **insmod 通道**：`ksud insmod kernelsu-vanilla-197.ko`（与 ReSukiSU 版相同，必须 root + 无 seccomp 环境，即 GhostLock root 后的 `./su -c`）。
2. **已打包进 GhostLock App**：`GhostLock-v1.2-vivo-neo9-kernelsu-original-arm64-release.apk`（ghostlock-deliverables/）内嵌本 ko + 官方 ksud v3.3.0 + vrpatch.ko + 官方 Manager APK v3.3.0（32601）；root 脚本顺序 = 先 `pm install -r` Manager → `ksud insmod` 内核驱动 → `ksud insmod` vrpatch。
3. **与 ReSukiSU 版互斥**：同一内核同时只能挂一个 KSU（supercall/prctl 槽位冲突）。切换前先重启清掉旧模块；设备上已装的 ReSukiSU Manager 可卸载。
4. 未装机验证。首验清单：App 一键 Root → 日志看 `manager install exit=0` → `insmod exit=0` → `vrpatch insmod exit=0` → 原版 Manager 检测到内核（版本 32661）→ `su -c id` → 网络（Enforcing + packet sepolicy patch）。

## vrpatch 结论：不需要重编 ✓

`ghostlock-ksu-full-kit/ksu-module/vrpatch.ko`（sha256 见 full-kit）与 KSU 版本**零耦合**，理论判断成立：

- `modinfo depends` = **空**（不依赖 kernelsu 或任何模块）
- 未解析符号仅 7 个，全部为内核本体符号：`set_memory_rw/ro`、`_printk`、`kick_all_cpus_sync`、`caches_clean_inval_pou`、`__ubsan_handle_cfi_check_fail_abort`（CFI 运行时）+ `find_module`（未导出 → ksud kallsyms 填充，机制同上）
- 无任何 `ksu_*` 符号/字符串引用（strings 命中的仅为构建机路径）
- vermagic 已与 197 匹配；只关心设备 vr.ko 的 `.text+0x2ecc`，与内核里挂的是 ReSukiSU 还是原版 KSU 无关
