package com.neoroot.ksuonetap.core

import java.lang.reflect.Method

/**
 * Shizuku 反射桥。
 *
 * 不加编译期依赖 (aapt2 + d8 直接构建, 没有 gradle 依赖解析), 所以全部走反射;
 * rikka 相关的 class 由构建脚本从 deps 下的 aar 里取出并并入 dex。
 */
object ShizukuBridge {
    private const val CLS = "rikka.shizuku.Shizuku"

    private val cls: Class<*>? by lazy {
        runCatching { Class.forName(CLS) }.getOrNull()
    }

    /** binder 是否可用 (进程活着且已授权注入)。 */
    fun ping(): Boolean = runCatching {
        cls!!.getMethod("pingBinder").invoke(null) as Boolean
    }.getOrDefault(false)

    fun uid(): Int = runCatching {
        cls!!.getMethod("getUid").invoke(null) as Int
    }.getOrDefault(-1)

    /** 0 = 已授权, -1 = 未授权。 */
    fun selfPermission(): Int = runCatching {
        cls!!.getMethod("checkSelfPermission").invoke(null) as Int
    }.getOrDefault(-1)

    fun requestPermission() {
        runCatching {
            cls!!.getMethod("requestPermission", Int::class.javaPrimitiveType).invoke(null, 0)
        }
    }

    /** 以 shell 域起一个进程 (Shizuku.newProcess 是隐藏 API, 需 setAccessible)。 */
    fun newProcess(cmd: String): Process {
        val m: Method = cls!!.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java
        )
        m.isAccessible = true
        return m.invoke(null, arrayOf("/system/bin/sh", "-c", cmd), null, "/") as Process
    }
}
