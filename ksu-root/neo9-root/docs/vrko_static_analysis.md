# vr.ko 静态逆向分析：接口面与"开关"候选

> 对象：`VIVO_Neo_9_PD2338C_GKIv1_5.15.178/vendorboot_For_PD2338C_extracted/lib/modules/vr.ko`（389952 字节，aarch64，CONFIG_CFI_CLANG）
> 方法：ELF section/重定位/符号表联合分析 + capstone 反汇编（纯离线）
> 日期：2026-08-18

## 1. 总体架构（符号表 + 调用图证据）

```
init_module (.init.text, 0x141ac 字节, 主体逻辑)
├── kthread_create_on_node      → 后台监控线程 (kthread_should_stop 循环)
├── register_kprobe ×3          → do_init_module / avc_has_perm / module_memfree (已实证)
├── for_each_kernel_tracepoint + tracepoint_probe_register_prio
│                                  → initcall_finish 等 tracepoint hook
├── name_to_dev_t → blkdev_get_by_dev → bio_init/bio_add_page/submit_bio_wait ×2
│       → set_blocksize → blkdev_issue_flush        ← fatal 日志写盘 (bio 栈)
├── proc_create + proc_set_user + single_open + simple_write_to_buffer
│       → 0x3634 分发函数 (读/写处理, 大状态机)
├── of_find_node_opts_by_path / of_property_read_string / of_find_compatible_node ×3
│       → 设备树配置 (日志目标/保留内存)
├── memremap                    → 保留内存映射 (ics_all_info_*)
└── 运行时 (.text): register_kprobe (非 init 期) + unregister_kprobe
```

## 2. 混淆手法（关键发现）

| 手法 | 证据 | 含义 |
|---|---|---|
| **mov/movk 立即数拼装符号名** | 0x2828: `mov x9,#0x6e69; movk #0x7469,<<16; movk #0x665f,<<32; movk #0x6572,<<48` → `init_fre` + `e_wq` → **"init_free_wq"**；0x28fc 拼出 **"module_mutex"**；另有 **"__SCK___"** | 敏感内核符号名**不进字符串表**，在栈上拼装后走 kallsyms 动态解析（ics_kallsyms_* 封装 0x3634 前身） |
| 明文保留 | `'vr: init,e,%d,%d,%lx,%lx'`、`'fatal:%d'`、`'Raw_Dmp!'`、`'ufs_qcom sdhci_msm'`、`RS_*_BOOT` 枚举、`cs_vrp` 等 | 格式串/枚举/DT compatible 明文；**节点名、分区名、符号名混淆** |
| .rodata 密文块 | `db d3 ea d9 dc c7 df c4 f0 dc c8 cf c4 d8 cd 00 92 d8 de cc 96 00 ...`（单字节 xor 无效） | 非普通字符串；疑为**加密的设备路径/头部常量**（fatal 写盘目标），待运行时确认 |

## 3. "开关"候选（按可利用性排序）

### 3.1 启动模式分支（最像总开关）
`RS_NORMAL_BOOT / RS_RECOVERY_BOOT / RS_FACTORY_BOOT / RS_SURVIVAL_BOOT / RS_UNKNOWN_BOOT`
- 0x1028 区域解析 bootargs（`mov w2,#0xe`=14 / `#0xb`=11 长度，比较 `' '`(0x20)/`'!'`(0x21)）
- **含义**：vr.ko 行为随启动模式分支 —— 恢复/工厂模式可能关闭或降级检测

### 3.2 SELinux 自定义类权限（/proc/vrp EPERM 的根源）
`cs_vrp`、`cs_vrp_context`、`cs_enable_mount_flag`、`cs_disable_mount_flag`、`cs_enable_mount_record`、`cs_disable_mount_record`、`cs_block_device_file_perms`
- vr.ko 注册了自己的 security class + 权限（enable/disable mount flag/record）
- 实测 uid=0+全 caps+permissive 下 /proc/vrp 仍 EPERM → **hook 内主动检查该 SELinux 类**（permissive 只跳过 avc，不跳过 LSM hook 内部检查）

### 3.3 proc 可写节点（0x7058 proc_create + simple_write_to_buffer）
- 节点名**静态不可见**（.init.rodata+0x1380 引用处为空 → 运行时拼装/生成）
- fops 含 single_open（读）+ simple_write_to_buffer（写）→ **存在用户态可写接口**
- 0x3634 分发函数：大状态机（参数校验 EINVAL、循环、函数指针表分发）→ 写内容的格式/动作待还原
- proc_set_user → 节点属主/权限由代码设置

### 3.4 fatal 写盘（与 apanic 命令同机制）
- 完整 bio 栈：`name_to_dev_t`(解析设备名) → `blkdev_get_by_dev` → `bio_init` + `bio_add_page` + `submit_bio_wait` ×2 → `set_blocksize` → `blkdev_issue_flush`
- 目标设备名来源：设备树（of_* 路径）或 bootargs
- **与用户侧 `printf '\370\037...' | dd of=apanic` 属于同一存储协议的两端**（vr.ko 生产，用户态工具维护）

## 4. 静态无法最终确认项（需设备端只读确认）

1. proc 节点名（运行时拼装）→ 设备端 `ls /proc` 对比 / 读 /proc/vrp 上下文
2. 0x3634 的输入格式与动作 → 设备端尝试写（只读探测）
3. .rodata 密文块内容 → `/sys/module/vr/sections/.rodata` 给地址 → rootd 读内存（kallsyms 可用）
4. 启动模式实际分支行为 → 设备端重启到 recovery/工厂模式观察（风险：需离开当前系统）

## 5. 结论

- **能发现开关**：vr.ko 的接口面（proc + debugfs + SELinux 类 + DT + bio）全部可从符号表/重定位/反汇编定位
- **最可能的"开关"形态**：① 启动模式（recovery/factory 行为降级）② proc 可写节点的特定输入（0x3634 分发）③ SELinux 类权限的绕过条件
- **混淆已破解一半**：movk 拼装符号名可还原（本分析已解出 init_free_wq/module_mutex/__SCK___）；.rodata 密文需运行时读
- 用户给的 apanic dd 命令：与 vr.ko 的 bio 写盘链路是同一协议的互补面，非 vr.ko 开关本身

## 7. 重大进展：/proc/vr_fss 管理接口完全还原（第二轮静态分析）

### 7.1 proc 节点：`/proc/vr_fss`
- 节点名通过 movk 立即数拼装（0x702c-0x7054）：`0x7276='vr' + 0x665f='_f' + 0x7373='ss'` → **"vr_fss"**
- `proc_create("vr_fss", 0x1b4=0644, NULL, fops)` + `proc_set_user(0, 2000)`（uid=0, gid=2000）
- fops：`single_open`（读 = fatal 信息）+ `simple_write_to_buffer`（写 = 命令接口）
- 注：此前实测的 `/proc/vrp` EPERM 是**另一接口**（或不同权限路径）；vr_fss 写入口为 0644 root:2000

### 7.2 命令分发表（.init.data+0x1500，每项 16 字节 = key + handler）
key 为 `(类型, 命令号)` 对，共 20+ 项，handler 全部指向 .init.text 内函数：

| key (type:cmd) | handler | 推测语义 |
|---|---|---|
| 1:0x14-0x1a, 1:0x97 | 0x19b0/0x19b8/0x19c0/0x19c8/0x19d0/0x19e0 | 一组 kprobe/检测控制 |
| **1:0x06** | 0x1570 | **"initcall_finish" tracepoint 操作**（handler 内 movk 拼出该符号名，调用 tracepoint 注册封装） |
| 1:0x0b | — | — |
| 2:0x1e-0x27（10 项连续） | 0x1560/0x1688/0x17b8/0x1960...0x1990 | 大批量命令（hook 启停） |
| 3:0x28, 5:0x29 | 0x1748/0x1998 | — |
| 2:0x5a, 2:0x7d | 0x19a0 | — |
| 0x0c, 0x19, 0x1c, 0xa2 | 0x17f8/0x19d8/0x19e8 | 特殊命令 |
| 1:0x18 | .bss+0x48 | 状态存储 |

### 7.3 写接口输入格式（write 回调 = 0x3634 宿主，0x7190 区域）
1. 最多读 **31 字节**（0x1f）
2. 过滤控制字符（< 0x21 替换为 '\0'）
3. mutex_lock 后解析
4. **关键字匹配**：`sp[0]=='c'(0x63) sp[1]=='l'(0x6c) sp[2]=='r'(0x72) sp[3]=='_'(0x5f)` → **"clr_" 前缀命令**（如 clr_xxx）
5. 数字命令：`(type, cmd)` 对 → 0x3890 jump table（8 种 case：读/写/比较/调用 handler）

### 7.4 分发执行链
```
写 /proc/vr_fss
  → simple_write_to_buffer (0x71b0)
  → 0x3634: 循环解析输入块 (0x2dbc 解析器, 0x4fe4 主解析)
  → 匹配分发表 key=(type,cmd) → handler
  → handler 操作: kprobe 注册/注销、tracepoint (initcall_finish 等)、状态管理
```

### 7.5 意义
- **vr.ko 存在用户态可写管理接口**（root 可写：0644 + permissive 下 SELinux 不拦，除非 hook 内主动检查 cs_vrp —— write 回调静态未见该检查）
- 这就是"开关"的实体：**特定命令可动态启停 vr.ko 的检测 hook**
- 下一步（设备端只读/低风险）：`echo` 探测 /proc/vr_fss 写入口的返回码（如空写/非法命令应返回 EINVAL，合法命令前缀 "clr_" 观察行为）

## 8. 第二轮补充：门控机制与完整命令协议（第三轮静态分析）

### 8.1 门控结构（"魔法门"）
- 全局状态结构（.init.data+0x0，sym507，init 后驻留副本），所有命令入口先检查：
  ```
  [0x00] magic = 0xdead4653   ("FS" + 0xdead 前缀)
  [0x04] flag1 = 1
  [0x08] flag2 = 1
  [0x10] mode  = 0x2cc (716)
  ```
- **静态数据中不存在该 magic**（.data/.rodata 均无 0xdead4653 字节）→ 默认锁定
- 检查点：0x5b44（init）、0x6b58（init 写盘）、0x715c（write 回调）、0x76f8（rinvrp handler）
- **解锁来源**：init 流程（0x5a00 区域）movk 拼装 **"userdata"** 与 **"apanic"** 分区名 → 0x2560 分区查找 → 0x6e5c 块设备读写（w3=0x2cc）→ **门控配置存于 userdata 分区，magic 由其内容决定**

### 8.2 完整命令协议（写 /proc/vr_fss，门控通过后）
| 命令 | 目标函数 | 语义 |
|---|---|---|
| `"clr_run_fatal"` | 0x7574 | **清 fatal 日志（apanic 分区）** —— 与用户侧 `printf...dd of=apanic` 等价的内核实现 |
| `"clr_load"` | 0x7628 | 加载 |
| `"rinvrp"` | 0x76e0 | 门控维护动作：3 个操作（0x5fa8/0x61d0/0x625c）+ 全局 flag bit1/bit2 + 0x1fe8 |
| `"skip:vrr"` | 0x74e0 | **全局 flag bit5 置位**（skip 机制开启 —— 对应 fatal 格式串 'skip:%lx'/'skip_state:%d'） |
| `"skip:ssc"` | 0x74e0 | 全局 flag bit5 清除 |
| `(type,cmd)` 数字命令 | 分发表 20+ handler | kprobe/tracepoint（initcall_finish 等）启停 |
| `"set"` / `"tst"` | 回退 | 无动作 |

- 输入规范：≤31 字节、过滤控制字符、`"clr_"` 前缀 / `"skip:"` 前缀 / 数字对
- 写回调 0x7120 开头强制门控（0x7150-0x7190），不通过直接拒绝

### 8.3 与用户命令的对应
- 用户给的 `printf '\370\037...' | dd of=apanic` = **手动操作 apanic 分区存储头**
- vr.ko 内部 `"clr_run_fatal"` 命令 = 同一功能的**内核原生实现**（bio 栈写 apanic）
- **真正接近"关闭反 root"的开关**：`"skip:vrr"`（全局 skip flag，需门控解锁后生效）

### 8.4 待设备端确认（只读/低风险）
1. `cat /proc/vr_fss` —— 读 fatal 信息（single_open 输出）
2. 门控结构当前状态：magic 是否已解锁（若 userdata 配置已解锁，写命令可用）
3. `echo skip:vrr > /proc/vr_fss` 返回码（若门控已开）→ 观察检测行为是否变化（⚠️ 高风险项，需谨慎）

## 9. userdata 配置位置（最终锁定，第四轮静态分析）

### 9.1 配置读取链路（init 流程 0x5a00 区域）
```
movk 拼装 "userdata" / "apanic" 分区名
  → 拼 bootargs 参数 "PARTABLE=L=<分区名>"（0x257c: 'P','A','R','T','A','B','L','E' + '=' + 'L' + '='）
  → 0x2560: name_to_dev_t（解析 PARTABLE=L=userdata → dev_t）
  → blkdev_get_by_dev（打开分区，记录容量/块大小/扇区）
  → 0x6e5c（通用块设备读写，bio 栈 + 可选 flush）:
      偏移 x1 = -0x2000（标志非 0）或 -0x10000（标志为 0）← 分区尾部向前
      长度 w3 = 0x2cc (716) 字节，w4=0（读，不 flush）
  → 读出配置块存入全局结构（0x5000 页）
  → 0x186c 拷贝 24 字节（配置头?）
  → 校验门控字段: [0]=0xdead4653 [4]=1 [8]=1 [0x10]=0x2cc
```

### 9.2 最终答案：配置块位置
| 项 | 值 |
|---|---|
| 分区 | **userdata**（f2fs 数据分区，经 bootargs PARTABLE=L= 解析） |
| 偏移 | **分区尾部 -0x2000（8192）字节**（或 -0x10000，取决于配置标志） |
| 大小 | **0x2cc（716）字节** |
| 门控字段 | [0x00]=magic `0xdead4653`、[0x04]=1、[0x08]=1、[0x10]=0x2cc |

### 9.3 设备端只读验证方法
```bash
# 读 userdata 尾部 8KB 起点处 716 字节 (只读, 安全)
dd if=/dev/block/by-name/userdata bs=1 \
   skip=$(( $(blockdev --getsize64 /dev/block/by-name/userdata) - 8192 )) \
   count=716 2>/dev/null | hexdump -C | head
# 若偏移 0x00 处出现 53 46 ad de → magic 存在 → 门控已解锁 → /proc/vr_fss 命令可用
# 否则配置块不在该偏移/未初始化 → 门控锁定
```
⚠️ 只读验证安全；**写 userdata 尾部可能损坏 f2fs，禁止直接写**（若需解锁，应先备份并理解 f2fs 尾部保留区语义）。

### 9.4 命令接口汇总（门控解锁后）
- 文本命令（≤31 字节）：`"clr_run_fatal"`（清 apanic fatal 日志）、`"clr_load"`、`"rinvrp"`、`"skip:vrr"`/`"skip:ssc"`（全局 skip flag）、`"set"`/`"tst"`
- 数字命令：`"+0x..."` 十六进制前缀（0x4fe4 解析器）→ (type,cmd) 分发表 20+ handler

## 10. 设备端实测结论（2026-08-18，只读）

### 10.1 实测结果
| 项 | 结果 |
|---|---|
| vr 模块 | ✅ 已加载：`vr 45056 0 [permanent] Live 0xffffffd2385ab000` |
| /proc/vr_fss | ❌ **不存在**（门控接口未创建） |
| /proc/vrp | ✅ 存在（`-rw-r----- root root`）—— 由**独立的 vrp 内置驱动**创建，非 vr.ko |
| bootargs PARTABLE | ❌ **cmdline 无 `PARTABLE=L=`**（有 `vivoboot.normalboot=true`） |
| apanic 配置块 | ✅ **存在**（偏移 0x9f0000，即分区尾部 -0x2000）：`53 46 ad de | 01 00 00 00 | 01 00 00 00 | cc 02 00 00` = magic/flag1/flag2/mode **全匹配**，且 [0x28] 存当前 vr.ko 加载地址 → **本次 boot 写入** |

### 10.2 为什么 vr_fss 不存在（根因链）
```
cmdline 无 PARTABLE=L=userdata
  → 0x2560 的 name_to_dev_t("PARTABLE=L=userdata") 解析失败
  → userdata 分区打不开（0x6000 页结构无有效句柄）
  → 0x6b28 函数（0x55cc/0x55f0 调用）内 0x6c84 写失败（cbnz → 0x6d40）
  → 0x6c8c 的 proc_create("vr_fss") 被跳过
→ /proc/vr_fss 不存在（正常 boot 恒成立；PARTABLE 仅在工厂/特定 boot 注入）
```

### 10.3 apanic 配置块写入路径（正常 boot 也会执行）
- 0x5000 页结构 = apanic（或回退 **misc** 分区，0x5e14 拼出 "misc"）句柄
- 0x5d80/0x5ef8/0x6c84 多次 `0x6e5c` 写（w3=0x2cc 配置块 + 0x1000 数据块，偏移 ±0x2000/0x10000）
- 配置块 [0x15] = **写计数**（实测 =1 → 本次 boot 写 1 次）
- 配置块含 vr.ko 运行时地址 → **每次 boot 重写** → 修改 apanic 配置块会被下次 boot 覆盖

### 10.4 最终结论（"开关"问题闭环）
1. **门控命令接口 /proc/vr_fss 在正常 boot 不可达**（PARTABLE 依赖，bootloader 侧注入）
2. **apanic 配置块是状态记录**（magic+计数+地址），每次 boot 重写，**不可作为持久开关**
3. 真正可操作面（若继续）：cmdline 注入（不可行）/ vrp 驱动（独立模块，另挖）/ 利用 vr.ko 写盘路径做观测（fatal 触发条件研究）

## 11. 产物文件

- `vrko_calls.txt` 全部调用点（.init.text/.text）
- `vrko_params.txt` 调用点参数字符串
- `vrko_full_disasm.txt` 完整反汇编（capstone）
- `vrko_adrp.txt` 全部 ADRP 引用
- `vrko_analysis.txt` 字符串分类
- 分析脚本：`vrko_rela.py / vrko_params.py / vrko_full.py / vrko_ptr.py / vrko_syms.py / vrko_proc.py / vrko_xor.py`
