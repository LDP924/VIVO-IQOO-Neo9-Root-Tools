package com.neoroot.ksuonetap.core

import android.app.Activity
import android.app.UiModeManager
import android.content.Context
import android.os.Build
import com.neoroot.ksuonetap.R

/**
 * 主题: 色板 (accent) + 深浅模式。
 *
 * 色板 = 一组 ksu* 色 attr 的取值, 每个色板一个 style (values/themes.xml 给浅色值,
 * values-night/themes.xml 给深色值) —— 换色板就是换 style, 布局与 drawable 不用动。
 * 深浅模式用 UiModeManager.setApplicationNightMode (API 31+), 系统会自己重建界面。
 *
 * 用法: 每个 Activity 在 super.onCreate **之前** 调 [apply]。
 */
object Theme {

    /** 色板标识 (存进 Prefs)。 */
    const val SEED_PURPLE = "purple"
    const val SEED_BLUE = "blue"
    const val SEED_TEAL = "teal"
    const val SEED_GREEN = "green"
    const val SEED_ORANGE = "orange"
    const val SEED_RED = "red"

    /** 深浅模式。 */
    const val MODE_SYSTEM = "system"
    const val MODE_LIGHT = "light"
    const val MODE_DARK = "dark"

    /** 一个色板: 标识 / 名称资源 / 主题 style。 */
    class Seed(val id: String, val labelRes: Int, val styleRes: Int)

    val SEEDS = listOf(
        Seed(SEED_PURPLE, R.string.theme_seed_purple, R.style.Theme_KSUOneTap),
        Seed(SEED_BLUE, R.string.theme_seed_blue, R.style.Theme_KSUOneTap_Blue),
        Seed(SEED_TEAL, R.string.theme_seed_teal, R.style.Theme_KSUOneTap_Teal),
        Seed(SEED_GREEN, R.string.theme_seed_green, R.style.Theme_KSUOneTap_Green),
        Seed(SEED_ORANGE, R.string.theme_seed_orange, R.style.Theme_KSUOneTap_Orange),
        Seed(SEED_RED, R.string.theme_seed_red, R.style.Theme_KSUOneTap_Red)
    )

    private val MODES = listOf(
        MODE_SYSTEM to R.string.theme_mode_system,
        MODE_LIGHT to R.string.theme_mode_light,
        MODE_DARK to R.string.theme_mode_dark
    )

    private fun seedOf(id: String): Seed = SEEDS.firstOrNull { it.id == id } ?: SEEDS[0]

    /** 当前设置对应的主题 style。 */
    fun styleRes(ctx: Context): Int = seedOf(Prefs.themeSeed(ctx)).styleRes

    /** 在 super.onCreate 之前调用: 应用色板。 */
    fun apply(activity: Activity) {
        activity.setTheme(styleRes(activity))
    }

    /**
     * 色板预览色 —— 直接从 style 里读 ksuPrimary, 不在这里重复维护色值,
     * 因此色点与真实主题永远一致 (按当前深浅配置解析)。
     */
    fun primaryOf(ctx: Context, seedId: String): Int {
        val ta = ctx.obtainStyledAttributes(seedOf(seedId).styleRes, intArrayOf(R.attr.ksuPrimary))
        val c = ta.getColor(0, 0xFF6750A4.toInt())
        ta.recycle()
        return c
    }

    /** 深浅模式列表: 标识 + 名称资源 (顺序即界面顺序)。 */
    fun modes(): List<Pair<String, Int>> = MODES

    fun modeLabelRes(mode: String): Int =
        MODES.firstOrNull { it.first == mode }?.second ?: R.string.theme_mode_system

    /** 强制深浅模式要 API 31 的 UiModeManager.setApplicationNightMode。 */
    fun canForceNightMode(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    /**
     * 应用深浅模式。返回 false 表示当前系统不支持 (API < 31), 只跟随系统。
     * 强制模式由系统重建 Activity, 不需要手动 recreate。
     */
    @Suppress("NewApi")
    fun applyNightMode(ctx: Context, mode: String): Boolean {
        if (!canForceNightMode()) return false
        val m = ctx.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager ?: return false
        m.setApplicationNightMode(
            when (mode) {
                MODE_LIGHT -> UiModeManager.MODE_NIGHT_NO
                MODE_DARK -> UiModeManager.MODE_NIGHT_YES
                else -> UiModeManager.MODE_NIGHT_AUTO
            }
        )
        return true
    }
}
