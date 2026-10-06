package com.neoroot.ksuonetap

import android.app.Application
import com.neoroot.ksuonetap.core.LogStore

/**
 * 应用入口 —— 只为了把日志落盘初始化好。
 *
 * 放在 Application 而不是某个 Activity：日志要在**任何界面进来之前**就能落盘，
 * 否则从内置终端直接启动时（PagerActivity 没跑过）那一轮日志就只在内存里。
 * 初始化本身幂等，多调无妨。
 */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        LogStore.init(this)
    }
}
