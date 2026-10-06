package com.neoroot.ksuonetap.core

/**
 * 读系统 property（无需 root / Shizuku）。
 *
 * 为什么需要它：**vivo 的 `Build.DISPLAY` 不是软件版本号**。
 * `ro.build.display.id`（`Build.DISPLAY` 的来源）在两代 OriginOS 上语义不同：
 *
 *   - OriginOS 5（Android 15）：`PD2338_A_15.1.14.7.W10.V000L1` —— 恰好就是软件版本号
 *   - OriginOS 4（Android 14）：`UP1A.231005.007 release-keys` —— AOSP build id，**不含版本号**
 *
 * 软件版本号另有其 prop（`ro.vivo.default.version` / `ro.build.version.bbk` /
 * `ro.vivo.product.version` …，见 `DeviceGate.SOFTWARE_VERSION_PROPS`），
 * 而 `Build` 的公开字段里没有对应项。那些 prop 只能通过 property 接口读：
 * `/system/build.prop` 是 `0600 root`（App 读不到）。
 *
 * `android.os.SystemProperties` 是 hidden API，这里用反射调用：被 hidden API 策略挡住时
 * 返回空串，由调用方回退到 shell（Shizuku）的 `getprop` 或 `Build.DISPLAY`。
 */
internal object SysProps {

    private val getter: java.lang.reflect.Method? = runCatching {
        Class.forName("android.os.SystemProperties").getMethod("get", String::class.java)
    }.getOrNull()

    /** 读一个 property；读不到（不存在 / 被策略挡住）返回空串。 */
    fun get(key: String): String =
        runCatching { getter?.invoke(null, key) as? String }
            .getOrNull()
            ?.trim()
            .orEmpty()
}
