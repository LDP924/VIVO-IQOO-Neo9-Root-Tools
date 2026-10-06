package com.neoroot.ksuonetap.core

import android.content.Context

/**
 * 设置的持久化 (SharedPreferences, 不引入 AndroidX)。
 *
 * 四项: 一键提取模式、终端执行通道、主题色板、深浅模式 —— 都是单选, 改完立即生效。
 * 通道标识用 [Channel] 的常量; 色板/深浅标识用 [Theme] 的常量。
 */
object Prefs {
    private const val FILE = "ksuonetap_settings"
    private const val K_MODE = "extract_mode"
    private const val K_CHANNEL = "term_channel"
    private const val K_THEME_SEED = "theme_seed"
    private const val K_THEME_MODE = "theme_mode"

    /** 一键提取: 只取临时 root (内置 su, uid=0), 不部署 KernelSU。 */
    const val MODE_ROOT_ONLY = "root_only"

    /** 一键提取: 取临时 root 并部署 KernelSU (完整流程)。 */
    const val MODE_ROOT_KSU = "root_ksu"

    private fun sp(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun mode(ctx: Context): String =
        sp(ctx).getString(K_MODE, MODE_ROOT_KSU) ?: MODE_ROOT_KSU

    fun setMode(ctx: Context, m: String) {
        sp(ctx).edit().putString(K_MODE, m).apply()
    }

    fun isRootOnly(ctx: Context): Boolean = mode(ctx) == MODE_ROOT_ONLY

    fun channel(ctx: Context): String =
        sp(ctx).getString(K_CHANNEL, Channel.AUTO) ?: Channel.AUTO

    fun setChannel(ctx: Context, c: String) {
        sp(ctx).edit().putString(K_CHANNEL, c).apply()
    }

    fun themeSeed(ctx: Context): String =
        sp(ctx).getString(K_THEME_SEED, Theme.SEED_PURPLE) ?: Theme.SEED_PURPLE

    fun setThemeSeed(ctx: Context, seed: String) {
        sp(ctx).edit().putString(K_THEME_SEED, seed).apply()
    }

    fun themeMode(ctx: Context): String =
        sp(ctx).getString(K_THEME_MODE, Theme.MODE_SYSTEM) ?: Theme.MODE_SYSTEM

    fun setThemeMode(ctx: Context, mode: String) {
        sp(ctx).edit().putString(K_THEME_MODE, mode).apply()
    }
}
