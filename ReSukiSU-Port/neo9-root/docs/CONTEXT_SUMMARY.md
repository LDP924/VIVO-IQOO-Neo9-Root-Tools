# 会话压缩摘要（2026-08-18）

> ⛔ **已废弃 —— 不要按本文操作。** 这是 2026-08-18 的上下文压缩点，描述的是**当时的
> Magisk 授权层方案**，而该方案后来被整个放弃、改为 ReSukiSU(KernelSU)（见
> `docs/RESUKISU_FULL_FLOW.md` 与 `neo9-root/docs/SU_BUILTIN.md`）。本文提到的
> `neo9_root/magisk/`、`Magisk-v26.4-src.tar.gz`、`smali-mod.patch`、`NOTICE`/`COPYING`
> 等文件**在本仓库里都不存在**，许可口径（"混合许可 原创 MIT + Magisk 衍生 GPL-3.0"）
> 也早已作废 —— 现行口径见仓库根 `README.md` 的「授权与使用」章节。
>
> 保留原因：里面的**设备事实与踩坑结论**（KASLR slide 确定性、magiskd 的 `is_client()`
> 同 inode 校验、toybox mount 在 rootd 管道环境挂起等）仍有参考价值。
> 找当前状态请看 `docs/INDEX.md`。

> 用途：上下文压缩点。后续会话从此文件 + 各 docs 恢复状态。

## 一、项目与设备

- 设备：vivo iQOO Neo9 (PD2338C)，Android 15（橘子 5），内核 `5.15.178-gaacdc35637c4-dirty`，Adreno 740，KASLR slide 确定性（重启后相同），stext_pa=0xa8010000
- 工作区：`D:\payload-dumper-go`（`neo9_root/` = root 工具链项目，`_ksu_build/` = 实验归档区）
- 临时 root 方案：CVE-2025-21479 (Adreno SDS) GPU exploit → rootd daemon（euid=2000 + 全 caps 防 vr.ko）→ rootc 客户端（B64 文件队列协议）
- 用户红线：**不动系统/根分区**（erofs + dm-verity green 已验证）；不 insmod（vr.ko 重启）；避免 setenforce
- 当前设备状态：临时 root 有效（rootd READY），Magisk 授权层已部署（magiskd 运行中），Manager 修改版已安装

## 二、已完成成果（按模块）

### 1. Magisk 授权层（完整可用）
- magiskd v26.4 部署于 `/debug_ramdisk/.magisk/`（RAM tmpfs），`/data/adb/magisk.db` 存策略
- **关键坑位**：magiskd 的 `is_client()` 校验客户端与自身**同一 inode**（st_dev+st_ino）→ su 必须与 magiskd 二进制硬链接（否则 "Access denied"）
- **修改版 Manager**（`neo9_root/magisk/Magisk-v26.4-mod.apk`）：重签名 + 3 处 smali 修改（libsu su 路径改 `/debug_ramdisk/.magisk/su` + root shell PATH 注入）→ Manager 完整可用（首页/超级用户/弹窗）
- 重打包工具链：baksmali/smali/apksig（`_ksu_build/tools/` + `smali_libs/`），签名密钥 `magisk-mgr.keystore`（alias=magisk, pass=magiskpass），对齐用 python zipalign（`_ksu_build/pytools/`）
- 一键部署脚本：`neo9_root/scripts/deploy_magisk.sh`（6 步：tmpfs 挂载→二进制→su 硬链接→magiskd→DB 初始化→验证）
- 许可合规：混合许可（原创 MIT + Magisk 衍生 GPL-3.0，`NOTICE`/`COPYING`/`Magisk-v26.4-src.tar.gz`/`smali-mod.patch` 齐全）
- **部署脚本最新 bug 修复**：`[1/6]` 原用 `mount | grep -q` 判断挂载点 → toybox mount 在 rootd 管道环境挂起（用户实测卡在 [1/6] 标题后）→ 已重写为 `touch` 可写测试 + `timeout 10` 防挂起（**注意：修复版尚未推送到设备，且 rootd 可能被上次卡死占用，需先 pkill exploit 恢复**）

### 2. tsu 适配（完成）
- `neo9_root/tsu/`：Termux tsu 8.6.0 修改版（SU_BINARY_SEARCH 加 `/debug_ramdisk/.magisk/su`）+ 原版 + patch + ISC 许可
- 已部署设备（Termux $PREFIX/bin/tsu 替换，原版备份 tsu.bak）；apt-mark hold 未执行成功（root 环境无 apt 源）

### 3. vr.ko 逆向（极深入，文档 `neo9_root/docs/vrko_static_analysis.md`）
- **模块**：`vr 45056 [permanent] Live`（vendorboot 模块，vermagic 5.15.178-gaacdc35637c4-dirty vivo）
- **架构**：3× kprobe（do_init_module/avc_has_perm/module_memfree）+ tracepoint（initcall_finish）+ hfm 文本补丁引擎 + 块设备写栈（bio）
- **混淆手法**：敏感符号名/节点名用 **mov/movk 立即数拼装**（init_free_wq、module_mutex、vr_fss 等）；.rodata 密文块非 xor（暴力无果）
- **接口面**（ics_* kallsyms 动态解析池）：proc_mkdir/proc_create、sys_call_table、set_memory_x/rw/ro、stop_machine、probe_kernel_read/write、selinux 全套（avc_has_perm_noaudit/avc_update_node/selinux_state）、do_mount、kstrtoint_from_user
- **SELinux 自定义类**：cs_vrp、cs_isroot、cs_proc_mounts、cs_selinux_mnt、cs_block_device_file_perms、cs_enable_disable_mount_flag/record
- **/proc/isroot/isroot**（0644 root 可读写）：输出 `mount state:0`（实时计算只读值），**写 abc/0/1 均无效果**（实测安全），长输入 rc=1
- **/proc/vrp**（0640）：读 EPERM（cs_vrp 类主动检查，permissive 也拦）—— 属 vr.ko（非独立驱动）
- **apanic 配置块**：`/dev/block/by-name/apanic` 偏移 0x9f0000（分区尾部 -0x2000）= `0xdead4653 | 1 | 1 | 0x2cc` + 写计数 + vr.ko 加载地址（本次 boot 写入）→ 每次 boot 重写，不可作持久开关
- **userdata 配置链路**：bootargs `PARTABLE=L=userdata/apanic` 解析（**正常 boot cmdline 无 PARTABLE → 分区打不开 → /proc/vr_fss 不创建**）；回退 "misc" 分区
- **结论**：vr_fss 命令接口（clr_run_fatal/clr_load/rinvrp/skip:vrr/skip:ssc + 数字命令表）在正常 boot 不可达；门控 magic 由分区配置决定

### 4. 其他分析
- **CVE-2026-43499 (GhostLock)**：内核 rtmutex LPE（rt_mutex_waiter 利用），已有工作区大量适配材料（`宽容模式下提取的...-适配cve-2026-43499/`）：target.h（vivo_pd2338c-perm）符号/BTF/物理布局推导完成 90%，编译 7/7 PASS；待真机项：VMEMMAP_START、MM_OWNER_OFF、SKB_DATA_DELTA、竞争时序 —— **当前 root 环境（kallsyms 可见 + rootd 读内存）可直接实测解决**
- **CVE-2025-21479 影响面**：骁龙 8 Gen 2/3（A740/A750）未修复驱动；Neo9 驱动 0676.63 (2024-08) 可打；SPL 日期与驱动修复无关（Neo9 SPL 2025-10 仍可打）；Neo10 (SM8650) 需实测驱动版本
- **LXC 不可行**：内核 `CONFIG_PID_NS is not set`（vivo 禁用）→ 标准 LXC 出局；chroot 方案可行
- **kernelsu.ko**（resukisu_apk/，12MB 调试版）：vermagic `g3dc7c01077b8-dirty` 不匹配（设备 `gaacdc35637c4`）+ 缺 vivo 标志 + __versions 空 → 不可用；需重编
- **KernelSU 路线**：物理写绕过 vr.ko（先 NOP fatal 重启→再 tracepoint state 清零→再 kprobe handler ret）→ insmod → 理论可行，卡在物理写稳定性（neutralize_vr 遗留）

## 三、关键命令速查

```sh
# root 命令执行
/data/local/tmp/rootc "命令"          # rootd 环境 (euid=2000+全caps)
/data/local/tmp/su --u0 -c "命令"     # 真 root (euid=0, 经 u0)
# su 测试
/data/local/tmp/su_test/su -c id      # → uid=0(root)
# magisk DB 查询 (真 root)
/data/local/tmp/u0 /debug_ramdisk/.magisk/magisk --sqlite "SELECT ..."
# 部署 magisk
/data/local/tmp/rootc "sh /data/local/tmp/deploy_magisk.sh"
# exploit 恢复 (rootd 卡死时)
pkill -f exploit_vivo_neo9; # 重新运行 exploit (run_rootc_loop.ps1)
```

## 四、文件索引

- `neo9_root/README.md` — 项目主文档（含 Magisk/tsu 章节）
- `neo9_root/docs/vrko_static_analysis.md` — vr.ko 全量逆向（10 节，含设备实测）
- `neo9_root/docs/magisk_auth_plan.md` — Magisk 部署完整记录
- `neo9_root/magisk/` — magisk 二进制/修改版 APK/密钥/许可
- `neo9_root/tsu/` — tsu 修改版
- `neo9_root/scripts/deploy_magisk.sh` — **已修复 [1/6] 挂起 bug（未推送设备）**
- `_ksu_build/README.md` — 实验区索引（tools/smali_libs/pytools/src/bins 等）
- `_ksu_build/` 下分析脚本：vrko_*.py（rela/params/disasm/btf/xor 等）、kimg_*.py（kernel.img 分析）
- `宽容模式下提取的...-适配cve-2026-43499/` — GhostLock 适配材料

## 五、未完成任务/下一步候选

1. **部署脚本修复版推送设备 + rootd 恢复**（用户远程执行中，当前 adb 未连接）
2. **GhostLock 适配收尾**：用当前 root 环境（kallsyms 可见）实测 VMEMMAP_START 等 → 完成 target.h → 编译 → 真机测试
3. **KernelSU 路线**（可选）：物理写拆 vr.ko → insmod（需重编 ko）
4. **vrp/isroot 深度**（可选）：写回调消费逻辑（已实测写无效果，价值低）
5. 设备上 `apt-mark hold termux-su`（防止 tsu 修改被覆盖）

## 六、当前设备已知状态（最近一次实测）

- rootd：READY（su/rootc 可用）
- magiskd：运行中（部署过）
- /debug_ramdisk/.magisk/：magisk+su 同 inode、socket、config、pts
- /data/adb/magisk.db：policies（2000/10000/10325 ALLOW）+ settings（root_access=3, multiuser_mode=1）
- Termux：tsu 修改版已装，uid=10325 预授权
- /proc/isroot/isroot：mount state:0（写无效果）
- 系统分区：erofs 只读 + verity green（未动）
