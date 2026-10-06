package com.neoroot.ksuonetap.ui

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.HorizontalScrollView

/**
 * 跟手左右滑的分页容器。
 *
 * 为什么不用 ViewPager: 构建链没有 AndroidX (无 gradle, 直接 aapt2+kotlinc+d8),
 * 而 framework 自带的 ViewPager 早已废弃。HorizontalScrollView 本身就支持跟手拖动
 * 与惯性滚动, 这里补上两件事就够了:
 *   1. 子页等宽 —— onMeasure 里把每个页面拉成与容器同宽, 否则 scrollX 与页序号对不上
 *   2. 松手 snap 到最近一页 (先看甩动速度, 速度不够再按位移是否过半), 不会停在两页之间
 *
 * 结构约定: 本容器的**唯一直接子**是一个横向 LinearLayout, 页面是它的子 View。
 */
class SwipePager @JvmOverloads constructor(
    ctx: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : HorizontalScrollView(ctx, attrs, defStyle) {

    private val minFling = ViewConfiguration.get(ctx).scaledMinimumFlingVelocity

    private var tracker: VelocityTracker? = null

    /** 当前页序号 (0-based)。 */
    var currentIndex = 0
        private set

    /** 页数 (= 横向条里的子 View 数)。 */
    val pageCount: Int get() = (getChildAt(0) as? ViewGroup)?.childCount ?: 0

    /** 切页落定后的回调 (snap 目标与原页不同才触发)。 */
    var onPageChanged: ((Int) -> Unit)? = null

    init {
        isHorizontalScrollBarEnabled = false
        overScrollMode = OVER_SCROLL_NEVER
    }

    /** 把每个页面拉成与容器等宽。 */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val strip = getChildAt(0) as? ViewGroup
        if (strip != null && width > 0) {
            for (i in 0 until strip.childCount) {
                strip.getChildAt(i).layoutParams.width = width
            }
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (pageCount <= 1) return super.onTouchEvent(ev)

        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                recycleTracker()
                tracker = VelocityTracker.obtain().also { it.addMovement(ev) }
            }

            MotionEvent.ACTION_MOVE -> tracker?.addMovement(ev)

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                tracker?.addMovement(ev)
                tracker?.computeCurrentVelocity(1000)
                snap((tracker?.xVelocity ?: 0f).toInt())
                recycleTracker()
                // 不把 UP 交给父类: 否则 ScrollView 会再触发一次 fling, 滚到两页之间
                return true
            }
        }
        return super.onTouchEvent(ev)
    }

    /** 松手判定: 先看甩动速度, 速度不够再按位移是否过半。 */
    private fun snap(vx: Int) {
        val pageW = width
        if (pageW <= 0) return

        val target = when {
            vx <= -minFling -> currentIndex + 1
            vx >= minFling -> currentIndex - 1
            else -> {
                val moved = scrollX - currentIndex * pageW
                when {
                    moved > pageW / 2 -> currentIndex + 1
                    moved < -pageW / 2 -> currentIndex - 1
                    else -> currentIndex
                }
            }
        }
        setCurrentItem(target)
    }

    /** 切到某页 (越界自动夹到有效范围)。 */
    fun setCurrentItem(index: Int, smooth: Boolean = true) {
        if (pageCount == 0) return
        val target = index.coerceIn(0, pageCount - 1)
        val changed = target != currentIndex
        currentIndex = target

        if (smooth) smoothScrollTo(target * width, 0) else scrollTo(target * width, 0)
        if (changed) post { onPageChanged?.invoke(target) }
    }

    private fun recycleTracker() {
        tracker?.recycle()
        tracker = null
    }
}
