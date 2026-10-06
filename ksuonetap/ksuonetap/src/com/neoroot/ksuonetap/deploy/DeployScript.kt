package com.neoroot.ksuonetap.deploy

import com.neoroot.ksuonetap.core.DeviceGate

/**
 * 部署规格与设备侧命令。
 *
 * 原版把这些命令放在 assets 的两个 .sh 里 (deploy_onedevice.sh / cleanup_reboot.sh);
 * 现在全部由 Kotlin 生成, **不再有独立脚本**: 命令字符串经 rootd 文件队列
 * (`rootd_cmd` 的 B64 协议, 编码见 core/B64) 或 `su_ksu -c` 执行。
 *
 * 阶段与标记沿用 v1.0.4 的日志文本, 便于与历史日志逐条对照。
 */
object DeployScript {
    const val DEV = "/data/local/tmp"
    const val ADB_DIR = "/data/adb"

    /** 2026-10-06 起为原版 KernelSU 管理器; 旧的 ReSukiSU 管理器包名在
     *  [cleanupRebootCommand] 里兼容卸载（历史部署可能装过）。 */
    const val MANAGER_PKG = "me.weishu.kernelsu"

    const val EXPLOIT = "exploit_vivo_neo9"
    const val KSU_KO = "kernelsu-vanilla-197.ko"
    const val UNPATCH_KO = "unpatch.ko"
    const val VRPATCH_KO = "vrpatch.ko"
    const val KSUD = "ksud"
    const val U0 = "u0"
    const val SU_KSU = "su_ksu"
    const val MANAGER_APK = "KernelSU.apk"

    /**
     * 内置 su (exploit 自己装的, 见 `stab_install_su`)。
     *
     * 本工程推的 exploit 是 `-DCHEESE_SU_DEFAULT=1` 构建
     * (= `neo9-root/exploit/out/<系统版本>/exploit_vivo_neo9_stable_su_ndk13`), 起来就带 socket 命令服务:
     *   - 装 `$DEV/su` -> 符号链接到自身 (argv[0]=="su" 即进客户端模式)
     *   - 客户端把命令经 `$DEV/su.sock` 交给服务端, 服务端 fork 后
     *     `setcon(u:r:vrp:s0)` + `setuid(0)` 再 `sh -c` —— 所以 `su -c 'id'` 直接 uid=0,
     *     且不需要设备上存在 `u0`。
     */
    const val SU = "su"

    /** 内置 su 的就绪标记 (判定「临时 root」通道是否可用)。 */
    const val SU_READY = "su_ready.txt"

    /** 内置 su 的命令 socket。 */
    const val SU_SOCK = "su.sock"

    /**
     * 内置 su 提权后落脚的 SELinux 域。
     *
     * 为什么不是 exploit 默认的 `u:r:vrp:s0`: vrp 是 vivo 自定义域, 权限**只够跑裸命令** ——
     * 实测它连 servicemanager 都问不到 (`service list` → `Found 0 services`,
     * `cmd package path ...` → `Can't find service: package`, avc 里是
     * `denied { find } name=package scontext=u:r:vrp:s0 tclass=service_manager permissive=0`)。
     * 后果是所有依赖系统服务的工具都起不来 (例如 AxManager/Axeron 的 starter 要以
     * package 服务来定位自己的 APK, 直接 fatal)。
     *
     * 换 `u:r:shell:s0` 后这些都能用 —— shell 是 Android 标准域, policy 对 servicemanager /
     * package / activity 等有完整 allow。压测 (120 轮 `cmd package list packages` +
     * `/proc/self/status` 读取) 进程全程存活, 域稳定, euid=0 保持。
     *
     * 代价: 放弃了 vr.ko 的白名单 (它的检测条件是 `euid==0 && sid != vrp`)。本机实测
     * shell 域 + euid=0 未被击杀; 真遇到被杀的机型, 先把 vrpatch.ko 中和 vr.ko 再切域。
     */
    const val U0_CTX = "u:r:shell:s0"

    /**
     * 需要推送到设备的 assets —— 按模式分两组。
     *
     * 「仅提取 root」模式**只推 exploit**: su 是 exploit 自己装的, KSU 相关文件一律不需要,
     * 这样 /data/local/tmp 里不会出现 u0 (用户要求: 该模式下只应有 su)。
     */
    val ASSETS_ROOT_ONLY = listOf(EXPLOIT)

    /** 完整模式 (部署 KSU): 还要 ksud / u0 / unpatch / vrpatch / su_ksu / Manager。 */
    val ASSETS_FULL = listOf(
        EXPLOIT, KSU_KO, KSUD, U0, SU_KSU, UNPATCH_KO, VRPATCH_KO, MANAGER_APK
    )

    /**
     * 「激活 KSU」用的子集: 临时 root 已经在跑, 只补 KSU 那一套 (不含 exploit)。
     *
     * `U0` 在这里是必需的 —— [4] 阶段经 rootd 域执行 `u0 ksud insmod` 才提权得动;
     * 用它跑完 `insmod` 后 App 会把它删掉 (KSU 一起, 之后走 su_ksu, 不再需要 u0)。
     */
    val ASSETS_KSU_ONLY = listOf(
        KSU_KO, KSUD, U0, SU_KSU, UNPATCH_KO, VRPATCH_KO, MANAGER_APK
    )

    /**
     * **系统版本绑定**的产物 —— 它们与目标系统的内核二进制布局绑定，换系统版本必须重做，
     * 所以在 assets 里按系统版本分目录存：`assets/<版本目录>/<名>`。
     * 版本目录名 = 该固件的**软件版本号**（`DeviceGate.ALL_ADAPTED_BUILDS` 里那一串），
     * 解析入口是 `DeviceGate.resolveBuildDir()` —— 注意**不是** `Build.DISPLAY`：
     * vivo 的 `ro.build.display.id` 在 OriginOS 4 上是 AOSP build id，不含版本号。
     *
 * 绑定的是什么（每一条都是"换版本会直接内核 panic 或加载失败"的那种）：
 *   - `exploit_vivo_neo9` —— `KERNEL_PHYS_BASE` / `CHEESE_STEXT_PA` / `gPhyAddrs[]` /
 *     spray 布局，全是内核镜像的物理布局
 *   - `kernelsu-vanilla-197.ko` —— vermagic + `struct module` 布局（LTO/CFI/BTF）+ `KSU_VERSION`
 *   - `unpatch.ko` —— 同上，外加硬编码的 `CAP_BPRM_PA`（= stext_pa + 符号偏移）
 *   - `vrpatch.ko` —— 同上，外加 vr.ko 内部检测函数偏移 `VR_DETECT_OFFSET`
 *
 * 其余产物**不绑系统版本**，留在 assets 顶层，换系统不用重做：
 *   - `u0` 只是 `setuid(0)` + `execv`；`su_ksu` / `ksud` 绑的是 KSU 版本与 UAPI；
 *     `KernelSU.apk` 是通用管理器 APK（原版 v3.3.0, 官方签名）
 */
    val SYSTEM_BOUND: Set<String> = setOf(KSU_KO, UNPATCH_KO, VRPATCH_KO, EXPLOIT)

    /**
     * **只适配 LPE / 临时 root** 的版本（见 `DeviceGate.LPE_ONLY_BUILDS`）所必须的绑定产物
     * —— 只有 exploit 一件。它们的 `assets/<版本>/` 里没有那 3 个内核模块，所以
     * 部署时不能去推（会报缺文件），「激活 KSU」也必须直接拒绝。
     */
    val SYSTEM_BOUND_LPE: Set<String> = setOf(EXPLOIT)

    /** 某个系统版本在 assets 里**必须齐备**的绑定产物（完整适配版 = 4 件，LPE-only 版 = 1 件）。 */
    fun systemBoundFor(build: String): Set<String> =
        if (DeviceGate.isLpeOnlyBuild(build)) SYSTEM_BOUND_LPE else SYSTEM_BOUND

    /**
     * assets 内该产物的实际路径。
     *
     * 版本绑定件走 `<版本目录>/<名>`（`build` 传 `DeviceGate.resolveBuildDir()` 的结果），
     * 通用件走顶层。
     * 目录名与设备版本不一致时取件会失败并明确报缺哪个 —— 这是刻意的：
     * 用别的版本的偏移打本机，后果不是"失败"而是内核 panic。
     */
    fun assetPath(build: String, name: String): String =
        if (name in SYSTEM_BOUND) "$build/$name" else name

    const val READY_FILE = "rootd_ready.txt"

    /** rootd 文件队列: 写入命令的文件 (内容 `B64:<base64>`)。 */
    const val CMD_FILE = "rootd_cmd"

    /** rootd 文件队列: 读取输出的文件 (rootd 每次 O_TRUNC 重写, 尾部带 `[rc=N]`)。 */
    const val OUT_FILE = "rootd_out"

    /** [4] 阶段完成的输出标记。 */
    const val DONE_MARKER = "DEPLOY DONE"

    /**
     * [4] 阶段交给 rootd 执行的命令 (等价于原 deploy_onedevice.sh)。
     *
     * 为什么必须是 rootd 域: kernelsu 加载前, u0 的 setuid(0) 需要 caps, 而 shizuku 的
     * 普通 shell 域没有 -> 只能在 rootd (caps 域) 里做。
     *
     * [1.5] 是关键: 新版内核 sucompat 把 /system/bin/su 重定向到 /data/adb/ksud,
     * 所以必须在 insmod **之前** (rootd 还能写文件时) 把 ksud 复制到位。
     *
     * 为什么到此为止: kernelsu 一加载, rootd 那侧 kernel-sid 的 sh 就再也 exec/写不了
     * 任何东西 (KSU execve hook 生效), 所以 unpatch/vrpatch 的加载与收尾由 App 走
     * shizuku + su_ksu 在 [4c] 之后完成。
     */
    fun ksuLoadCommand(): String = buildString {
        append("export PATH=$DEV:/system/bin:\$PATH\n")
        append("echo \"== [1] env ==\"\n")
        append("id\n")
        append("cat /proc/sys/kernel/kptr_restrict 2>/dev/null\n")
        append("echo \"== [1.5] 确保 $ADB_DIR/ksud (新版 sucompat 硬依赖) ==\"\n")
        append("mkdir -p $ADB_DIR\n")
        append("cp $DEV/$KSUD $ADB_DIR/$KSUD 2>/dev/null\n")
        append("chmod 755 $ADB_DIR/$KSUD\n")
        append("ls -la $ADB_DIR/$KSUD\n")
        append("echo \"== [2] ksud insmod kernelsu ==\"\n")
        // 原版 KernelSU 的 ko 没有 allow_shell 模块参数 —— 带着它 insmod 直接失败
        // （参数是 ReSukiSU 分支的; 2026-10-06 换原版后去掉）。
        append("$DEV/$U0 $DEV/$KSUD insmod $DEV/$KSU_KO\n")
        append("echo \"ksu_rc=\$?\"\n")
        append("echo \"=== $DONE_MARKER (kernelsu only; unpatch/vrpatch 由 App 在 [4c] 加载) ===\"\n")
    }

    /**
     * 一键清理并重启的设备侧命令 (等价于原 cleanup_reboot.sh)。
     *
     * 由 App 通过当前可用的提权客户端以 root 执行 (`su_ksu -c`, 或只有临时 root 时的
     * 内置 `su -c` —— 见 `Deployer.cleanupAndReboot`), 顺序不可拆:
     *   [0] 卸载 Manager + 结束 ksud 守护
     *   [1] 清 /data/local/tmp 的部署物 (保留目录本身与无关大文件)
     *   [2] 清 /data/adb 下全部内容 (保留目录本身)
     *   [3] reboot
     * 顺序关键点: /data/adb/ksud 是 su_ksu 提权链的内核重定向目标, 删掉后无法再提权,
     * 所以清理与重启必须在**同一个 root 会话**内一次做完。
     *
     * 注意: 命令里只用双引号, 不带单引号 —— 外层要整体塞进 `su_ksu -c '...'`。
     */
    fun cleanupRebootCommand(): String = buildString {
        append("export PATH=$DEV:/system/bin:\$PATH; ")
        append("echo \"== [0] 卸载 KernelSU/ReSukiSU Manager ==\"; ")
        // 两个包名都卸: 原版 (me.weishu.kernelsu) + 历史 ReSukiSU 部署装过的旧包
        append("pm uninstall $MANAGER_PKG 2>&1 | head -2; ")
        append("pm uninstall com.resukisu.resukisu 2>&1 | head -2; ")
        append("killall $KSUD 2>/dev/null; ")
        append("echo \"== [1] 清理 $DEV (保留目录) ==\"; ")
        // 注意: `*.sh` / `*.txt` 这类 glob 在 sh 里**不匹配以点开头的文件**,
        // 所以 .kt_term.sh 与 .cheese* (含 .cheese_stab_backup.bin) 要显式列出。
        append("cd $DEV && rm -rf exploit* rootd* *.ko *.sock *.log *.sh *.txt ")
        append("deploy* run_exploit* *_probe *_test su_test2 rootc tsu tsu.orig resukisu-ksud $MANAGER_APK ")
        append("$SU $SU_KSU $KSUD $U0 asuid u_uid preload.so pd2338c_root su_ksud.txt ")
        append("vhangup_trigger kread_test helloksu.zip env.sh .cheese* .kt_term.sh; ")
        append("sync; ")
        append("echo \"== [2] 清理 $ADB_DIR (保留目录) ==\"; ")
        append("rm -rf $ADB_DIR/* $ADB_DIR/.[!.]* 2>/dev/null; ")
        append("sync; ")
        append("echo \"== [3] 重启 ==\"; ")
        append("reboot")
    }
}
