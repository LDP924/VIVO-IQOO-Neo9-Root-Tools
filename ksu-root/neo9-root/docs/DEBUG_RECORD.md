# iQOO Neo9 (PD2338C) Root 调试记录 v2
> 更新至 2026-08-16 18:00。新对话先读本文件 + `cheese_progress.txt` + `cheese_panics.txt`。

## 1. 目标与设备
- vivo iQOO Neo9（PD2338C，16GB，Android 15，内核 5.15.178-gaacdc35637c4-dirty，BL 未解锁）
- 目标：SELinux permissive + caps-only root（uid 2000 + 全 caps，规避 vr.ko）
- PIN 768213；adb 仅 `C:\Users\qweio\AppData\Local\Programs\AYA\resources\adb\adb.exe`；USB 质量差
- **设备配置（luzhencheng520 研究确认）**：SPL 2025-10-01、MTE KASAN_HW_TAGS=y (async)、kptr_restrict=2、perf_event_paranoid=-1、CFI diag、PAC

## 2. 已确认的可用原语
1. **perf_event_open KASLR 泄漏 ✅**（perf_event_paranoid=-1）：采样内核 IP（16+ 个 0xffffffdfd2... 地址）——`neo9_research/poc/perf_leak2.c` 验证成功
2. **kgsl ioctl 全表**（nr 映射，vivo 重编号）：
   - nr 0x13 = DRAWCTXT_CREATE（8B 结构 {flags, id}）
   - nr 0x15 = MAP_USER_MEM（memtype=2 ADDR）
   - nr 0x4A = GPU_COMMAND（kgsl_gpu_command 0x40B，adrenaline.h 布局 ✓ 验证）
   - nr 0x2F = GPUMEM_ALLOC（24B {gpuaddr,flags,memtype,size,pad}）
   - nr 0x38 = TIMELINE_CREATE（16B {in u64, id out u32, pad}）
   - nr 0x40 = 提交类（接受 0x30 结构，ret=0）
   - dispatch：cmd&0xff 索引 kgsl_ioctl_funcs（16B/项），表基址 rodata+0xdad0
3. **GPU CVE-2025-21479 机制 ✅**：SDS（CP_SET_DRAW_STATE 0x43）误判 → SMMU_TABLE_UPDATE(0x53) 特权执行 → 任意物理读写（命中后）——**卡 pbuf 定位**

## 3. GPU 路线结论（已穷尽）
- **SMMU_TABLE_UPDATE 32 位截断**：候选 ≥0x100000000 必 panic（v675 固件 SDS 路径）→ 候选必须 <4GB
- **4GB 以下可用窗口**（DTB 全量分析）：W1 0x82800000-0x8a800000、W2 0xa3a80000-0xd4a80000、W3 0xd8800000-0xe6000000（头部实际 TZ 保留）、W4 0xe6800000-0xf7c00000、W5 0xfc400000-0xff400000
- **全部窗口密集扫描（184 候选 × 多轮）零命中**：pbufs（匿名 mmap 128MB-512MB）物理页都在高区
- **holdmem 11-12GB 塑造失败**：低区页被系统占用，空闲集中高区（buddy 分配方向问题）
- **kgsl gpumem 分配（nr 0x2F）物理页也在高区**（kgsl_gpuwrite.c 验证：34 候选零命中；SDS 后需重建 context 恢复 GPU）
- **_submit_hw_fence 溢出不可触发**：timeline syncpoint 在此设备不创建 dma_fence_array（内层循环 w22=1），K=8192 也不溢出
- **结论：GPU 路线在此设备受阻于内存分配偏好（低窗口仅 ~1.5GB 且被系统占用）**

## 4. CVE-2026-64560（当前主攻）
- **漏洞**：posix-cpu-timers UAF（非 leader exec 竞争）——`timer_delete` 的 pid_task 查到旧 leader 且 sighand==NULL → 跳过摘链 → free 仍入队的 k_itimer
- **受影响**：5.15.178 < 5.15.213 修复 ✓（CONFIG_POSIX_TIMERS=y、PREEMPT=y 确认）
- **触发窗口极窄**：delete 的 pid_task→lock_task_sighand 之间被抢占 + exec 完成 sighand=NULL
- **PoC**：`poc64560d`（统计版，errno 检测）、`poc64560e`（nanosleep 变体）——单实例 10-15 分钟零触发
- **当前**：4 实例 2 小时长跑中（pd1: 24t/12e、pd2: 12t/24e、pd3: 32t/8e、pd4: nanosleep 24s/12e）
- **利用前提**：触发 → k_itimer UAF（kmalloc-192）→ 堆喷（msg_msg ENOSYS、keyctl SELinux 挡——需替代）→ rbtree 操作 → 提权；KASLR 用 perf 泄漏
- 官方 PoC/补丁：`cve64560_*` 文件；分析：villager1314/CVE-2026-64560-Analysis

## 5. 已排除路线
- GhostLock（CVE-2026-43499）：UAF 形状不兼容 5.15.178
- vr.ko：攻击面封闭（自隐藏 + 门控回调）
- CVE-2025-38236：此设备已修补（EAGAIN）
- msg_msg/keyctl/io_uring 堆喷：不可用（ENOSYS/EPERM/未启用）

## 6. 关键文件
| 文件 | 作用 |
|---|---|
| `exploit_vivo.c` / `cheese_vivo` | GPU exploit（184 候选，pbuf 定位问题） |
| `poc64560d.c` / `poc64560e.c` | CVE-2026-64560 触发（统计版/nanosleep 版） |
| `kgsl_hwfence.c/hwfence2.c` | _submit_hw_fence 溢出测试（不可触发） |
| `kgsl_pbuf.c` / `kgsl_gpuwrite.c` | gpumem 物理位置验证（高区结论） |
| `kgsl_nrscan.c` / `kgsl_tlscan.c` / `kgsl_gmascan.c` | ioctl nr 枚举 |
| `neo9_research/` | luzhencheng520 仓库（README/KGSL-ANALYSIS.md/kgsl_text.txt 10MB 反汇编/poC） |
| `perf_leak2.c` | KASLR 泄漏 ✅ |
| `cve64560_*` | CVE-2026-64560 官方 PoC/补丁/分析 |
| `cheese_progress.txt` / `cheese_panics.txt` | GPU 扫描状态（保留） |
| `DEBUG_RECORD.md` | 本文档 |

## 7. 下一步
1. **CVE-2026-64560 长跑结果检查**（2 小时后：pd1-4.log 的统计 + uptime）
2. 若触发（esrch/einval 非零或设备重启）：进入利用开发（k_itimer 堆喷 + perf KASLR）
3. 若仍零触发：考虑调整触发策略（exec 频率、timer 参数扫描、绑核）
4. 备选：联系 Type010/villager1314 问触发经验

## 8. 里程碑：CVE-2025-21479 宽容 SELinux 验证成功（2026-08-16 深夜）
- **`exploit0_selonly`（Type010 exploit0 的 SELinux-only 版，即 cve21479_iqoo/exploit0.selinux_only，SHA256 4DB1272F...）在 PD2338C 上第 7/27 候选命中**（phyaddr 0xfeb00000，设备刚重启 5 分钟内存干净）
- 完整流程：GPU spray 6GB（384×16MB）→ SDS SMMU 劫持 → GPU R/W ready → stext=0xa8010000 → 物理写 selinux_state.enforcing=0 → 回读验证 enforce=0 → 正常退出（selinux-only 路径，跳过 vhangup）
- **结果：getenforce=Permissive，/sys/fs/selinux/enforce=0，功能验证 chcon relabelto 成功（shell→system_data_file，enforcing 下不可能），dmesg 可读（本设备 dmesg_restrict=0，唯一门是 SELinux syslog_read，enforcing 时不可读）**
- **运行后设备稳定 13+ 分钟**：无 GPU fault/panic（dmesg 中反复出现的 single_open WARNING 是 vivo soc_sleep_stats 模块既有 bug，与 exploit 无关）
- 经验：**刚重启（≤5min）内存干净时命中极快**；exploit 后无崩溃——但 fake TT0 仍残留，风险未完全排除
- 注意：dmesg 中 `SELinux: avc: ... permissive=0` 的 printk 行在翻转后仍出现，是 vivo/QCOM 日志路径问题；权威判据是 type=1400 audit 行（翻转后 permissive=1）+ 功能测试
- 遗留：permissive 是 RAM 态，重启即失；如需 root 用完整 exploit0（vhangup）或 exploit_vivo（ctx 恢复 + caps root）

## 9. KernelSU 越狱模式失败分析（2026-08-16 深夜，详见 `kernelsu_jailbreak_analysis.md`）
- **现象**：app 内点"自动越狱"后 ksud 全部特权命令 SIGSYS 崩溃（logcat：`Fatal signal 31 (SIGSYS), SYS_SECCOMP, syscall 142/146`，pc 0x3009b8）
- **原因**：越狱模式 = "adb root 后 insmod kernelsu.ko（KSU LKM）"（APK 逆向确认，ksu_manager.apk 已拉取）；app 沙箱内拉起 ksud → **seccomp 拦截 setuid(2)/reboot(2)**。permissive SELinux 与 seccomp/DAC/caps 正交，救不了
- **正确路径**：exploit root shell（无 app seccomp）→ 先中和 vr.ko → 再以 root 跑 ksud 越狱流程
- **vr.ko 新事实**：加载副本 = 静态分析同构建（srcversion 638A035334C256C3A38393E）；只创建 /proc/vrp（0640 root:root，shell 不可写）；[permanent] 不可 rmmod；initsize=0（状态字需在运行时 .data 重定位）→ 干掉方案 = GPU 物理写 patch 模块内存（详见分析文档 §3）
- 待验证：GKI 模块签名（insmod kernelsu.ko 是否 EPERM）、soft-reboot 是否真实重启

## 10. 完整版 exploit0 崩溃修复（2026-08-17，详见 `EXPLOIT0_FIX.md`）
- **现象**：完整版 exploit0（27 候选）命中后在 `rooting shell (ROOT_CMD=(interactive sh))` 处系统崩溃（无 `root shell ready`）
- **根因（内核镜像+symbols.txt 实测定案）**：exploit0 硬编码偏移来自 iQOO 11 Pro 构建，Neo9 上两处关键常量错误：
  - sys_call_table = **+0x212e648**（exploit0 用 +0x2130d08，该处是字符串 "d (struct…"）
  - slide 常量应为 **0x18352f8**（sct[0] = `__arm64_sys_io_setup.cfi_jt` 偏移；exploit0 用 iQOO 的 0x16970a9c）
  - vhangup = **+0x5dc898**（exploit0 用 +0x5dc8b8，差 0x20）；swapper_pg_dir = **+0x2ac4000**（差 0x2000）
  - → slide 算成垃圾 → stub 内嵌 prepare_kernel_cred/commit_creds 地址垃圾 → syscall(58) 执行 stub 时 blr 到垃圾 → panic
- **修复**：`fix_exploit0_neo9.ps1` 对 `cve21479_iqoo/exploit0` 打 7 处补丁（11 字节）→ **`cve21479_iqoo/exploit0_neo9`**（可直接 push 设备）
- **上机验证**：`adb push cve21479_iqoo\exploit0_neo9 /data/local/tmp/exploit0 && CHEESE_VERBOSE=1 ./exploit0`
  - 判据：verbose 显示 `capset: sct[0]=0xffffffc0098452f8 slide=0xffffffc008010000` → 到 `root shell ready (uid=0)`
- **遗留风险**：GPU fake TT0 未恢复（root 后尽快操作或改跑 exploit_vivo）；root 后先中和 vr.ko 再跑 KSU

## 11. exploit0 完整版崩溃真凶定案（2026-08-17 凌晨，dmesg 实时流实证，详见 `EXPLOIT0_FIX.md` §0）
- **不是偏移问题**（v1-v3 偏移/硬编码/NOP 读全部正确且上机验证），**是 fake TT0 GPU 路径在这台 Neo9 上不可靠**：
  - dmesg 实证 `GPU PAGE FAULT: addr=40403xxx (read translation fault)`，TTBR0=fake TT0（0xfebeb000/0xfeb30000）
  - 读路径几乎全 fault（sct 读回 0x272000002720 垃圾，`readback=1` 同源）；stub 写阶段也 fault（addr=40403898）
  - spray 未命中时扫描本身会崩机（SMMU 指向非 spray 物理页 → fault → kgsl recovery → 重启）
  - 疑似机制：fake TT0 页为可回收匿名页（memlock 上限 64MB 无法 pin），zram 高压下被换出/回收 → SMMU 走旧物理地址 → 垃圾/fault；pagemap 被限无法直接验证
- **版本状态**：v1（偏移）❌ / v2（硬编码地址）❌ / v3（NOP 全部 GPU 读）❌ / v4a（跳过 patch_capset 二分诊断）待冷启动验证
- **下一步**：冷启动窗口跑 v4a 二分；若 stub 写必崩 → 换 exploit_vivo（每候选 ctx 重建，崩溃安全）或 stub 写前重建 ctx 缩短时间窗
- 设备端 `exploit0_neo9` 当前 = v4a 诊断版（md5 5f0f6d15）；v3 = md5 a7cc3efb

## 12. 突破: vr.ko 反 root 绕过 + caps-root 成功 (2026-08-17 中午, MODE=10)

### 崩溃根因链 (kernel.img 反汇编 + vr.ko 逆向实证)
1. **MODE=0/2/5 命中后崩溃** = vr.ko 反 root 触发: 检测 `current->cred->euid(+0x14)==0` 且非 vrp 域 -> fatal (force_sig/取证) -> 系统崩溃
2. **MODE=1 不崩**: uid/euid 不变 -> vr.ko 不检测
3. **MODE=8 崩**: 进程内循环 GPU 累积毒化 (非 stub 问题; exec 方式无此问题)
4. **BTI 结论修正**: prepare_kernel_cred/commit_creds 入口无 brk (shadow CFI) -> blr 不触发 CFI; BTI=y 但 MODE=1 证明 stub 执行链安全 (cfi_jt 桩 b 进入)

### vr.ko 逆向要点 (本地 50MB 镜像反汇编)
- kprobe 目标: module_memfree / __vmalloc_node_range / 运行时注册(目标动态) + tracepoint 遍历注册
- 检测函数 0x2ecc (.text): `mrs x22,sp_el0; ldr x8,[x22,#0x798](cred); ldr w8,[x8,#0x14](euid); cbnz` -> euid!=0 返回; euid==0 -> 构造 "u:r:vrp:s0" context -> security_context_to_sid -> 比较 cred->security blob sid (+0x18+4) -> 不匹配返回 -1
- 域 = **vrp** (实测 u:r:vrp:s0 存在, /sys/fs/selinux/context 可转换; "vr:p" 拼法错误不存在)
- fatal 动作: force_sig 杀进程 + 落盘取证 (无直接 panic 调用)

### MODE=10 方案 (成功): euid 保持 2000 + fsuid/fsgid=0 + 全 caps
- BTF cred: uid@0x4 euid@0x14 fsuid@0x1c fsgid@0x20 cap_inh@0x28 perm@0x30 eff@0x38 bset@0x40 amb@0x48
- stub (20 words, zig 汇编生成): 只写 fsuid/fsgid=0 + caps=0x1fffffffff (41位) -> euid 不变 -> vr.ko 不触发
- **实测: CapEff=000001ffffffffff, 读 /data/adb /data/user_de/0, 设备稳定 (3次验证, uptime 600s+)**
- 系统分区 erofs ro + dm-verity -> 持久化需 RAM 态方案 (rootd)

### 进程内候选循环 (spray 一次覆盖 45 候选, 每候选 ctx 重建)
- 命中率 ~50%/轮 (候选可分配性随 boot 漂移); 冷窗口 (reboot 后 <3min) 命中快
- 每候选: 更新 spray 页 [1][2][4]=候选 -> ctx destroy+create -> DoWrite -> 1.5s -> 扫描

### rootd (caps 常驻 daemon)
- exec 后 effective 清零规则: root-owned 文件 (legacy root) 保留 effective; 非 root-owned 清零 (permitted 也清?)
- 方案: ROOT_CMD 里先 chown 0:0 rootd 再启动 -> rootd 保留全 effective
- rootd: Unix socket daemon, 每连接执行 sh -c 命令返回输出; rootc 客户端
- 状态: chown 方案待验证 (capset 方案失败 - exec 后 permitted 被清)

### 下一步
- [ ] rootd 全 caps 验证 (chown 方案)
- [ ] 内核访问能力探测 (kallsyms/kcore/kpageflags via rootc)
- [ ] vr.ko 中和 (GPU 物理写 patch 模块内存 -> 真 euid=0 root + KSU 可能性)
- [ ] kernelsu.ko 获取/编译 (MODVERSIONS CRC + vermagic 匹配; vr.ko do_init_module 检测)

## 13. 最终方案达成: 免解锁 caps-root + rootd daemon (2026-08-17 下午)

### 完整能力验证 (v14 最终版)
- CapEff=000001ffffffffff (全 41 位), Uid 2000 保持 -> vr.ko 不触发, 设备稳定 (uptime 450s+)
- ls /data/adb (700 root) 成功; 写 /data/roottest 成功
- mount remount / 被 fstab 限制 (Android 策略, 非权限问题)

### 技术链
1. **exploit_vivo_neo9** (STUB_MODE=10): euid 保持 2000 + fsuid/fsgid=0 + 全 caps (0x1fffffffff)
   - BTF: cred { uid@0x4 euid@0x14 fsuid@0x1c fsgid@0x20 cap_*@0x28-0x48 }
2. **CHEESE_PATCH_CAP=1**: GPU 物理写 cap_bprm_creds_from_file 入口 (VA 0xffffffc00894cdbc, 物理 0xa894cdbc)
   = mov w0,#0; ret -> exec 时不再清零 effective caps -> 命令进程保留全 caps
   (验证: cat /proc/self/status 显示 CapEff 全开, 设备不崩)
3. **CHEESE_DAEMON=1**: 内嵌文件队列 daemon (释放 spray 6GB 防 LMK, RSS 348KB)
   - rootd_cmd (B64 编码) -> daemon fork sh 执行 -> rootd_out
   - rootc 客户端: 内部 base64 编码 / B64: 直通 (调用端编码避开 adb 引号剥落)
4. **SELinux permissive** (selinux_state.enforcing=0 GPU 写, 0x314fa40)

### 使用流程 (每次开机)
```
reboot -> boot -> adb shell "cd /data/local/tmp && nohup sh -c 'CHEESE_STEXT_PA=0xa8010000 CHEESE_DAEMON=1 CHEESE_PATCH_CAP=1 ./exploit_vivo_neo9 > log 2>&1 &'"
-> 等 rootd_ready.txt -> rootc "命令" (或 PowerShell: [Convert]::ToBase64String -> rootc B64:xxx)
```

### 待办 (可选)
- [ ] KSU: kernelsu.ko (需匹配 vermagic/CRC) + vr.ko do_init_module 检测 (需中和)
- [ ] vr.ko 中和 (GPU 写模块内存 -> 真 euid=0) - 当前 caps-root 已够用
- [ ] 一键脚本封装 (reboot -> exploit -> rootc)
