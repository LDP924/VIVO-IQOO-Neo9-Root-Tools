package com.neoroot.ksuonetap.ui.page

import android.app.Activity
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.RadioButton
import android.widget.TextView
import android.widget.Toast
import com.neoroot.ksuonetap.R
import com.neoroot.ksuonetap.core.DeviceGate
import com.neoroot.ksuonetap.core.Prefs
import com.neoroot.ksuonetap.core.ShizukuBridge
import com.neoroot.ksuonetap.core.Theme

/**
 * 设置页 (dock 第 3 项)。
 *
 *   - 一键提取模式 (单选, 改完立即生效): 仅提取 root / 提取 root 并部署 KSU
 *   - 外观: 主题色板 (6 套) + 深浅模式 (跟随系统 / 浅色 / 深色)
 *   - 当前设备判定: 机型 + 系统版本 + 是否已适配
 */
class SettingsPage(activity: Activity, root: View) : Page(activity, root) {

    private val rbRootKsu: RadioButton = id(R.id.rbRootKsu)
    private val rbRootOnly: RadioButton = id(R.id.rbRootOnly)
    private val optRootKsu: View = id(R.id.optRootKsu)
    private val optRootOnly: View = id(R.id.optRootOnly)

    /** 色点与深浅按钮的 id 表 —— 顺序必须与 Theme.SEEDS / Theme.modes() 一致。 */
    private val dotItemIds = intArrayOf(
        R.id.dotItem0, R.id.dotItem1, R.id.dotItem2,
        R.id.dotItem3, R.id.dotItem4, R.id.dotItem5
    )
    private val dotColorIds = intArrayOf(
        R.id.dotColor0, R.id.dotColor1, R.id.dotColor2,
        R.id.dotColor3, R.id.dotColor4, R.id.dotColor5
    )
    private val modeIds = intArrayOf(R.id.modeItem0, R.id.modeItem1, R.id.modeItem2)

    init {
        // 初始化选中态 (默认「提取 root 并部署 KSU」)
        select(Prefs.mode(activity))

        // 为什么不用 RadioGroup: 选项卡片里除了 RadioButton 还要放描述文本,
        // 而 RadioGroup 只认**直接子 View** 里的 RadioButton。所以这里显式互斥:
        // 用 setOnClickListener (程序化 setChecked 不会回调 click, 不会递归)。
        rbRootKsu.setOnClickListener { select(Prefs.MODE_ROOT_KSU) }
        optRootKsu.setOnClickListener { select(Prefs.MODE_ROOT_KSU) }
        rbRootOnly.setOnClickListener { select(Prefs.MODE_ROOT_ONLY) }
        optRootOnly.setOnClickListener { select(Prefs.MODE_ROOT_ONLY) }

        buildThemeCard()

        // 设备判定放后台 (getprop 需要 Shizuku; 拿不到也能用 Build.* 出结论)
        val tvDevice = id<TextView>(R.id.tvDeviceDetail)
        Thread {
            val info = DeviceGate.probe(ShizukuBridge.ping())
            activity.runOnUiThread { tvDevice.text = info.detail }
        }.start()
    }

    /** 选中某个模式: 两个 RadioButton 显式互斥 + 落盘 + 刷新卡片背景。 */
    private fun select(mode: String) {
        rbRootKsu.isChecked = mode == Prefs.MODE_ROOT_KSU
        rbRootOnly.isChecked = mode == Prefs.MODE_ROOT_ONLY
        Prefs.setMode(activity, mode)
        syncOptions()
    }

    private fun syncOptions() {
        optRootKsu.setBackgroundResource(
            if (rbRootKsu.isChecked) R.drawable.bg_option_on else R.drawable.bg_option_off
        )
        optRootOnly.setBackgroundResource(
            if (rbRootOnly.isChecked) R.drawable.bg_option_on else R.drawable.bg_option_off
        )
    }

    // ---------------- 外观 ----------------

    /**
     * 填色点与深浅按钮。
     *
     * 色点的颜色不是写死的, 而是从每个色板 style 里读 ksuPrimary ([Theme.primaryOf]) ——
     * 这样色点与真实主题永远一致, 改 themes.xml 不用同步改 Kotlin。
     */
    private fun buildThemeCard() {
        for ((i, seed) in Theme.SEEDS.withIndex()) {
            val item = id<View>(dotItemIds[i])
            val dot = id<View>(dotColorIds[i])
            dot.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Theme.primaryOf(activity, seed.id))
            }
            item.setOnClickListener { onSeedSelected(seed.id) }
        }

        for ((i, m) in Theme.modes().withIndex()) {
            id<TextView>(modeIds[i]).setOnClickListener { onModeSelected(m.first) }
        }

        if (!Theme.canForceNightMode()) {
            id<TextView>(R.id.tvThemeNote).text = activity.getString(R.string.theme_note_old_android)
        }

        syncTheme()
    }

    private fun syncTheme() {
        val seed = Prefs.themeSeed(activity)
        for ((i, s) in Theme.SEEDS.withIndex()) {
            id<View>(dotItemIds[i]).setBackgroundResource(
                if (s.id == seed) R.drawable.bg_dot_ring else 0
            )
        }
        id<TextView>(R.id.tvSeedName).text = activity.getString(
            Theme.SEEDS.first { it.id == seed }.labelRes
        )

        val mode = Prefs.themeMode(activity)
        val modes = Theme.modes()
        for (i in modeIds.indices) {
            id<TextView>(modeIds[i]).setBackgroundResource(
                if (modes[i].first == mode) R.drawable.bg_option_on else R.drawable.bg_option_off
            )
        }
    }

    /**
     * 换色板: 落盘后重建整个容器 —— 主题只能在 setTheme 之前应用, 重建是最省事的生效方式。
     * 容器会记住当前页序号, 所以重建后仍停在设置页。
     */
    private fun onSeedSelected(seedId: String) {
        if (Prefs.themeSeed(activity) == seedId) return
        Prefs.setThemeSeed(activity, seedId)
        activity.recreate()
    }

    /**
     * 换深浅模式。API 31+ 由系统按新配置重建界面 (不用手动 recreate);
     * 更低版本没有 force 能力, 只能跟随系统 —— 给个提示避免以为没生效。
     */
    private fun onModeSelected(mode: String) {
        if (Prefs.themeMode(activity) == mode) return
        Prefs.setThemeMode(activity, mode)
        if (!Theme.applyNightMode(activity, mode)) {
            Toast.makeText(activity, activity.getString(R.string.theme_mode_unsupported), Toast.LENGTH_SHORT)
                .show()
        }
        syncTheme()
    }
}
