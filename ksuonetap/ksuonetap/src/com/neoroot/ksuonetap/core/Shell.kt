package com.neoroot.ksuonetap.core

import java.io.ByteArrayOutputStream

/**
 * shell 执行原语: 统一带超时, 避免后台线程被卡死的命令拖住。
 *
 * 不需要在这个类里处理"进程未退出"的异常 —— Shizuku 的 RemoteProcess 在进程未结束时
 * exitValue() 抛的是 IllegalArgumentException (JDK 的 isAlive/waitFor 只捕获
 * IllegalThreadStateException, 异常会漏出来), 所以这里自己包一层判断。
 */
object Shell {

    /** 单条命令的输出上限, 防止 `cat 大文件` 把内存吃光 (超出继续读但不保存, 否则管道会堵住子进程)。 */
    private const val MAX_OUT = 1 shl 20   // 1 MB

    /** 起进程 (只在本文件用: `run` / `pipeTo` 是唯二的出口)。 */
    private fun exec(cmd: String): Process = ShizukuBridge.newProcess(cmd)

    /** 进程是否已结束 (不能直接用 Process.isAlive / waitFor, 见文件头注释)。 */
    private fun exited(p: Process): Boolean = try {
        p.exitValue()
        true
    } catch (t: Throwable) {
        false
    }

    /** 跑命令并收集 stdout+stderr, 最多等 timeoutMs。 */
    fun run(cmd: String, timeoutMs: Long = 5_000): String {
        val out = ByteArrayOutputStream()
        val chunk = ByteArray(8192)
        var total = 0
        var truncated = false
        try {
            val p = exec(cmd)
            val ins = p.inputStream
            val err = p.errorStream
            val deadline = System.currentTimeMillis() + timeoutMs
            var exitedAt = 0L

            fun take(n: Int) {
                if (n <= 0) return
                if (total < MAX_OUT) {
                    val w = minOf(n, MAX_OUT - total)
                    out.write(chunk, 0, w)
                    total += w
                    if (w < n) truncated = true
                } else {
                    truncated = true
                }
            }

            while (System.currentTimeMillis() < deadline) {
                var idle = true
                if (ins.available() > 0) {
                    take(ins.read(chunk))
                    idle = false
                }
                if (err.available() > 0) {
                    take(err.read(chunk))
                    idle = false
                }
                if (idle) {
                    if (exited(p)) {
                        // 关键: 进程退出时管道里往往还有没读完的数据。直接 break 会丢尾 ——
                        // 实测 `getprop` (几百行) 末尾的 `[rc=0]` 就是这么丢的, 表现为 [rc=?]。
                        // 所以退出后再补读一小段时间, 期间只要有数据就继续读并重置计时。
                        if (exitedAt == 0L) exitedAt = System.currentTimeMillis()
                        if (System.currentTimeMillis() - exitedAt > 250) break
                    }
                    Thread.sleep(20)
                } else {
                    exitedAt = 0L
                }
            }
            runCatching { p.destroy() }
        } catch (t: Throwable) {
            return "!! " + t
        }
        val s = out.toString("UTF-8")
        return if (truncated) "$s\n...(输出超过 ${MAX_OUT / 1024}KB, 已截断)\n" else s
    }

    /** 把字节流喂给目标命令的 stdin (往 /data/local/tmp 落文件用)。 */
    fun pipeTo(cmd: String, data: ByteArray, timeoutMs: Long = 120_000): Boolean = runCatching {
        val p = exec(cmd)
        p.outputStream.use { os ->
            var off = 0
            while (off < data.size) {
                val n = minOf(256 * 1024, data.size - off)
                os.write(data, off, n)
                off += n
            }
            os.flush()
        }
        // 等远端收尾。不用 waitFor(): Shizuku 版没有超时, 命令挂起会永久阻塞线程。
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline && !exited(p)) Thread.sleep(50)
        runCatching { p.destroy() }
        true
    }.getOrDefault(false)

    /** 读文件内容 (读不到返回空串)。 */
    fun readFile(path: String, timeoutMs: Long = 3_000): String =
        run("cat $path 2>/dev/null", timeoutMs)
}
