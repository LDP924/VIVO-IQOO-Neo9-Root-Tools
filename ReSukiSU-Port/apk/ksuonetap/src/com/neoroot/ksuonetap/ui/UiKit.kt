package com.neoroot.ksuonetap.ui

import android.app.Activity
import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.widget.ScrollView
import kotlin.math.abs

/**
 * 不被外层滚动容器抢手势的 ScrollView。
 *
 * 为什么需要它: 日志/终端输出区如果直接放在外层 ScrollView 里, 手指滑动会被外层
 * 拦截 —— 表现就是"日志区不能手动滑动", 只能整页滚。
 *
 * **只在垂直意图明确时才锁父容器**: 外层除了纵向 ScrollView, 还有横向的 [SwipePager];
 * 无条件锁会让"在日志区上左右滑切页"失效。判据是首次移动的 dx/dy 谁更大。
 */
class InnerScrollView @JvmOverloads constructor(
    ctx: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : ScrollView(ctx, attrs, defStyle) {

    private var downX = 0f
    private var downY = 0f

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
            }
            MotionEvent.ACTION_MOVE -> {
                if (abs(ev.y - downY) > abs(ev.x - downX)) {
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                parent?.requestDisallowInterceptTouchEvent(false)
        }
        return super.onInterceptTouchEvent(ev)
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) {
            parent?.requestDisallowInterceptTouchEvent(false)
        }
        return super.onTouchEvent(ev)
    }
}

/**
 * edge-to-edge 适配。targetSdk 34 + Android 15 默认全屏铺开: 顶栏让出状态栏高度,
 * 底部 dock 让出导航栏高度。
 *
 * 顶栏是**一组**而不是一个 —— 分页容器里四个页面各有自己的顶栏, 得一起处理。
 *
 * @param topBars 需要让出状态栏高度的顶栏 (每个各自叠加自己的原始 paddingTop)
 * @param bottom  需要让出导航栏高度的底部视图 (dock); null = 底部不留白
 */
object Insets {
    fun apply(activity: Activity, topBars: List<View>, bottom: View?) {
        val tops = topBars.map { it to it.paddingTop }
        val botBase = bottom?.paddingBottom ?: 0

        activity.findViewById<View>(android.R.id.content).setOnApplyWindowInsetsListener { _, insets ->
            for ((v, base) in tops) {
                v.setPadding(
                    v.paddingLeft, base + insets.systemWindowInsetTop,
                    v.paddingRight, v.paddingBottom
                )
            }
            bottom?.setPadding(
                bottom.paddingLeft, bottom.paddingTop,
                bottom.paddingRight, botBase + insets.systemWindowInsetBottom
            )
            insets
        }
    }
}

/**
 * 让一段"可滚动输出区"具备正确的自动跟随行为。
 *
 * 要解决的三个问题:
 *   1. 每来一行就把视图拉到底 -> 用户往回翻时被不断拽走 (表现就是"滑不动")
 *   2. TextView.setText() 会**重置滚动位置** -> 翻到中间看历史, 一来新日志就跳回顶部
 *   3. 日志区在固定高度容器里时, 不能自动把外层页面也带着滚
 *
 * 用法: 改文本前 [beforeChange] 取位置, 改完 [afterChange] 恢复;
 * 只有 [following] 为 true 时才滚到最新。
 */
class TailFollower(
    private val scroll: ScrollView,
    private val pill: View?
) {
    /** 是否处于"跟随最新"状态 (用户滑到别处就自动关掉)。 */
    var following = true
        private set

    private var suppress = false

    private fun atBottom(): Boolean {
        val content = scroll.getChildAt(0) ?: return true
        if (content.height <= scroll.height) return true
        val slop = (scroll.resources.displayMetrics.density * 24).toInt()
        return scroll.scrollY + scroll.height >= content.height - slop
    }

    private fun syncPill() {
        pill?.visibility = if (following) View.GONE else View.VISIBLE
    }

    init {
        scroll.setOnScrollChangeListener { _, _, _, _, _ ->
            if (suppress) return@setOnScrollChangeListener
            following = atBottom()
            syncPill()
        }
        // 手指一碰就算"我要自己看": 立刻停止跟随, 避免长内容时被反复拉回底部
        scroll.setOnTouchListener { _, ev ->
            if (ev.actionMasked == MotionEvent.ACTION_DOWN && !atBottom()) {
                following = false
                syncPill()
            }
            false
        }
        pill?.setOnClickListener { toTail() }
        syncPill()
    }

    /** 内容变化前调用, 记下当前位置。 */
    fun beforeChange(): Int = scroll.scrollY

    /** 内容变化后调用: 恢复位置; 原本在跟随则滚到最新。 */
    fun afterChange(prevY: Int) {
        suppress = true
        scroll.scrollTo(0, prevY)
        suppress = false
        if (following) scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    /** 手动回到最新。 */
    fun toTail() {
        following = true
        syncPill()
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }
}
