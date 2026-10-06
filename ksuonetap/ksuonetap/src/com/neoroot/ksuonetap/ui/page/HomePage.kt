package com.neoroot.ksuonetap.ui.page

import android.app.Activity
import android.app.AlertDialog
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import com.neoroot.ksuonetap.R
import com.neoroot.ksuonetap.core.DeviceGate
import com.neoroot.ksuonetap.core.LogBus
import com.neoroot.ksuonetap.core.Prefs
import com.neoroot.ksuonetap.core.Progress
import com.neoroot.ksuonetap.core.Shell
import com.neoroot.ksuonetap.core.ShizukuBridge
import com.neoroot.ksuonetap.deploy.GhostlockRunner
import com.neoroot.ksuonetap.deploy.DeployScript
import com.neoroot.ksuonetap.deploy.Deployer
import com.neoroot.ksuonetap.deploy.Terminal
import com.neoroot.ksuonetap.ui.Dock

/**
 * 主页 (dock 第 1 项) —— KSUOneTap 的操作台:
 * 大状态卡 / 状态明细 / 操作按钮 / 日志区。
 *
 * 原来是一个独立 Activity, 现在只是分页容器里的一页, 所以生命周期简化为
 * [onShow] / [onHide] (见 [Page])。
 * 部署流程与设备侧命令仍在 [Deployer] / [DeployScript] 里, 没有 .sh 脚本。
 */
class HomePage(
    activity: Activity,
    root: View,
    private val forceNoRoot: Boolean
) : Page(activity, root) {

    private enum class State { IDLE, OK, WARN, ERR }

    private val tvHint: TextView = id(R.id.tvHint)
    private val progressRow: View = id(R.id.progressRow)
    private val tvProgressPct: TextView = id(R.id.tvProgressPct)
    private val tvProgressLabel: TextView = id(R.id.tvProgressLabel)
    private val tvDevice: TextView = id(R.id.tvDevice)
    private val tvShizuku: TextView = id(R.id.tvShizuku)
    private val tvKsu: TextView = id(R.id.tvKsu)
    private val tvRoot: TextView = id(R.id.tvRoot)
    private val dotDevice: TextView = id(R.id.dotDevice)
    private val dotShizuku: TextView = id(R.id.dotShizuku)
    private val dotKsu: TextView = id(R.id.dotKsu)
    private val dotRoot: TextView = id(R.id.dotRoot)
    private val dotVerdict: TextView = id(R.id.dotVerdict)
    private val tvVerdict: TextView = id(R.id.tvVerdict)
    private val tvVerdictSub: TextView = id(R.id.tvVerdictSub)
    private val btnDeploy: Button = id(R.id.btnDeploy)
    private val btnSoftReboot: Button = id(R.id.btnSoftReboot)
    private val btnActivateKsu: Button = id(R.id.btnActivateKsu)
    private val btnRefresh: Button = id(R.id.btnRefresh)
    private val btnShizuku: Button = id(R.id.btnShizuku)
    private val btnCleanup: Button = id(R.id.btnCleanup)

    private val deployer by lazy { Deployer(activity) { line -> log(line) } }

    private val version: String = runCatching {
        activity.packageManager.getPackageInfo(activity.packageName, 0).versionName
    }.getOrNull() ?: "?"

    /** UI 互斥标志 (执行中)。 */
    private var busy = false

    /** root 可用: KSU 的 su 通了, 或临时 root (内置 su) 在线。 */
    private var rootAvailable = false

    /** KSU 可用 —— 与临时 root 分开记: 「软重启」要 ksud, 只有 KSU 能提供。 */
    private var ksuAvailable = false

    /** 最近一次设备判定结果 (部署前的门控依据)。 */
    private var devInfo: DeviceGate.Info? = null

    private var shown = false

    /**
     * 进度订阅器。
     *
     * 主页**不显示运行日志** —— 日志归日志页（那里能按日期翻历史、能导出）。
     * 这里只用"转圈 + 百分比 + 阶段名"表示部署走到哪一步。
     */
    private val onProgress: (Progress.State) -> Unit = { st -> renderProgress(st) }

    init {
        // 版本号在大状态卡右上角 (顶栏只留标题, 对齐 KernelSU 那种干净顶栏)
        id<TextView>(R.id.tvVerdictVersion).text = version

        btnDeploy.setOnClickListener { startDeploy() }
        btnSoftReboot.setOnClickListener {
            runBusy { deployer.softReboot(); refreshStatus(quick = true) }
        }
        btnActivateKsu.setOnClickListener {
            confirm(R.string.dlg_activate_title, R.string.dlg_activate_msg) {
                runBusy { deployer.activateKsu(); refreshStatus(quick = true) }
            }
        }
        btnRefresh.setOnClickListener { refreshStatus(quick = true) }
        btnShizuku.setOnClickListener {
            runBusy { deployer.requestPermission(); refreshStatus(quick = true) }
        }
        btnCleanup.setOnClickListener {
            confirm(R.string.dlg_cleanup_title, R.string.dlg_cleanup_msg) {
                runBusy { deployer.cleanupAndReboot() }
            }
        }
        // 终端是二级页 (独立 Activity), 用同一套滑动过渡打开
        id<TextView>(R.id.btnTerminalTop).setOnClickListener { Dock.openTerminal(activity) }

        if (forceNoRoot) log("调试模式: 强制按「无 root」渲染 (验证按钮门控)")
        log("== KSUOneTap $version ==")
        log("uid=${android.os.Process.myUid()}")

        applyModeToUi()
        renderProgress(Progress.state())
        refreshStatus()
    }

    override fun onShow() {
        // 首次显示: 首屏检测已在 init 跑过, 这里只接上进度订阅
        if (!shown) {
            shown = true
            Progress.addListener(onProgress)
            renderProgress(Progress.state())
            return
        }
        // 再回来时 (改完设置模式 / 从后台切回) 做一次快速刷新
        applyModeToUi()
        refreshStatus(quick = true)
    }

    override fun onHide() {
        if (!shown) return
        shown = false
        Progress.removeListener(onProgress)
    }

    // ---------------- 日志与小工具 ----------------

    private fun log(line: String) {
        android.util.Log.i("KSUONETAP", line)
        LogBus.append(line)
    }

    private fun toast(msg: String) {
        activity.runOnUiThread { Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show() }
    }

    /** 危险操作前的确认弹窗 (只用 framework AlertDialog)。 */
    private fun confirm(titleRes: Int, msgRes: Int, onOk: () -> Unit) {
        AlertDialog.Builder(activity)
            .setTitle(activity.getString(titleRes))
            .setMessage(activity.getString(msgRes))
            .setNegativeButton(activity.getString(R.string.dlg_cancel), null)
            .setPositiveButton(activity.getString(R.string.dlg_ok)) { _, _ -> onOk() }
            .show()
    }

    /**
     * 进度行渲染。
     *
     *   跑动中  → 显示（转圈 + `45%` + 阶段名）
     *   失败    → 定格显示（百分比停在失败那一刻，label 换成"失败：原因"）
     *   完成/空闲 → 隐藏（结论由上方状态卡表达）
     */
    private fun renderProgress(st: Progress.State) {
        val show = st.running || st.failed
        progressRow.visibility = if (show) View.VISIBLE else View.GONE
        if (!show) return
        tvProgressPct.text = activity.getString(R.string.pct_fmt, st.pct)
        tvProgressLabel.text =
            if (st.failed) activity.getString(R.string.pct_failed, st.label) else st.label
    }

    // ---------------- 状态 ----------------

    private fun setChip(value: TextView, dot: TextView, text: String, state: State) {
        value.text = text
        val colorRes = when (state) {
            State.OK -> R.color.state_ok
            State.WARN -> R.color.state_warn
            State.ERR -> R.color.state_err
            State.IDLE -> R.color.state_idle
        }
        val bgRes = when (state) {
            State.OK -> R.drawable.chip_ok
            State.WARN -> R.drawable.chip_warn
            State.ERR -> R.drawable.chip_err
            State.IDLE -> R.drawable.chip_idle
        }
        val c = activity.getColor(colorRes)
        value.setTextColor(c)
        value.setBackgroundResource(bgRes)
        dot.setTextColor(c)
    }

    /** @param quick true 时不等待 Shizuku 注入就绪 (避免每次切回都卡十几秒) */
    private fun refreshStatus(quick: Boolean = false) {
        setChip(tvDevice, dotDevice, activity.getString(R.string.state_checking), State.IDLE)
        setChip(tvShizuku, dotShizuku, activity.getString(R.string.state_checking), State.IDLE)
        setChip(tvKsu, dotKsu, activity.getString(R.string.state_checking), State.IDLE)
        setChip(tvRoot, dotRoot, activity.getString(R.string.state_checking), State.IDLE)

        Thread {
            // GhostLock 版本: exploit 随 APK, 不需要 Shizuku —— 整条 Shizuku 等待/检测都跳过
            val isGl = DeviceGate.isGhostlockBuild(DeviceGate.probe(shellOk = false).buildDir)

            // Shizuku 的 binder 注入是异步的 (provider attach), 未就绪时等一会
            if (!quick && !isGl) {
                for (i in 0 until 15) {
                    if (ShizukuBridge.ping()) break
                    Thread.sleep(1_000)
                }
            }
            val hooked = !isGl && ShizukuBridge.ping()

            // 设备判定: 基本信息用 Build.* (不需要权限), OriginOS 版本在 shell 可用时补
            val info = DeviceGate.probe(hooked)
            devInfo = info
            val devState = if (info.fullySupported) State.OK
            else if (!info.isTargetDevice) State.ERR else State.WARN
            val devText = if (info.fullySupported) "已适配"
            else if (!info.isTargetDevice) "${info.device} 非目标机型"
            else info.summary

            if (isGl) {
                // GhostLock 流程: Shizuku 行常显"不需要"; root 状态看本地 daemon socket
                val glRoot = GhostlockRunner.latest?.rootReady() == true
                activity.runOnUiThread {
                    setChip(tvDevice, dotDevice, devText, devState)
                    setChip(tvShizuku, dotShizuku, "不需要（GhostLock 流程）", State.OK)
                    setChip(tvKsu, dotKsu, "不适用（仅临时 root）", State.IDLE)
                    setChip(tvRoot, dotRoot,
                        if (glRoot) "uid=0 (GhostLock 临时 root)" else "未提权",
                        if (glRoot) State.OK else State.WARN)
                    // 大状态卡: ghostlock 专用文案 (updateVerdict 的 when 只覆盖旧流程)
                    val (t, sub, st) = if (glRoot) Triple(
                        activity.getString(R.string.verdict_temp_root),
                        activity.getString(R.string.verdict_sub_gl_ready), State.OK)
                    else Triple(
                        activity.getString(R.string.verdict_gl_ready_go),
                        activity.getString(R.string.verdict_sub_gl_ready_go), State.WARN)
                    tvVerdict.text = t
                    tvVerdict.setTextColor(activity.getColor(
                        if (st == State.OK) R.color.state_ok else R.color.state_warn))
                    dotVerdict.setTextColor(activity.getColor(
                        if (st == State.OK) R.color.state_ok else R.color.state_warn))
                    tvVerdictSub.text = sub
                }
                return@Thread
            }

            var shizukuState = State.ERR
            var shizukuText = "未激活（需电脑 adb 激活）"
            var ksuState = State.IDLE
            var ksuText = activity.getString(R.string.state_unknown)
            var rootState = State.IDLE
            var rootText = activity.getString(R.string.state_unknown)
            var rooted = false
            var ksuRootSeen = false

            if (hooked) {
                shizukuState = State.OK
                // 值这一栏宽度有限 (右侧还有胶囊), 所以只留最关键的 uid; SELinux 域见日志
                shizukuText = "已激活 · uid ${ShizukuBridge.uid()}"

                val mods = Shell.run("cat /proc/modules 2>/dev/null | grep -c kernelsu", 3_000).trim()
                val loaded = mods.contains("1")
                ksuState = if (loaded) State.OK else State.WARN
                ksuText = if (loaded) "已加载" else "未加载"

                // root 判据与 Terminal 的 KSU 通道保持一致: **不只认随包的 su_ksu** ——
                // 手工装 KSU（adb root + ksud insmod）时设备上只有 KernelSU 自己装的
                // /system/bin/su，只认 su_ksu 会把"已加载"误判成"无 root"（2026-09-28 真机踩到）
                val ksuRoot = Terminal.ksuUsable()
                val tempRoot = !ksuRoot && Terminal.tempUsable()
                rooted = ksuRoot || tempRoot
                ksuRootSeen = ksuRoot

                rootState = when {
                    ksuRoot -> State.OK
                    tempRoot -> State.OK
                    else -> State.WARN
                }
                rootText = when {
                    ksuRoot -> "uid=0 (KSU)"
                    tempRoot -> "uid=0 (临时 root)"
                    else -> "无 root"
                }
            }

            val finalRooted = rooted
            val finalKsuRoot = ksuRootSeen
            activity.runOnUiThread {
                setChip(tvDevice, dotDevice, devText, devState)
                setChip(tvShizuku, dotShizuku, shizukuText, shizukuState)
                setChip(tvKsu, dotKsu, ksuText, ksuState)
                setChip(tvRoot, dotRoot, rootText, rootState)

                rootAvailable = finalRooted && !forceNoRoot
                ksuAvailable = finalKsuRoot && !forceNoRoot
                updateVerdict(hooked, finalRooted, finalKsuRoot)
                updateActionButtons()
                if (!busy) {
                    // 每次刷新都给准确的结论 (之前用"保持原文本"会让首次就绪时显示错误的提示)
                    tvHint.text = when {
                        !hooked -> activity.getString(R.string.hint_ready)
                        !finalRooted -> activity.getString(R.string.hint_need_root)
                        finalKsuRoot -> activity.getString(R.string.hint_ok)
                        else -> activity.getString(R.string.hint_ok_root_only)
                    }
                }
            }
        }.start()
    }

    // ---------------- 由后台线程驱动的操作 ----------------

    private fun runBusy(block: () -> Unit) {
        setButtonsEnabled(false)
        busy = true
        tvHint.text = activity.getString(R.string.hint_busy)
        Thread {
            try {
                block()
            } catch (t: Throwable) {
                log("异常: $t")
            } finally {
                activity.runOnUiThread {
                    busy = false
                    setButtonsEnabled(true)
                }
            }
        }.start()
    }

    private fun startDeploy() {
        val rootOnly = Prefs.isRootOnly(activity)
        val titleRes = if (rootOnly) R.string.dlg_deploy_root_only_title else R.string.dlg_deploy_title
        val msgRes = if (rootOnly) R.string.dlg_deploy_root_only_msg else R.string.dlg_deploy_msg
        var msg = activity.getString(msgRes)

        // 设备门控: 机型/版本不匹配时把风险讲清再让用户决定
        val info = devInfo
        if (info != null && !info.fullySupported) {
            msg = activity.getString(R.string.dlg_gate_msg, info.detail) + "\n\n" + msg
        }

        AlertDialog.Builder(activity)
            .setTitle(activity.getString(titleRes))
            .setMessage(msg)
            .setNegativeButton(activity.getString(R.string.dlg_cancel), null)
            .setPositiveButton(activity.getString(R.string.dlg_ok)) { _, _ ->
                runBusy { finishDeploy() }
            }
            .show()
    }

    private fun finishDeploy() {
        val rootOnly = Prefs.isRootOnly(activity)
        val ok = deployer.run()
        activity.runOnUiThread {
            tvHint.text = activity.getString(
                if (ok) {
                    if (rootOnly) R.string.hint_ok_root_only else R.string.hint_ok
                } else {
                    R.string.hint_fail
                }
            )
        }
        refreshStatus(quick = true)
    }

    /** 按设置里的模式改主按钮文案。 */
    private fun applyModeToUi() {
        if (busy) return
        btnDeploy.text = activity.getString(
            if (Prefs.isRootOnly(activity)) R.string.btn_deploy_root_only else R.string.btn_deploy
        )
    }

    private fun setButtonsEnabled(enable: Boolean) {
        btnDeploy.isEnabled = enable
        btnRefresh.isEnabled = enable
        btnShizuku.isEnabled = enable
        if (enable) {
            applyModeToUi()
        } else {
            btnDeploy.text = activity.getString(R.string.btn_deploy_busy)
        }

        val alpha = if (enable) 1f else 0.5f
        btnDeploy.alpha = alpha
        btnRefresh.alpha = alpha
        btnShizuku.alpha = alpha

        updateActionButtons()
    }

    /**
     * 大状态卡: 把四行明细压成**一句话结论** + 一行下一步提示 (参考 KernelSU 主界面的
     * 大字状态)。结论色与圆点一致, 让"现在能不能用"一眼可见。
     */
    private fun updateVerdict(hooked: Boolean, rooted: Boolean, ksuRoot: Boolean) {
        val (titleRes, subRes, state) = when {
            !hooked -> Triple(R.string.verdict_no_shizuku, R.string.verdict_sub_no_shizuku, State.ERR)
            ksuRoot -> Triple(R.string.verdict_ok, R.string.verdict_sub_ok, State.OK)
            rooted -> Triple(R.string.verdict_temp_root, R.string.verdict_sub_temp_root, State.WARN)
            else -> Triple(R.string.verdict_not_ready, R.string.verdict_sub_not_ready, State.ERR)
        }
        val color = activity.getColor(
            when (state) {
                State.OK -> R.color.state_ok
                State.WARN -> R.color.state_warn
                State.ERR -> R.color.state_err
                State.IDLE -> R.color.state_idle
            }
        )
        tvVerdict.text = activity.getString(titleRes)
        tvVerdict.setTextColor(color)
        dotVerdict.setTextColor(color)
        tvVerdictSub.text = activity.getString(subRes)
    }

    /**
     * 「激活 KSU」与「软重启」共用同一个按钮槽位, 二选一显示:
     *   有 root 但 KSU 还没起来 -> 「激活 KSU」(仅提取 root 模式下就是这种状态)
     *   KSU 已激活            -> 「软重启」
     * 「清理并重启」只要有能提权的客户端就行。
     */
    private fun updateActionButtons() {
        val canActivate = !busy && rootAvailable && !ksuAvailable
        val rebootOk = !busy && ksuAvailable
        val cleanupOk = !busy && rootAvailable

        btnActivateKsu.visibility = if (canActivate) View.VISIBLE else View.GONE
        btnSoftReboot.visibility = if (canActivate) View.GONE else View.VISIBLE

        btnCleanup.isEnabled = cleanupOk
        btnCleanup.alpha = if (cleanupOk) 1f else 0.4f
        btnSoftReboot.isEnabled = rebootOk
        btnSoftReboot.alpha = if (rebootOk) 1f else 0.4f
    }
}
