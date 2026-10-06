package com.neoroot.ksuonetap.core

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 日志落盘 —— **按日期分文件**，**边写边落盘**（崩溃也不丢最后几行）。
 *
 * 位置：`<filesDir>/logs/<yyyy-MM-dd>.txt`（App 私有目录，不需要任何权限）。
 *
 * 为什么不做"临时文件 + 收尾归档"：按日期直接写更省事 —— 跨天、进程被杀、崩溃恢复
 * 都不需要额外动作，那个文件本身就是"实时落盘"的那一份。换天由 [append] 逐行判断。
 *
 * 为什么每行都 `write` + `flush`：`FileOutputStream` 没有用户态缓冲，写一次就是一次
 * write(2) —— 内核一收到就对外可见，所以"拔电/崩溃"最多丢正在写的那一行。
 * 日志量是"一次部署几百行"，这点系统调用开销可以忽略。
 */
object LogStore {
    private const val DIR = "logs"

    private val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    @Volatile
    private var root: File? = null

    /** 当前打开的日期文件与其句柄（换天时换文件）。 */
    private var out: FileOutputStream? = null
    private var outDay: String = ""

    /** 启动时调一次（[PagerActivity] / 二级页都可以调，幂等）。 */
    fun init(ctx: Context) {
        if (root == null) {
            root = File(ctx.applicationContext.filesDir, DIR).apply { mkdirs() }
        }
    }

    /** 今天的日期（`yyyy-MM-dd`）。 */
    fun today(): String = dayFmt.format(Date())

    /** 当前时刻（`HH:mm:ss`），给日志行做前缀。 */
    fun now(): String = timeFmt.format(Date())

    /** 日志目录（未 init 时为 null）。 */
    fun dir(): File? = root

    /**
     * 追加一行（自动补换行，立即落盘）。未 [init] 时静默跳过 —— 日志绝不能让主流程挂掉。
     */
    @Synchronized
    fun append(line: String) {
        val d = root ?: return
        val day = today()
        runCatching {
            if (out == null || outDay != day) {
                out?.close()
                out = FileOutputStream(File(d, "$day.txt"), true)
                outDay = day
            }
            out!!.write((line + "\n").toByteArray())
            out!!.flush()
        }
    }

    /** 清掉某天的**内容**（文件保留为空）。 */
    @Synchronized
    fun truncate(day: String) {
        val d = root ?: return
        runCatching {
            if (outDay == day) {
                out?.close()
                out = null
                outDay = ""
            }
            File(d, "$day.txt").writeText("")
        }
    }

    /** 已有的日志文件日期，从新到旧。 */
    fun days(): List<String> {
        val d = root ?: return emptyList()
        return (d.listFiles { f -> f.isFile && f.name.endsWith(".txt") } ?: emptyArray())
            .map { it.name.removeSuffix(".txt") }
            .sortedDescending()
    }

    /** 某一天的内容；读不到返回空串。 */
    fun read(day: String): String {
        val d = root ?: return ""
        return runCatching { File(d, "$day.txt").readText() }.getOrDefault("")
    }

    /** 全部历史（按日期从旧到新拼接，导出用）。 */
    fun readAll(): String {
        val sb = StringBuilder()
        for (day in days().sorted()) {
            val body = read(day)
            if (body.isEmpty()) continue
            sb.append("===== ").append(day).append(" =====\n").append(body)
            if (!body.endsWith("\n")) sb.append('\n')
        }
        return sb.toString()
    }

    /** 已占用的字节数（设置页展示用）。 */
    fun bytes(): Long {
        val d = root ?: return 0
        return (d.listFiles() ?: emptyArray()).sumOf { it.length() }
    }
}
