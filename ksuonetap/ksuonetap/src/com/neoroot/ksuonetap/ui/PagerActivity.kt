package com.neoroot.ksuonetap.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import com.neoroot.ksuonetap.R
import com.neoroot.ksuonetap.core.Theme
import com.neoroot.ksuonetap.ui.page.AboutPage
import com.neoroot.ksuonetap.ui.page.HomePage
import com.neoroot.ksuonetap.ui.page.LogPage
import com.neoroot.ksuonetap.ui.page.Page
import com.neoroot.ksuonetap.ui.page.SettingsPage

/**
 * 唯一的容器 Activity —— 四个页面 (主页 / 日志 / 设置 / 关于) 是 [SwipePager] 里的四个
 * 子 View, 底部 dock 固定不动。
 *
 * 为什么合并成一个: 要的是**跟手左右滑**。四个独立 Activity 只能做转场动画, 做不了
 * "手指拖到一半松手回弹"; 单 Activity + 分页容器才能做到。
 *
 * 生命周期: 各页面的 onShow / onHide 由这里转发 —— 切页时一次, Activity 前后台各一次。
 * 所以页面实现必须**幂等** (自己记 shown 标志)。
 */
class PagerActivity : Activity() {

    private lateinit var pager: SwipePager
    private lateinit var pages: List<Page>

    /** 当前已 onShow 的页序号 (避免重复回调)。 */
    private var shownIndex = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        Theme.apply(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pager)

        val strip = findViewById<ViewGroup>(R.id.pagerStrip)
        val roots = (0 until strip.childCount).map { strip.getChildAt(it) }
        val forceNoRoot = intent?.getBooleanExtra("no_root", false) == true

        pages = listOf(
            HomePage(this, roots[0], forceNoRoot),
            LogPage(this, roots[1]),
            SettingsPage(this, roots[2]),
            AboutPage(this, roots[3])
        )

        pager = findViewById(R.id.pager)
        // 四个页面各有自己的顶栏 -> 都要让出状态栏; dock 让出导航栏
        Insets.apply(this, roots.mapNotNull { it.findViewById(R.id.topBar) }, findViewById(R.id.dock))
        Dock.attach(this, pager)

        // 换主题色板会 recreate 整个容器 -> 用 savedInstanceState 停在原来那一页;
        // 从二级页 (内置终端) 的 dock 点 tab 回来则走 Intent extra (REORDER_TO_FRONT 复用实例)
        shownIndex = (savedInstanceState?.getInt(KEY_PAGE)
            ?: intent?.getIntExtra(EXTRA_PAGE, 0) ?: 0).coerceIn(0, pages.size - 1)
        pages[shownIndex].onShow()
        // 等测量完再定位 (onCreate 时 pager 宽度还是 0, scrollTo 会算错)
        pager.post { pager.setCurrentItem(shownIndex, smooth = false) }
        Dock.highlight(this, shownIndex)

        pager.onPageChanged = { next ->
            if (next != shownIndex) {
                pages[shownIndex].onHide()
                shownIndex = next
                pages[next].onShow()
                Dock.highlight(this, next)
            }
        }
    }

    /**
     * 从二级页 (内置终端) 的 dock 点 tab 回来时会走到这里 (容器实例被 REORDER_TO_FRONT 复用,
     * 不重新 create)。走 [SwipePager.setCurrentItem] 而不是直接改状态, 是为了让
     * onPageChanged 里的 onHide / onShow / 高亮三件事照常发生。
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val target = (intent.getIntExtra(EXTRA_PAGE, shownIndex)).coerceIn(0, pages.size - 1)
        if (target != shownIndex) pager.setCurrentItem(target)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_PAGE, shownIndex)
    }

    override fun onStart() {
        super.onStart()
        pages[shownIndex].onShow()
    }

    override fun onStop() {
        pages[shownIndex].onHide()
        super.onStop()
    }

    /** 返回键: 不在主页时先滑回主页, 在主页再按才退出。 */
    override fun onBackPressed() {
        if (shownIndex != 0) pager.setCurrentItem(0) else super.onBackPressed()
    }

    companion object {
        /** 换主题 recreate 时用: 停在原来那一页 (savedInstanceState)。 */
        private const val KEY_PAGE = "pager_index"

        /** 二级页 (内置终端) 点 dock 回来时用: 指定落在第几页 (Intent extra)。 */
        const val EXTRA_PAGE = "pager_index"
    }
}
