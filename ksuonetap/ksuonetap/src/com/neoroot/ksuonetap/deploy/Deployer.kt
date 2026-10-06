package com.neoroot.ksuonetap.deploy

import android.content.Context
import android.util.Log
import com.neoroot.ksuonetap.core.B64
import com.neoroot.ksuonetap.core.DeviceGate
import com.neoroot.ksuonetap.core.Prefs
import com.neoroot.ksuonetap.core.Progress
import com.neoroot.ksuonetap.core.Shell
import com.neoroot.ksuonetap.core.ShizukuBridge
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 部署流程 (对齐 v1.0.4 的阶段划分)。
 *
 * 阶段: [1] 推文件 -> [2] 起 exploit -> [3] 等临时 root -> [4] rootd 加载 kernelsu ->
 *       [4a] 模块确认 -> [4b] 等 su -> [4c] unpatch+vrpatch -> [4c2] 复核 ->
 *       [4d] 杀 exploit -> [5] 验证 -> [6] 装 KernelSU Manager
 *
 * 设备侧命令都由 Kotlin 生成 ([DeployScript]), 不再有 .sh 脚本。
 */
class Deployer(
    private val ctx: Context,
    private val log: (String) -> Unit
) {
    private companion object {
        const val TAG = "KSUONETAP"
        const val DEV = DeployScript.DEV
    }

    /** 返回 true 表示最终 root 校验通过。进度走 [Progress]（主页的转圈 + 百分比）。 */
    fun run(): Boolean {
        Progress.start("准备中")
        val ok = try {
            runInner()
        } catch (t: Throwable) {
            log("部署异常: $t")
            Log.e(TAG, "deploy", t)
            Progress.fail("部署异常")
            false
        }
        // 成功路径各自 Progress.finish（文案按模式不同）; 这里只兜"失败了但没报过原因"的情况
        if (!ok && Progress.state().running) Progress.fail("未完成")
        return ok
    }

    /** 真正的流程 —— 每个阶段旁边都跟一条 [Progress] 上报。 */
    private fun runInner(): Boolean {
        // GhostLock (CVE-2026-43499) 版本: exploit 全程无特权、随 APK 打包、
        // 从 app 进程树直接执行 —— **不走 Shizuku 部署流程**, 也不推任何文件
        // (二进制在 nativeLibraryDir, socket 在 app files 目录, 见 GhostlockRunner)。
        if (DeviceGate.isGhostlockBuild(DeviceGate.resolveBuildDir())) {
            return GhostlockRunner(ctx, log).run()
        }

        if (!ShizukuBridge.ping()) {
            log("!! Shizuku 未激活 —— 需先在电脑上 adb 激活")
            Progress.fail("Shizuku 未激活")
            return false
        }
        Progress.set(4, "校验 Shizuku 授权")
        if (!ensurePermission()) {
            Progress.fail("Shizuku 授权失败")
            return false
        }
        Progress.set(8, "校验系统版本")
        if (!ensureSystemAssets()) {
            Progress.fail("系统版本未适配")
            return false
        }

        val rootOnly = Prefs.isRootOnly(ctx)
        // 用 DeviceGate 解析出来的 assets 版本目录，**不是** Build.DISPLAY
        // （OriginOS 4 上 Build.DISPLAY 是 AOSP build id，见 DeviceGate 类注释）
        val lpeOnly = DeviceGate.isLpeOnlyBuild(DeviceGate.resolveBuildDir())

        Progress.set(12, "推送部署文件")
        log("== [1] 部署文件 ==")
        deployAssets(
            if (rootOnly || lpeOnly) DeployScript.ASSETS_ROOT_ONLY else DeployScript.ASSETS_FULL,
            pruneKsuExtras = rootOnly || lpeOnly
        )

        log("== [2] 启动 exploit（Shizuku shell 域）==")
        Progress.set(32, "启动 exploit")
        // CHEESE_SU=1: 内置 su 服务 (推的 exploit 就是 -DCHEESE_SU_DEFAULT=1 构建, 这里是
        //   显式声明)。它会把 $DEV/su 装好并起 socket —— 之后 `su -c 'id'` 直接 uid=0。
        // CHEESE_SU_QUEUE=1: 一并 fork rootd 文件队列服务。两种模式都留着它, 因为
        //   「激活 KSU」的 [4] 阶段要靠队列里的 u0 提权才能 `ksud insmod` —— 有它就能
        //   不重跑漏洞地接着上 KSU。队列空转时不创建 rootd_cmd / rootd_out, 不占地方。
        log(
            Shell.run(
                // 先结束上一次残留的 exploit: 新版有并发防护 (检测到本机已有 GPU 路径会直接
                // 拒绝启动), 不清掉旧实例新进程根本起不来。顺带清旧就绪标记与 socket,
                // 否则状态判定会拿着上一轮的文件当真。
                "killall -9 ${DeployScript.EXPLOIT} 2>/dev/null; sleep 1; " +
                    "rm -f $DEV/rootd_cmd $DEV/rootd_out $DEV/${DeployScript.READY_FILE} " +
                    "$DEV/${DeployScript.SU_SOCK} $DEV/${DeployScript.SU_READY}; " +
                    "cd $DEV && CHEESE_SU=1 CHEESE_SU_QUEUE=1 " +
                    "CHEESE_U0_CTX=${DeployScript.U0_CTX} " +
                    "CHEESE_STEXT_PA=0xa8010000 CHEESE_PATCH_CAP=1 " +
                    "nohup ./${DeployScript.EXPLOIT} </dev/null > $DEV/exploit_daemon.log 2>&1 & echo started",
                30_000
            ).trim()
        )

        Progress.set(35, "等待漏洞命中（spray）")
        log("== [3] 等待临时 root 就绪（spray 最长约 9 分钟）==")
        if (!waitRootdReady()) {
            Progress.fail("漏洞未命中")
            return false
        }

        // 设置里的「仅提取 root」模式: 到此为止, 不碰 KernelSU
        if (rootOnly) {
            log("== 模式: 仅提取 root（不部署 KernelSU）==")
            log("临时 root 已就绪: 内置 su 已装到 $DEV/${DeployScript.SU} (socket 命令服务在跑)")
            log("用法: 终端选「临时 root」(或「自动」) 执行 id; 也可直接 $DEV/${DeployScript.SU} -c 'id'")
            log("提权后 uid=0(root), 域 ${DeployScript.U0_CTX} (可访问 servicemanager, cmd/pm/am 可用)")
            log("想接着上 KernelSU: 点主界面的「激活 KSU」(不用重跑漏洞)")
            log("注意: 重启后失效; 未加载 vrpatch 故不能用软重启, 也没有 KSU 的 su 入口")
            log("*** 临时 ROOT OK (未部署 KSU, su 可用) ***")
            Progress.finish("临时 root 就绪")
            return true
        }

        // 「本版本只适配了 LPE / 临时 root」: 到此为止。
        // KernelSU 的 3 个内核模块与内核二进制绑定（vermagic + struct module 布局 +
        // KSU_VERSION），换内核版本必须重编 —— 那批还没做，所以这里不能往下走
        // finishKsuDeploy()（它要去推并不存在的 .ko）。
        if (lpeOnly) {
            log("== 模式: 本系统版本仅适配临时 root（LPE + 内置 su）==")
            log("临时 root 已就绪: 内置 su 已装到 $DEV/${DeployScript.SU} (socket 命令服务在跑)")
            log("用法: ${DeployScript.SU} -c 'id'  → uid=0(root)")
            log("注意: 重启后失效；本版本没有适配 KernelSU 模块，故「激活 KSU」会被拒绝")
            log("*** 临时 ROOT OK (本版本仅 LPE/su, 未部署 KSU) ***")
            Progress.finish("临时 root 就绪")
            return true
        }

        return finishKsuDeploy()
    }

    /**
     * 从「临时 root 已在手」的状态继续把 KernelSU 装完 —— 即完整模式的 [4]~[6]。
     *
     * 两个调用方共用: `run()` 的完整模式 (前面刚提取完 root), 以及 [activateKsu]
     * (用户后来才决定上 KSU, 不必重跑漏洞)。
     *
     * **模块加载一律经 `ksud insmod`**:
     *   - `kernelsu-vanilla-197.ko`: rootd 域里 `u0 ksud insmod ...`（原版 ko 无 allow_shell 参数）
     *   - `unpatch.ko` / `vrpatch.ko`: KSU 起来后经 `Terminal.ksuRun`（入口见 [Terminal.ksuEntry]）
     * 裸 `insmod` 不会做 ksud 的 UAPI 校验与初始化, 不能用。
     */
    private fun finishKsuDeploy(): Boolean {
        Progress.set(70, "加载 KernelSU 驱动")
        log("== [4] 加载 KernelSU 驱动（rootd 域, 经 ksud insmod）==")
        if (!loadKsuViaRootd()) {
            Progress.fail("KernelSU 驱动加载失败")
            return false
        }

        Progress.set(76, "确认 KernelSU 模块")
        log("== [4a] KernelSU: 模块确认 ==")
        log(Shell.run("cat /proc/modules 2>/dev/null | grep kernelsu", 4_000).trim())

        log("== [4b] 等待 KernelSU su 通道 ==")
        Progress.set(82, "等待 KernelSU su 通道")
        if (!waitKsuSu()) {
            Progress.fail("KernelSU su 不可用")
            return false
        }

        Progress.set(88, "加载 unpatch + vrpatch")
        log("== [4c] 加载 unpatch + vrpatch（经 ksud insmod）==")
        log("unpatch: " + Terminal.ksuRun("$DEV/${DeployScript.KSUD} insmod $DEV/${DeployScript.UNPATCH_KO}", 8_000).trim())
        log("vrpatch: " + Terminal.ksuRun("$DEV/${DeployScript.KSUD} insmod $DEV/${DeployScript.VRPATCH_KO}", 8_000).trim())

        log("== [4c2] 复核模块 ==")
        val mods = Shell.run("cat /proc/modules 2>/dev/null | grep -E 'kernelsu|unpatch|vrpatch'", 5_000).trim()
        log(mods.ifEmpty { "!! 未读到模块行" })

        log("== [4d] 结束临时 root，交给 KernelSU ==")
        Terminal.ksuRun("killall -9 ${DeployScript.EXPLOIT} 2>/dev/null; echo killed", 5_000)
        log("剩余进程: " + Shell.run("ls -la $DEV/ 2>/dev/null | grep -E 'exploit|rootd' || echo none", 4_000).trim())

        Progress.set(94, "校验 root")
        log("== [5] 验证 ==")
        val id = Terminal.ksuRun("id", 5_000).trim()
        log(id)
        if (!id.contains("uid=0")) {
            log("!! root 校验失败")
            Progress.fail("root 校验失败")
            return false
        }

        Progress.set(97, "安装 KernelSU Manager")
        log("== [6] 安装 KernelSU Manager ==")
        log(
            Terminal.ksuRun(
                "pm install -r -d $DEV/${DeployScript.MANAGER_APK}",
                60_000
            ).trim()
        )
        log("*** ROOT OK (KernelSU active) ***")
        Progress.finish("完成")
        return true
    }

    /**
     * 「激活 KSU」: 临时 root 已在手时, 接着把 KernelSU 装上 —— 不用重跑漏洞 (spray)。
     *
     * 为什么需要它: 「仅提取 root」模式刻意只推了 exploit, 但拿到临时 root 后常常还想
     * 接着上 KSU。此时把 KSU 那套资产补推上去, 再走一遍 [4]~[6] 即可。
     *
     * 前置是 rootd 文件队列 ([4] 阶段 u0 提权靠它)。正常部署都会带 (CHEESE_SU_QUEUE=1),
     * 但万一队列不在 (旧部署/异常退出), [ensureQueue] 会用内置 su 补起一个 ——
     * `--su-server` 路径不碰漏洞与 GPU, 所以不受实例锁限制, 也不需要重新 spray。
     */
    fun activateKsu(): Boolean {
        Progress.start("激活 KernelSU")
        try {
            // GhostLock (CVE-2026-43499) 版本: 无 Shizuku —— 全程走 daemon
            // （seccomp 逃逸通道加载 .ko），见 GhostlockRunner.activateKsu。
            if (DeviceGate.isGhostlockBuild(DeviceGate.resolveBuildDir())) {
                return GhostlockRunner(ctx, log).activateKsu()
            }
            if (!ShizukuBridge.ping()) {
                log("!! Shizuku 未激活 —— 需先在电脑上 adb 激活")
                Progress.fail("Shizuku 未激活")
                return false
            }
            if (!ensurePermission()) {
                Progress.fail("Shizuku 授权失败")
                return false
            }
            Progress.set(15, "校验系统版本")
            if (!ensureSystemAssets()) {
                Progress.fail("系统版本未适配")
                return false
            }
            val buildDir = DeviceGate.resolveBuildDir()
            if (DeviceGate.isLpeOnlyBuild(buildDir)) {
                log("!! 当前系统版本只适配了 LPE / 临时 root：${DeviceGate.softwareVersion()}")
                log("   KernelSU 的内核模块与内核二进制绑定（vermagic + struct module 布局 +")
                log("   KSU_VERSION），本版本尚未重编 —— 拒绝加载其它版本的模块（会失败或内核 panic）。")
                log("   本版本可用的是「一键提取 Root」+ 内置 su（su -c 'id' → uid=0）。")
                Progress.fail("本版本未适配内核模块")
                return false
            }
            if (!Terminal.tempUsable()) {
                log("!! 没检测到临时 root (内置 su) —— 请先「一键提取 Root」")
                Progress.fail("需要先提取临时 root")
                return false
            }

            Progress.set(25, "补推 KSU 资产")
            log("== 激活 KSU: [1'] 补推 KSU 资产 ==")
            deployAssets(DeployScript.ASSETS_KSU_ONLY, pruneKsuExtras = false)

            log("== 激活 KSU: 确认 rootd 文件队列 ==")
            if (!ensureQueue()) {
                Progress.fail("rootd 队列不可用")
                return false
            }

            if (!finishKsuDeploy()) return false

            // KSU 已接管: u0 只在 [4] 阶段用得到, 收尾删掉 (之后提权走 Terminal.ksuRun)
            Shell.run("rm -f $DEV/${DeployScript.U0}", 3_000)
            log("[cleanup] 已移除 $DEV/${DeployScript.U0} (KSU 起后不再需要)")
            return true
        } catch (t: Throwable) {
            log("激活 KSU 异常: $t")
            Log.e(TAG, "activateKsu", t)
            Progress.fail("激活异常")
            return false
        }
    }

    /**
     * 确保 rootd 文件队列在跑。
     *
     * 判据只能是"投一条命令看有没有回音": `rootd_ready.txt` 由内置 su 服务端也会写,
     * 不能证明队列活着 (两者是同一套 asset 的不同服务)。
     */
    private fun ensureQueue(): Boolean {
        if (queueAlive()) {
            log("  队列在线")
            return true
        }
        log("  队列不在 -> 用内置 su 补起一个 (--su-server 路径, 不碰漏洞/GPU)")
        Shell.run(
            "$DEV/${DeployScript.SU} -c 'cd $DEV && CHEESE_SU=1 CHEESE_SU_QUEUE=1 " +
                "nohup ./${DeployScript.EXPLOIT} --su-server </dev/null > $DEV/su_daemon.log 2>&1 & echo started'",
            30_000
        )
        repeat(20) { i ->
            Thread.sleep(1_000)
            if (queueAlive()) {
                log("  队列已就绪（${i + 1}s）")
                return true
            }
        }
        log("!! 队列补起失败（见 $DEV/su_daemon.log）")
        return false
    }

    /** 队列是否活着: 投一条 no-op 命令, 看有没有 `[rc=` 回音。 */
    private fun queueAlive(): Boolean {
        Shell.run("rm -f $DEV/${DeployScript.OUT_FILE}", 3_000)
        Shell.run("echo B64:${B64.encode("true")} > $DEV/${DeployScript.CMD_FILE}", 5_000)
        repeat(10) {
            Thread.sleep(300)
            if (Shell.readFile("$DEV/${DeployScript.OUT_FILE}", 3_000).contains("[rc=")) return true
        }
        return false
    }

    // ---- [1] 并行推送 assets ----
    /**
     * @param assets 要推送的资产集合 (见 [DeployScript] 里的三组常量)
     * @param pruneKsuExtras true 时顺带清掉 KSU 那套部署物 —— 「仅提取 root」模式下
     *        设备上只该有 exploit 与它自己装的 su
     */
    private fun deployAssets(assets: List<String>, pruneKsuExtras: Boolean) {
        if (pruneKsuExtras) {
            Shell.run(
                "rm -f $DEV/${DeployScript.U0} $DEV/${DeployScript.KSU_KO} $DEV/${DeployScript.KSUD} " +
                    "$DEV/${DeployScript.SU_KSU} $DEV/${DeployScript.UNPATCH_KO} " +
                    "$DEV/${DeployScript.VRPATCH_KO} $DEV/${DeployScript.MANAGER_APK}",
                8_000
            )
        }

        val pool = Executors.newFixedThreadPool(4)
        val ok = AtomicInteger(0)
        val bad = AtomicInteger(0)
        // 推送阶段占 12% -> 30%（并发推送, 所以按"已完成件数"报进度）
        val done = AtomicInteger(0)
        val total = assets.size.coerceAtLeast(1)
        for (name in assets) {
            val data = readAsset(name)
            if (data == null) {
                log("  缺失 asset: $name")
                bad.incrementAndGet()
                Progress.set(12 + done.incrementAndGet() * 18 / total, "推送部署文件")
                continue
            }
            pool.execute {
                if (pushOne(name, data)) ok.incrementAndGet() else bad.incrementAndGet()
                val n = done.incrementAndGet()
                Progress.set(12 + n * 18 / total, "推送部署文件 $n/$total")
            }
        }
        pool.shutdown()
        pool.awaitTermination(180, TimeUnit.SECONDS)
        log("[deploy] 结果: 成功 ${ok.get()} / 失败 ${bad.get()} (共 ${assets.size})")
        if (bad.get() > 0) log("!! 有文件未就位, 后续步骤大概率失败")
    }

    /** 返回是否写入且大小校验通过。 */
    private fun pushOne(name: String, data: ByteArray): Boolean {
        val path = "$DEV/$name"
        return try {
            Shell.run("rm -f $path", 3_000)
            if (!Shell.pipeTo("cat > $path", data)) {
                log("  $name 写入失败")
                return false
            }
            var got = ""
            var match = false
            for (i in 0 until 10) {   // 落盘可能滞后, 边等边校验
                got = Shell.run("stat -c %s $path 2>/dev/null", 2_000).trim()
                if (got == data.size.toString()) {
                    match = true
                    break
                }
                Thread.sleep(300)
            }
            Shell.run("chmod 755 $path", 2_000)
            if (!match) {
                log("[deploy] $name 校验失败: 期望 ${data.size}B, 实际 '$got'")
                return false
            }
            log("[deploy] $name (${data.size}B) ok")
            true
        } catch (t: Throwable) {
            log("[deploy] $name 失败: $t")
            false
        }
    }

    /**
     * 取 assets 里的产物字节。
     *
     * 版本绑定件在 `assets/<版本目录>/` 下（见 [DeployScript.assetPath]）——
     * 版本目录由 [DeviceGate.resolveBuildDir] 从设备软件版本号解析而来（**不是**
     * `Build.DISPLAY`，那个在 OriginOS 4 上是 AOSP build id），所以不可能把别的系统版本的
     * 产物推到本机。
     */
    private fun readAsset(name: String): ByteArray? = runCatching {
        ctx.assets.open(DeployScript.assetPath(DeviceGate.resolveBuildDir(), name)).use { input ->
            val bos = ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                bos.write(buf, 0, n)
            }
            bos.toByteArray()
        }
    }.getOrNull()

    /**
     * 部署前的版本目录预检。
     *
     * 设备版本与随包适配版本不一致时，取件本来就会缺文件；但那时已经推进到 [2] 起 exploit 了，
     * 报错会埋在日志里。这里提前判掉并给出明确的修法。
     */
    private fun ensureSystemAssets(): Boolean {
        val info = DeviceGate.probe(shellOk = true)
        val build = info.buildDir
        val lpeOnly = DeviceGate.isLpeOnlyBuild(build)
        // 必须齐备的绑定产物: 完整适配版 = 4 件 (exploit + 3 个 .ko), LPE-only 版 = 1 件。
        val need = DeployScript.systemBoundFor(build)
        val missing = need.filter { runCatching { ctx.assets.open(DeployScript.assetPath(build, it)).close() }.isFailure }
        if (missing.isEmpty()) {
            log(
                "[0] 系统版本目录: assets/$build/ " +
                    "(绑定产物 ${need.size} 个${if (lpeOnly) ", 仅 LPE/临时 root" else ""})"
            )
            log("    设备软件版本: ${info.versionDisplay}")
            return true
        }
        log("!! 当前系统未被适配 —— assets/$build/ 缺: ${missing.joinToString()}")
        log("   设备软件版本       : ${info.versionDisplay}")
        log("   版本核             : ${info.versionCore.ifEmpty { "（未识别）" }}")
        if (info.versionProp.isNotEmpty()) log("   （版本号取自 prop ${info.versionProp}）")
        log("   Build.DISPLAY      : ${info.build}（不是软件版本号, 仅诊断用）")
        log("   完整适配版本       : ${DeviceGate.ADAPTED_BUILDS.joinToString()}")
        log("   仅 LPE/临时 root   : ${DeviceGate.LPE_ONLY_BUILDS.joinToString()}")
        log("   绑定产物 (需按系统版本重做): ${DeployScript.SYSTEM_BOUND.joinToString()}")
        log("   做法: 在 apk/ksuonetap/assets/ 下新建以本机软件版本号命名的目录, ")
        log("         放入重新产出的绑定产物 + SYSTEM.txt; 见 assets/PD2338_A_15.1.14.7.W10.V000L1/SYSTEM.txt")
        return false
    }

    // ---- [3] 等 rootd, 并识别 exploit 已死 ----
    private fun waitRootdReady(): Boolean {
        repeat(55) { i ->
            Thread.sleep(10_000)
            // 等待期间进度缓慢爬（35% -> 62%）——spray 要等多久无法预知, 只能给"已等 Ns"
            val secs = (i + 1) * 10
            Progress.set(35 + secs * 27 / 540, "等待漏洞命中（已 ${secs}s）")
            if (Shell.run("ls $DEV/${DeployScript.READY_FILE} 2>/dev/null", 3_000)
                    .contains(DeployScript.READY_FILE)
            ) {
                log("临时 root 就绪（${(i + 1) * 10}s）")
                return true
            }
            // exploit 已退出且没过就绪标记 -> 直接失败, 把它最后的日志贴出来
            val alive = Shell.run("pidof ${DeployScript.EXPLOIT} 2>/dev/null", 3_000).trim()
            if (alive.isEmpty()) {
                val tail = Shell.run("tail -c 300 $DEV/exploit_daemon.log 2>/dev/null | tr -d '\\0'", 4_000).trim()
                log("!! EXPLOIT DEAD, 日志尾部:\n$tail")
                return false
            }
            if (i % 6 == 5) log("  spray 中… ${(i + 1) * 10}s")
        }
        log("!! ROOTD TIMEOUT")
        return false
    }

    // ---- [4] 经 rootd 文件队列执行 Kotlin 生成的命令 ----
    private fun loadKsuViaRootd(): Boolean {
        Shell.run(
            "echo B64:${B64.encode(DeployScript.ksuLoadCommand())} > $DEV/rootd_cmd", 5_000
        )
        repeat(60) { i ->
            Thread.sleep(3_000)
            val out = Shell.readFile("$DEV/rootd_out", 3_000)
            if (out.contains(DeployScript.DONE_MARKER)) {
                log(out.trim())
                return true
            }
            if (i % 5 == 4) log("  等待加载… ${(i + 1) * 3}s")
        }
        log("!! kernelsu 加载超时（rootd 无输出）")
        return false
    }

    // ---- [4b] 等 su 通道 ----
    private fun waitKsuSu(): Boolean {
        repeat(20) { i ->
            Thread.sleep(2_000)
            // 随包的 su_ksu 优先, 退化到 KernelSU 自己装的 su —— 两者都算"su 通道就绪"
            val entry = Terminal.ksuEntry()
            if (entry != null) {
                log("KernelSU su 就绪（${(i + 1) * 2}s；入口 $entry）")
                return true
            }
            if (i % 5 == 4) log("  等待 su… ${(i + 1) * 2}s")
        }
        log("!! KSU su 不可用")
        return false
    }

    // ---- 授权 ----
    private fun ensurePermission(): Boolean {
        var perm = ShizukuBridge.selfPermission()
        log("Shizuku 授权状态: $perm (0=已授权, -1=未授权)")
        if (perm == 0) return true
        return requestPermission()
    }

    /** 单独暴露给「激活 Shizuku」按钮。 */
    fun requestPermission(): Boolean {
        log("请求 Shizuku 授权（需在弹窗点允许）…")
        ShizukuBridge.requestPermission()
        repeat(30) { i ->
            Thread.sleep(2_000)
            if (ShizukuBridge.selfPermission() == 0) {
                log("授权成功（${(i + 1) * 2}s）")
                return true
            }
            if (i % 5 == 4) log("  等待授权… ${(i + 1) * 2}s")
        }
        log("!! 授权超时（60s）")
        return false
    }

    /** 软重启: ksud soft-reboot (走完整 boot 事件流)。ksud 只有 KSU 加载后才存在。 */
    fun softReboot() {
        val entry = Terminal.ksuEntry()
        if (entry == null) {
            log("!! 软重启需要 KernelSU (走 ksud soft-reboot); 当前只有临时 root, 请改用「清理并重启」")
            return
        }
        log("== 软重启（ksud soft-reboot）==")
        log("提权客户端: $entry")
        log(Terminal.ksuRun("$DEV/${DeployScript.KSUD} soft-reboot", 30_000).trim())
    }

    /**
     * 一键清理并重启: 命令由 Kotlin 生成, 不用设备侧脚本。
     *
     * 提权客户端要**按当前环境选**: KSU 在就用它探到的 su 入口（随包的 `su_ksu`，
     * 或手工装 KSU 时 KernelSU 自己装的 `/system/bin/su`），只提取了 root 时用内置 `su`
     * —— 后者没有 `su_ksu`（它是 KSU 的客户端, 要等 KSU 加载后才存在）。
     * 命令里不含单引号 (见 [DeployScript.cleanupRebootCommand] 的约定), 所以直接单引号包裹。
     */
    fun cleanupAndReboot() {
        log("== 一键清理并重启 ==")
        val entry = Terminal.ksuEntry()
        val cmd = DeployScript.cleanupRebootCommand()
        val out = if (entry != null) {
            log("提权客户端: $entry")
            Terminal.ksuRun(cmd, 60_000)
        } else {
            val client = "$DEV/${DeployScript.SU}"
            log("提权客户端: $client (临时 root)")
            Shell.run("$client -c '$cmd'", 60_000)
        }
        log(out.trim())
        log("清理命令已下发（设备随后会重启）")
    }
}
