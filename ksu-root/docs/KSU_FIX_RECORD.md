# KernelSU (ReSukiSU) 移植 iQOO Neo9 — 调试记录 (2026-08-19)

## 最终成果 (已验证)

- `kernelsu 221184 0 - Live` — LTO+CFI 构建的模块, init 完整执行
- 超级调用全部可用: reboot-fd (fd=3), GET_INFO (version=35077, LKM|LATE_LOAD, uapi=2), GRANT_ROOT (uid 2000 → uid=0)
- sucompat execve hook: uid 2000 执行 /system/bin/su → root + u:r:ksu:s0 域 → 重定向 /data/adb/ksud
- `adb shell /data/local/tmp/su_ksu -c id` → `uid=0(root) u:r:ksu:s0` (无需 exploit daemon!)
- ReSukiSU Manager: `KsuCli: install result: true` — 检测到 KernelSU 已安装

## 根因: struct module 布局错位 (8 字节)

**现象**: 模块能加载 (Live) 但 init 从不执行 → 无 hook、无日志、全部超级调用 EINVAL。
**定位过程**:
1. 测试模块 (puretest) 用 toybox insmod 加载: initstate=live 但全局变量未被 init 写入 → init 确实没跑
2. 反汇编设备内核 do_init_module: `ldr x0, [x19, #0x178]` (mod->init) + `cbz` + `bl do_one_initcall` — 内核逻辑正常
3. 解析设备 BTF (vmlinux.btf): struct module 的 init 成员在 **byte 376 (0x178)**
4. 我们的 ko: .gnu.linkonce.this_module 的 init_module 重定位在 **0x170** → 差 8 字节
5. 原因: 设备内核 `CONFIG_CFI_CLANG=y` → struct module 里有 `cfi_check_fn cfi_check` (8 字节) 在 init 之前
6. 我们的构建脚本为省事把 LTO_CLANG_FULL 改成了 LTO_NONE → Kconfig 依赖导致 CFI_CLANG 也被关掉 → 结构体少了 cfi_check 字段 → init 偏移 0x170 ≠ 设备 0x178 → 内核读到的 mod->init 是垃圾/0 → 跳过 init
7. 验证: 用 LTO_CLANG_FULL + CFI_CLANG 重建 (proctest 模块的 init 创建 /proc/ksu_init_marker → 出现了!); kernelsu-vivo-lto.ko 的 init_module 重定位在 0x178 ✓

**修复**: 构建配置改为 LTO_CLANG_FULL=y + CFI_CLANG=y (+CFI_CLANG_SHADOW 自动) → `build_ksu_lto2.sh`

## 顺带发现 (设备特性)

- vivo 内核抑制模块日志: dmesg 里任何模块的 pr_info/taint 都看不到 (模块加载全程无日志)
- kptr_restrict: 开机后几分钟内是 0, 之后被 vivo 设成 2 (kallsyms 地址全隐藏) — ksud 必须在窗口期内加载
- /proc/kallsyms 读取要求真实 uid 0 (caps 不够, vivo 加了 uid 检查) — 用 u0 (setuid 工具) 跑 ksud
- 往 /sys/kernel/tracing/kprobe_events 写任何东西 → 设备重启 (vivo 防护)
- 旧的 (非 CFI) 模块 rmmod → 设备重启 (exit 指针同样错位)

## 加载流程 (重启后重新部署)

```sh
# 1. 冷窗口内跑 exploit (必须带这些环境变量):
CHEESE_STEXT_PA=0xa8010000 CHEESE_DAEMON=1 CHEESE_PATCH_CAP=1 ./exploit_vivo_neo9
# 2. rootd ready 后:
rootc "sh /data/local/tmp/deploy_ksu.sh"   # 或逐条:
rootc "u0 /data/local/tmp/ksud insmod /data/local/tmp/kernelsu-vivo.ko allow_shell=1"
# 3. 验证:
adb shell "/data/local/tmp/su_ksu -c id"    # → uid=0(root) u:r:ksu:s0
```

## 遗留事项

- ksud 版本: 设备 Manager (v4.2.0-rc1) 的 ksud vs 内核模块 (v4.1.0/35077) — 核心超级调用兼容 (GRANT_ROOT/GET_INFO 正常), `ksud feature list` 显示 NOT_SUPPORTED (UAPI 小差异, 仅影响功能开关显示) — 如需完全一致可考虑用 ReSukiSU 源码构建 ksud (Rust, 需要 NDK/交叉工具链)
- 持久化: 重启后模块消失, 需重新走 exploit + insmod 流程; 未来可做 boot/init_boot patch (未动, 遵守 /system 红线)
- su_ksu 是补丁版 su 客户端 (去掉 legacy prctl 门禁, 直接 execve /system/bin/su 触发内核 hook)
