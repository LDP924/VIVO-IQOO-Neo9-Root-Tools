package com.neoroot.ksuonetap.core

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 部署进度（**阶段式**）。
 *
 * 为什么不是"平滑进度"：整个流程是"几个大步 + 一段时长完全不可控的等待"
 * （spray 命中要 40s~9 分钟），谁也没法给出真实百分比的连续值。所以按阶段跳:
 * [set] 报一次百分比与阶段名，等待阶段就**停在那个值上不动** —— 界面用转圈表示
 * "还在动"，用百分比与阶段名表示"走到哪了"。
 *
 * 谁在用：主页的进度行（转圈 + `45%` + 阶段名）。[Deployer] 在各阶段调 [set]。
 */
object Progress {
    /**
     * @param running true = 正在跑（界面显示转圈）
     * @param failed  true = 停在中途（界面定格并标红，[fail] 设的）
     * 两者都为 false 且 pct==0 时界面整行隐藏。
     */
    data class State(val pct: Int, val label: String, val running: Boolean, val failed: Boolean)

    @Volatile
    private var cur = State(0, "", false, false)

    private val listeners = CopyOnWriteArrayList<(State) -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    fun state(): State = cur

    /** 开始（把上一次残留的 100% 清掉）。 */
    fun start(label: String) = set(0, label)

    /** 报一个阶段。 */
    fun set(pct: Int, label: String) {
        cur = State(pct.coerceIn(0, 100), label, true, false)
        notifyChanged()
    }

    /** 成功收尾（100%，不再是"进行中"）。 */
    fun finish(label: String = "完成") {
        cur = State(100, label, false, false)
        notifyChanged()
    }

    /** 失败收尾：停在当前百分比并标出来（界面显示红色定格，好让用户知道卡在哪）。 */
    fun fail(label: String) {
        cur = State(cur.pct, label, false, true)
        notifyChanged()
    }

    fun reset() {
        cur = State(0, "", false, false)
        notifyChanged()
    }

    fun addListener(l: (State) -> Unit) {
        listeners.add(l)
    }

    fun removeListener(l: (State) -> Unit) {
        listeners.remove(l)
    }

    private fun notifyChanged() {
        val s = cur
        main.post { for (l in listeners) l(s) }
    }
}
