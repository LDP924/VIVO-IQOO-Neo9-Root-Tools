package com.neoroot.ksuonetap.core

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 全局日志缓冲。
 *
 * 部署流程在后台线程写日志, 而日志页要读 —— 放这里比各自持一个 StringBuilder 省心:
 * 切页面时历史不丢。
 *
 * **每一行都同时落到 [LogStore]**（按日期分文件、边写边 flush），所以:
 *   - 进程被杀 / 崩溃后，日志仍在 `<filesDir>/logs/<日期>.txt` 里
 *   - 日志页可以按日期翻历史、导出
 * 落盘同步进行（见 [LogStore.append] 的注释），失败也不影响主流程。
 *
 * 监听回调统一在主线程触发, 所以后台线程 append 后页面能直接刷新 TextView
 * (setText 必须在主线程)。
 */
object LogBus {
    private val buf = StringBuilder()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    /** 追加一行 (自动补 `[HH:mm:ss]` 前缀与换行, 并落盘)。 */
    fun append(line: String) {
        val stamped = "[${LogStore.now()}] $line"
        synchronized(buf) { buf.append(stamped).append('\n') }
        LogStore.append(stamped)
        notifyChanged()
    }

    /** 全文 (内存里这一份 = "本次会话从启动到现在的日志")。 */
    fun text(): String = synchronized(buf) { buf.toString() }

    /**
     * 清空内存缓冲**与当天的落盘文件**（其他日期的历史保留）。
     *
     * 只清内存不够 —— 日志页能从文件读回来, 看起来像"没清掉"。
     */
    fun clear() {
        synchronized(buf) { buf.setLength(0) }
        LogStore.truncate(LogStore.today())
        notifyChanged()
    }

    fun addListener(l: () -> Unit) {
        listeners.add(l)
    }

    fun removeListener(l: () -> Unit) {
        listeners.remove(l)
    }

    private fun notifyChanged() {
        main.post { for (l in listeners) l() }
    }
}
