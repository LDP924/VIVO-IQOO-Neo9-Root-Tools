package com.neoroot.ksuonetap.deploy

import com.neoroot.ksuonetap.core.B64
import com.neoroot.ksuonetap.core.Channel
import com.neoroot.ksuonetap.core.DeviceGate
import com.neoroot.ksuonetap.core.Shell
import com.neoroot.ksuonetap.core.ShizukuBridge

/**
 * 内置终端的执行通道。
 *
 *   SHELL : Shizuku 授权的 shell 域 (uid 2000) —— 只读探测, 任何状态都可用
 *   TEMP  : 临时 root, 走 exploit 内置的 `su` -> uid=0(root), 域 u:r:shell:s0
 *           (执行前先 setcon 过去 —— su 默认落的 vrp 域连 servicemanager 都问不到)
 *           —— 未部署 KSU 时**唯一**的 root 通道
 *   KSU   : 部署好的 KernelSU, 走 [ksuEntry] 解析出的 su 入口 —— 随包的 `su_ksu`,
 *           或 KernelSU 自己装的 `/system/bin/su`（手工装 KSU 时只有后者, 见该常量的注释）
 *   AUTO  : 依次探测 KSU -> TEMP -> SHELL, 取第一个可用的
 *
 * 通道的标识与展示名在 core 包的 `Channel`; 本文件只管"怎么执行"。
 *
 * 三条通道都归一到「跑一段脚本 -> 尾部拿到 `[rc=N]`」:
 *   - 命令写成脚本落盘再执行 (见 [execWrapped]), 由本类在尾部追加 `[rc=N]`
 *   - 所以解析逻辑只有 [RC_TAIL] 一份
 */
object Terminal {
    /** 末尾的 `[rc=N]` 标记 (只认结尾, 避免把命令输出里的同名文本当成标记)。 */
    private val RC_TAIL = Regex("\\[rc=(-?\\d+)]\\s*$")

    /** 包装脚本落盘路径 (放 /data/local/tmp, 与部署物同目录)。 */
    private const val WRAP_SH = "${DeployScript.DEV}/.kt_term.sh"

    data class Result(val channel: String, val output: String, val rc: Int?)

    // ---------------- 可用性探测 ----------------

    /**
     * KSU 的 su 入口候选（**按优先级依次探测**，任一出 `uid=0` 即算可用）。
     *
     * 为什么不止一个：`$DEV/su_ksu` 是**随包**的（App 部署时推过去的），最干净；
     * 但用户完全可能**手工装 KSU**（adb root + `ksud insmod`，本项目的另一条推荐路径），
     * 那时设备上真正存在的 su 入口是 KernelSU 自己装的 `/system/bin/su` ——
     * 只认 `su_ksu` 会把「KernelSU 已加载」误判成「无 root」，
     * 并连带把「软重启」「清理并重启」一起门控掉（2026-09-28 真机踩到）。
     */
    private val KSU_SU_CANDIDATES = listOf(
        "${DeployScript.DEV}/${DeployScript.SU_KSU}",   // ① 随包部署的 su_ksu（优先）
        "/system/bin/su",                                // ② KernelSU 自己装的 su
        "/data/adb/ksu/bin/su",                          // ③ 少数版本放这里
        "su",                                            // ④ PATH 兜底
    )

    /** 已探明的可用入口。探不到**不缓存**（KSU 可能还没加载完，下次要能重探）。 */
    @Volatile
    private var ksuEntryCache: String? = null

    /**
     * 探明并缓存可用的 KSU su 入口。
     *
     * 探测方式就是**真的执行一次 `id`** —— 只看文件在不在不算数（KSU 会拒绝未授权的调用者）。
     */
    fun ksuEntry(): String? {
        ksuEntryCache?.let { return it }
        for (c in KSU_SU_CANDIDATES) {
            if (Shell.run("$c -c id 2>&1", 6_000).contains("uid=0")) {
                ksuEntryCache = c
                return c
            }
        }
        return null
    }

    /** KSU 的 su 通道是否可用 (uid=0)。 */
    fun ksuUsable(): Boolean = ksuEntry() != null

    /** 让缓存的入口失效（KSU 卸载 / 清掉了 /data/local/tmp / 入口被执行证明不可用）。 */
    fun invalidateKsu() {
        ksuEntryCache = null
    }

    /**
     * 用当前可用的 KSU su 执行一条 shell 命令。
     *
     * [cmd] 传**将被执行的命令**（引用由本函数负责，调用方别自己加引号）。
     * 入口取自 [ksuEntry]（优先随包的 `su_ksu`，退化到 KernelSU 装的 `su`）；
     * 若输出显示**入口本身**没起来（`inaccessible` / `not found`），清缓存下次重探 ——
     * 免得一直拿一个已经失效的路径。
     */
    fun ksuRun(cmd: String, timeoutMs: Long): String {
        val entry = ksuEntry() ?: KSU_SU_CANDIDATES.first()
        val out = Shell.run("$entry -c ${shq(cmd)}", timeoutMs)
        if (out.contains("inaccessible") || out.contains("not found") || out.contains("No such file")) {
            invalidateKsu()
        }
        return out
    }

    /**
     * 临时 root 是否在线。
     *
     * GhostLock 版本: 驻留 daemon 的 socket 在 app files 目录（GhostlockRunner
     * 成功后由 daemon 建出）。旧 LPE 版本: exploit 进程活着 **且** 内置 su 的 socket 在。
     */
    fun tempUsable(): Boolean {
        if (DeviceGate.isGhostlockBuild(DeviceGate.resolveBuildDir())) {
            return GhostlockRunner.latest?.rootReady() == true
        }
        if (Shell.run("pidof ${DeployScript.EXPLOIT} 2>/dev/null", 3_000).trim().isEmpty()) return false
        val sock = "${DeployScript.DEV}/${DeployScript.SU_SOCK}"
        return Shell.run("ls $sock 2>/dev/null", 3_000).contains(DeployScript.SU_SOCK)
    }

    /** 把 AUTO 解析成具体通道。 */
    fun resolve(ch: String): String = when (ch) {
        Channel.AUTO -> when {
            ksuUsable() -> Channel.KSU
            tempUsable() -> Channel.TEMP
            else -> Channel.SHELL
        }
        else -> ch
    }

    /** 当前环境的一句话概览 (给终端顶部显示)。KSU 那栏带上实际用的入口, 便于排查。 */
    fun statusLine(ch: String): String {
        val shizuku = if (ShizukuBridge.ping()) "Shizuku 已激活" else "Shizuku 未激活"
        val entry = if (ShizukuBridge.ping()) ksuEntry() else null
        val ksu = if (entry != null) "KSU 可用（${entry.substringAfterLast('/')}）" else "KSU 不可用"
        val temp = if (ShizukuBridge.ping() && tempUsable()) "临时 root 在线" else "临时 root 离线"
        return "$shizuku · $ksu · $temp · 通道「${Channel.describe(resolve(ch))}」"
    }

    // ---------------- 执行 ----------------

    fun exec(cmd: String, ch: String, timeoutMs: Long = 60_000): Result {
        val trimmed = cmd.trim()
        if (trimmed.isEmpty()) return Result(ch, "", null)
        // GhostLock 版本: TEMP 通道走驻留 daemon (SU_SOCK_PATH 指向 app files 目录)
        if (ch == Channel.TEMP &&
            DeviceGate.isGhostlockBuild(DeviceGate.resolveBuildDir())
        ) {
            val r = GhostlockRunner.latest
                ?: return Result(ch, "!! GhostLock root 不在线 —— 请先「一键提权」", null)
            val script = "{\n$trimmed\n} 2>&1\necho \"[rc=\$?]\"\n"
            val inner = "echo ${B64.encode(script)} | base64 -d > /data/local/tmp/.kt_term.gl.sh 2>/dev/null || " +
                "exit 97; chmod 700 /data/local/tmp/.kt_term.gl.sh 2>/dev/null; " +
                "sh /data/local/tmp/.kt_term.gl.sh; rm -f /data/local/tmp/.kt_term.gl.sh"
            val out = r.runAsRoot(inner, timeoutMs)
            return Result(ch, stripRc(out), rcOf(out))
        }
        // 三条通道只差"谁来跑": shell 域直接起进程, 另两条各经一个提权客户端 (su / su_ksu)
        return execWrapped(trimmed, resolve(ch), timeoutMs)
    }

    /**
     * 命令写成脚本落盘再执行, 尾部补 `[rc=N]`。
     *
     * 为什么要落盘而不是直接 `su -c '<cmd>'` / `su_ksu -c '<cmd>'`: 命令里出现单引号或换行
     * 就会破坏外层引用, 多行命令在层层引用里极易被吞。写成脚本后, 传给客户端的只有 base64 串。
     */
    private fun execWrapped(cmd: String, channel: String, timeoutMs: Long): Result {
        val script = "{\n$cmd\n} 2>&1\necho \"[rc=\$?]\"\n"
        val inner = "echo ${B64.encode(script)} | base64 -d > $WRAP_SH; " +
            "chmod 700 $WRAP_SH; sh $WRAP_SH; rm -f $WRAP_SH"

        val dev = DeployScript.DEV
        val out = when (channel) {
            // KSU: 入口由 ksuEntry() 解析（随包的 su_ksu 或 KernelSU 装的 su）
            Channel.KSU -> ksuRun(inner, timeoutMs)
            // 临时 root: 先切到 shell 域再执行。不能直接用 su 给的 vrp 域 —— 它问不到
            // servicemanager (cmd / pm / am 全废, Axeron 这类工具直接起不来), 见 DeployScript.U0_CTX
            Channel.TEMP -> {
                val pre = "echo -n ${DeployScript.U0_CTX} > /proc/self/attr/current 2>/dev/null; "
                Shell.run("$dev/${DeployScript.SU} -c ${shq(pre + inner)}", timeoutMs)
            }
            // shell 域直接用 Shizuku 起进程, 同样落盘执行以保持一致
            else -> Shell.run(inner, timeoutMs)
        }
        return Result(channel, stripRc(out), rcOf(out))
    }

    // ---------------- 小工具 ----------------

    /** 单引号安全包裹 (命令里出现 ' 时用 '\'' 转义)。 */
    private fun shq(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    /** 取尾部 [rc=N] 的 N (取不到返回 null)。 */
    private fun rcOf(s: String): Int? =
        RC_TAIL.find(s)?.groupValues?.get(1)?.toIntOrNull()

    /**
     * 去掉尾部的 `[rc=N]` 标记, 只留真实输出。
     *
     * 循环去 —— 临时 root 通道会叠两个: 脚本自己 `echo` 的那个, 加上内置 su 客户端
     * 在尾部补的那个 (KSU / shell 通道只有一个, 循环也照样成立)。
     */
    private fun stripRc(s: String): String {
        var t = s
        while (true) {
            val n = RC_TAIL.replace(t, "").trimEnd('\n')
            if (n == t) return t.trimEnd('\n')
            t = n
        }
    }
}
