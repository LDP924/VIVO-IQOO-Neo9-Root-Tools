package com.neoroot.ksuonetap.ui.page

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.TextView
import android.widget.Toast
import com.neoroot.ksuonetap.R
import com.neoroot.ksuonetap.core.Ansi
import com.neoroot.ksuonetap.core.LogBus
import com.neoroot.ksuonetap.core.LogStore
import com.neoroot.ksuonetap.ui.TailFollower

/**
 * 日志页：同一块显示区，两个数据源。
 *
 *   - **实时**（默认）：[LogBus] 的内存缓冲，本次会话从启动到现在
 *   - **历史**：落盘的 `<filesDir>/logs/<日期>.txt`（[LogStore]），按日期翻
 *
 * 落盘是 [LogBus] 每写一行就做的（按日期分文件 + 立即 flush，见 [LogStore] 的注释），
 * 所以进程被杀 / 崩溃都不会丢最后几行 —— 「历史」看到的就是当时真正写下去的东西。
 *
 * 「导出」走系统分享（可存成文件 / 发到别处）：不需要任何存储权限，也不用 FileProvider。
 */
class LogPage(activity: Activity, root: View) : Page(activity, root) {

    private val tvLog: TextView = id(R.id.tvLog)
    private val tail = TailFollower(id(R.id.logScroll), id<View>(R.id.btnJumpLatest))

    private var shown = false

    /** 当前在看哪一天的历史；null = 本次会话（实时）。 */
    private var viewingDay: String? = null

    /** 实时模式下日志增长要跟着刷新；看历史时不动（历史是静态的）。 */
    private val onChange: () -> Unit = { if (viewingDay == null) refresh() }

    init {
        id<TextView>(R.id.btnCopyLog).setOnClickListener { copyAll() }
        id<TextView>(R.id.btnExportLog).setOnClickListener { export() }
        id<TextView>(R.id.btnHistoryLog).setOnClickListener { pickDay() }
        id<TextView>(R.id.btnClearLog).setOnClickListener { clearCurrent() }
        refresh()
    }

    override fun onShow() {
        refresh()
        if (shown) return
        shown = true
        LogBus.addListener(onChange)
    }

    override fun onHide() {
        if (!shown) return
        shown = false
        LogBus.removeListener(onChange)
    }

    // ---------------- 渲染 ----------------

    /** setText 会重置滚动位置 —— 先记位置、写完再恢复; 只在"跟随最新"时才滚到底。 */
    private fun refresh() {
        val y = tail.beforeChange()
        tvLog.text = Ansi.render(currentText(), tvLog.currentTextColor)
        tail.afterChange(y)
    }

    /** 当前显示区该是什么内容。 */
    private fun currentText(): String {
        val day = viewingDay
            ?: return LogBus.text().ifEmpty { activity.getString(R.string.log_empty) }
        val body = LogStore.read(day)
        return if (body.isEmpty()) {
            activity.getString(R.string.log_day_empty)
        } else {
            activity.getString(R.string.log_day_head, day) + "\n" + body
        }
    }

    // ---------------- 操作 ----------------

    /** 复制**当前显示**的内容（看历史时就是那一天）。 */
    private fun copyAll() {
        val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("KSUOneTap log", currentText()))
        toast(activity.getString(R.string.copied))
    }

    /** 导出当前显示的日志：走系统分享，用户可选"保存到文件"/发送。 */
    private fun export() {
        val text = currentText()
        if (text.isBlank()) {
            toast(activity.getString(R.string.export_none))
            return
        }
        val name = "KSUOneTap-${viewingDay ?: LogStore.today()}.txt"
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, name)
            putExtra(Intent.EXTRA_TEXT, text)
        }
        activity.startActivity(
            Intent.createChooser(send, activity.getString(R.string.export_title))
        )
    }

    /** 选一天看历史；第一项是「本次会话（实时）」。 */
    private fun pickDay() {
        val days = LogStore.days()
        val items = ArrayList<String>(days.size + 1)
        items += activity.getString(R.string.log_live)
        items += days
        AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.log_history_title))
            .setItems(items.toTypedArray()) { _, which ->
                viewingDay = if (which == 0) null else items[which]
                refresh()
            }
            .setNegativeButton(activity.getString(R.string.dlg_cancel), null)
            .show()
    }

    /** 清空当前显示的那一份（实时 = 内存 + 当天文件；历史 = 那一天的文件）。 */
    private fun clearCurrent() {
        val day = viewingDay
        if (day == null) LogBus.clear() else LogStore.truncate(day)
        refresh()
        toast(activity.getString(R.string.cleared))
    }

    private fun toast(msg: String) {
        Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show()
    }
}
