package com.neoroot.ksuonetap.ui.page

import android.app.Activity
import android.view.View

/**
 * 分页容器里的一个页面。
 *
 * 页面不再是独立 Activity, 而是 [com.neoroot.ksuonetap.ui.SwipePager] 里的一个子 View ——
 * 生命周期因此简化成 [onShow] / [onHide] (被切到 / 切走时调用), 对应原来的 onResume / onPause。
 * 两者都会在 Activity 的 onStart/onStop 里一并转发, 所以实现要**幂等** (自己记一个 shown 标志)。
 *
 * **所有 findViewById 都走 [root]**: 四个页面的 layout 里存在同名 id
 * (topBar / tvLog / btnCopyLog ...), 从 Activity 查会串到别的页去。
 */
abstract class Page(protected val activity: Activity, val root: View) {

    protected fun <T : View> id(resId: Int): T = root.findViewById(resId)

    /** 页被切到 (或 Activity 回到前台)。可能重复调用, 实现要幂等。 */
    open fun onShow() {}

    /** 页被切走 (或 Activity 退到后台)。可能重复调用, 实现要幂等。 */
    open fun onHide() {}
}
