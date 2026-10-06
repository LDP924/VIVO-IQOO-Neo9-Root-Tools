package com.neoroot.ksuonetap.core

/**
 * 终端执行通道的标识与展示名。
 *
 * 单独成文件是为了断开 core 对 deploy 的依赖: `Prefs` 要记默认通道, 而通道实现
 * (deploy 包的 Terminal) 反过来要依赖 core 的执行原语。
 *
 * 顺序即下拉框顺序, 与 res/values/arrays.xml 的 term_channels 一致。
 */
object Channel {
    const val AUTO = "auto"
    const val SHELL = "shell"
    const val TEMP = "temp"
    const val KSU = "ksu"

    fun describe(ch: String): String = when (ch) {
        AUTO -> "自动"
        SHELL -> "Shizuku shell"
        TEMP -> "临时 root"
        KSU -> "KernelSU"
        else -> ch
    }

    fun all(): List<String> = listOf(AUTO, KSU, TEMP, SHELL)
}
