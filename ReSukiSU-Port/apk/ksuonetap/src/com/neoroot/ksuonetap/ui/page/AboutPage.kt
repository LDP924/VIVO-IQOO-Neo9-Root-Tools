package com.neoroot.ksuonetap.ui.page

import android.app.Activity
import android.view.View
import android.widget.TextView
import com.neoroot.ksuonetap.R
import com.neoroot.ksuonetap.core.DeviceGate

/**
 * 关于页 (dock 第 4 项): 应用信息 / 适用范围 / 开源许可 / 声明。
 *
 * 只放静态事实, 不探设备 —— 实时判定留在设置页的「当前设备」。
 * 版本号直接读 packageManager, 不写死在 strings 里 (免得发版忘了改)。
 */
class AboutPage(activity: Activity, root: View) : Page(activity, root) {

    init {
        val info = runCatching {
            activity.packageManager.getPackageInfo(activity.packageName, 0)
        }.getOrNull()
        val ver = info?.versionName ?: "?"

        id<TextView>(R.id.tvAboutVersion).text = ver
        id<TextView>(R.id.tvAboutVersionRow).text =
            if (info == null) ver else "$ver (code ${info.versionCode})"

        // 系统版本清单从 DeviceGate 现取, 不在 strings 里再写一份
        // (原先写死成只有 15.1.14.7, 加了 14.0.17.2 之后就成了假信息)。
        id<TextView>(R.id.tvAboutSupportSystem).text =
            DeviceGate.ADAPTED_BUILDS.joinToString("\n")
        id<TextView>(R.id.tvAboutTempRoot).text =
            DeviceGate.ALL_ADAPTED_BUILDS.joinToString("\n")
    }
}
