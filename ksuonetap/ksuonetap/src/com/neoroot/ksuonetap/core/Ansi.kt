package com.neoroot.ksuonetap.core

import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan

/**
 * ANSI 终端颜色转义解析。
 *
 * 为什么需要: exploit 日志带颜色标记（`\u001B[32m[+]\u001B[0m` 这类 —— GhostLock
 * 原版日志同款），直接 setText 会把转义序列原样打进 TextView，用户看到的是
 * `[32m[+]` 乱码。这里把 SGR 序列（`ESC[...m`）翻译成 ForegroundColorSpan，
 * 其余 CSI 序列（清行、光标移动等）直接剥掉。
 *
 * 明暗主题: 调色板取中等明度，浅色卡片与深色终端上都可读。
 * 非 SGR 的转义不支持（日志里没用到）；256 色/truecolor 归到默认色。
 */
object Ansi {

    /** CSI 序列: ESC [ 参数(数字/;/?) 终止字母。 */
    private val CSI = Regex("\u001B\\[([0-9;?]*)([A-Za-z])")

    /** 16 色前 8 档（中等明度，明暗主题都可读）。 */
    private val PALETTE = intArrayOf(
        0xFF787878.toInt(),  // 30 black  (纯黑在深色主题不可见, 用深灰)
        0xFFE53935.toInt(),  // 31 red
        0xFF2E9E44.toInt(),  // 32 green
        0xFFC99600.toInt(),  // 33 yellow
        0xFF2196F3.toInt(),  // 34 blue
        0xFFAB47BC.toInt(),  // 35 magenta
        0xFF0097A7.toInt(),  // 36 cyan
        0xFFB0B0B0.toInt(),  // 37 white  (纯白在浅色主题不可见, 用浅灰)
    )

    /**
     * 把 [text] 渲染成着色的 [SpannableString]。
     * [defaultColor] 传 TextView 的 currentTextColor —— reset(0) / 无色段用它。
     * 文本不含 ESC 时原样返回（零开销路径）。
     */
    fun render(text: String, defaultColor: Int): CharSequence {
        if (!text.contains('\u001B')) return text

        val out = StringBuilder(text.length)
        // (start, end, color) 三元组列表
        val spans = ArrayList<IntArray>()
        var color = defaultColor
        var idx = 0

        while (idx < text.length) {
            val m = CSI.find(text, idx) ?: break
            // 转义序列之前的普通文本
            if (m.range.first > idx) out.append(text, idx, m.range.first)
            val segStart = out.length
            val next = CSI.find(text, m.range.last + 1)
            val segEndIn = next?.range?.first ?: text.length
            if (segEndIn > m.range.last + 1) out.append(text, m.range.last + 1, segEndIn)

            when (m.groupValues[2]) {
                "m" -> {
                    val params = m.groupValues[1].split(';').filter { it.isNotEmpty() }
                    if (params.isEmpty()) {
                        color = defaultColor            // ESC[m == ESC[0m
                    }
                    for (p in params) {
                        when (p.toIntOrNull() ?: 0) {
                            0 -> color = defaultColor
                            39 -> color = defaultColor
                            49 -> {}
                            in 30..37 -> color = PALETTE[p.toInt() - 30]
                            in 90..97 -> color = PALETTE[p.toInt() - 90]
                            else -> {}                  // 256色/粗体等: 保持当前色
                        }
                    }
                }
                else -> {}                              // 其余 CSI: 剥掉
            }
            if (out.length > segStart) spans.add(intArrayOf(segStart, out.length, color))
            idx = segEndIn
        }
        if (idx < text.length) out.append(text, idx, text.length)

        if (spans.isEmpty()) return out.toString()
        val ss = SpannableString(out)
        for ((s, e, c) in spans) {
            if (c != defaultColor && e > s) {
                ss.setSpan(ForegroundColorSpan(c), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        return ss
    }
}
