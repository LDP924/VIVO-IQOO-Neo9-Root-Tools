package com.neoroot.ksuonetap.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import com.neoroot.ksuonetap.R

/**
 * 底部 dock —— 主页 / 日志 / 设置 / 关于四个入口。
 *
 * 四个页面是 [SwipePager] 里的四个子 View, 容器布局与四个页面都 include 了同一份
 * `view_dock.xml`; 这里只负责**点按切页**与**高亮**。
 *
 * 高亮没有做在 onPageChanged 里 —— 那是容器的回调, 由 [PagerActivity] 组合调用,
 * 免得两处都去覆盖 `pager.onPageChanged` 相互顶掉。
 */
object Dock {

    private class Item(val tabId: Int, val iconId: Int, val labelId: Int)

    /** 顺序 = 页面顺序 (主页 / 日志 / 设置 / 关于), 与 SwipePager 的子 View 一一对应。 */
    private val ITEMS = listOf(
        Item(R.id.tabHome, R.id.icHome, R.id.lbHome),
        Item(R.id.tabLog, R.id.icLog, R.id.lbLog),
        Item(R.id.tabSettings, R.id.icSettings, R.id.lbSettings),
        Item(R.id.tabAbout, R.id.icAbout, R.id.lbAbout)
    )

    /** 绑定点按: 点第 i 项就切到第 i 页 (跟手滑动也会走到同一页)。 */
    fun attach(activity: Activity, pager: SwipePager) {
        for ((i, item) in ITEMS.withIndex()) {
            activity.findViewById<View>(item.tabId)?.setOnClickListener {
                pager.setCurrentItem(i)
            }
        }
    }

    /**
     * 二级页 (内置终端) 的 dock 绑定 —— 那里没有 [SwipePager], 点 tab = 结束本页、
     * 回到容器的第 i 页。
     *
     * 用 REORDER_TO_FRONT 复用已在任务栈里的容器实例 (不新开一个, 否则返回栈会越堆越深),
     * 页码经 [PagerActivity.EXTRA_PAGE] 交给 onNewIntent。
     */
    fun attachSecondary(activity: Activity) {
        for ((i, item) in ITEMS.withIndex()) {
            activity.findViewById<View>(item.tabId)?.setOnClickListener {
                activity.startActivity(
                    Intent(activity, PagerActivity::class.java)
                        .putExtra(PagerActivity.EXTRA_PAGE, i)
                        .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                )
                // 与 openTerminal 相反的方向: 容器从左侧回来, 本页右移退出
                activity.overridePendingTransition(R.anim.dock_in_left, R.anim.dock_out_right)
                activity.finish()
            }
        }
    }

    /** 高亮第 index 项; null = 四项都不高亮 (终端页用 [dim])。 */
    fun highlight(activity: Activity, index: Int?) {
        val active = attrColor(activity, R.attr.ksuPrimary)
        val idle = activity.getColor(R.color.text_tertiary)

        for ((i, item) in ITEMS.withIndex()) {
            val icon = activity.findViewById<ImageView>(item.iconId) ?: continue
            val label = activity.findViewById<TextView>(item.labelId) ?: continue
            val on = i == index
            val color = if (on) active else idle

            icon.setColorFilter(color)
            label.setTextColor(color)
            label.typeface = if (on) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
    }

    /** 终端页: 它不属于 dock 四项, 四项都不高亮。 */
    fun dim(activity: Activity) = highlight(activity, null)

    /** 打开内置终端 (二级页, 独立 Activity) —— 用同一套滑动过渡。 */
    fun openTerminal(activity: Activity) {
        activity.startActivity(Intent(activity, TerminalActivity::class.java))
        activity.overridePendingTransition(R.anim.dock_in_right, R.anim.dock_out_left)
    }

    /** 解析主题 attr 的颜色 (getColor 不吃 attr, 得走 obtainStyledAttributes)。 */
    private fun attrColor(ctx: Context, attr: Int): Int {
        val ta = ctx.obtainStyledAttributes(intArrayOf(attr))
        val c = ta.getColor(0, 0)
        ta.recycle()
        return c
    }
}
