package com.neoroot.ksuonetap.core

import android.os.Build

/**
 * 设备与系统版本判定。
 *
 * 为什么必须判定: 本工程的漏洞利用与内核补丁都依赖具体机型的内存布局与符号偏移,
 * 换机型/换系统版本就会命中失败甚至 panic。适用边界 (设备实测结论):
 *
 *   - 机型       : vivo iQOO Neo9 (设备代号 PD2338, 市场型号 V2338A)
 *   - 已适配版本 : 分三档, 每一条都对应 assets/ 下同名的产物目录
 *                  ① [ADAPTED_BUILDS]        完整适配 (exploit + 3 个 .ko)
 *                  ② [LPE_ONLY_BUILDS]       仅 LPE / 临时 root su (只有 exploit)
 *                  ③ [GHOSTLOCK_KSU_BUILDS]  GhostLock 临时 root + KSU 部署 (需宽容模式)
 *   - 漏洞       : 两条, 按系统版本分派:
 *                  ① CVE-2025-21479 (Adreno GPU SDS) —— 理论适用 OriginOS 4 ~ 5
 *                    (exploit_vivo_neo9 执行流, 需 Shizuku 部署)
 *                  ② CVE-2026-43499 (futex PI requeue, GhostLock) —— 适用 OriginOS 6
 *                    (app 进程树直接执行, 无需 Shizuku)
 *
 * **版本号怎么取（2026-09-28 修正）**：不能用 `Build.DISPLAY`。
 * `ro.build.display.id` 在 OriginOS 5 上给的就是软件版本号，但在 OriginOS 4 上退化成
 * AOSP build id（`UP1A.231005.007 release-keys`）—— 于是 OriginOS 4 的机器永远被判成
 * "未适配"。现在改成：按 [SOFTWARE_VERSION_PROPS] 依次取 prop → 归一化成**版本核**
 * （`14.0.17.2.W10`）→ 与 assets 目录名的核比对。归一化同时抹平了 `PD2338` vs `PD2338C`
 * 与 `.V000L1` 这类写法差异。
 *
 * 判定在未授权状态下也能给出结论（property 可读，命中 hidden API 限制时会退化为
 * `Build.DISPLAY` 比对，并在界面上说明）。
 */
object DeviceGate {
    /** 设备代号 (Build.DEVICE)。 */
    const val TARGET_DEVICE = "PD2338"

    /** 市场型号 (Build.MODEL)。 */
    const val TARGET_MODEL = "V2338A"

    /**
     * 已做过**完整**偏移适配的系统版本（= `assets/` 下的目录名，每版 4 件绑定产物）。
     *
     * 新增一版的完整做法（见 `apk/ksuonetap/assets/<版本>/SYSTEM.txt`）：
     *   1. 在 `apk/ksuonetap/assets/` 下新建以该版本**软件版本号**命名的目录
     *   2. 放入绑定产物（完整版 = exploit + 3 个 .ko），写 `tier=full`
     *   3. 在下面这个清单里加一条
     * 通用产物（`u0` / `su_ksu` / `ksud` / manager）不随系统版本走，不用重复放。
     */
    val ADAPTED_BUILDS = listOf(
        "PD2338_A_15.1.14.7.W10.V000L1",   // OriginOS 5 / 内核 5.15.178（2026-09-27）
        "PD2338_A_14.0.17.2.W10.V000L1",   // OriginOS 4 / 内核 5.15.137（2026-09-28）
        "PD2338_A_14.0.17.6.W10.V000L1",   // OriginOS 4 / 内核 5.15.137-g7cb3e06b062c（2026-09-28 离线适配，真机验证待做）
    )

    /**
     * **只适配了 LPE / 临时 root（su）**的系统版本。
     *
     * 这些版本的 `assets/<版本>/` 里只有 exploit（无内核模块）—— 模块与内核
     * 二进制绑定（vermagic + `struct module` 布局 + `KSU_VERSION`），换内核版本必须重编。
     * 所以这些版本上：
     *   - ✅ 可用：一键提取临时 root + su（`su -c 'id'` → uid=0）
     *   - ❌ 不可用：「激活 KernelSU」及之后的阶段（会被明确拒绝，不会拿旧模块去加载）
     */
    val LPE_ONLY_BUILDS = listOf<String>()

    /**
     * **GhostLock 临时 root + KSU 部署支持**的版本（kernelsu-vanilla-197.ko / vrpatch.ko
     * 已按该内核重编适配; ko 已换原版 KernelSU v3.3.0, 2026-10-05 GhostLock App 真机验证）。
     *
     * 部署方式: 「一键 Root」(GhostLock, 无 Shizuku) → 「激活 KSU」—— 后者经
     * daemon 的 **seccomp 逃逸通道**加载模块（App 进程树的 seccomp 白名单没有
     * init_module, 见 GhostlockRunner.activateKsu 注释）。
     *
     * ⚠️ **需宽容模式**: 该内核上 KSU + SELinux enforcing 会断网（vivo netb 内核
     * 过滤器 enforce 特异性静默丢包, 含回环; 用户态无法解除）。部署脚本加载完成后
     * 自动 `setenforce 0`, 之后请保持宽容模式。
     */
    val GHOSTLOCK_KSU_BUILDS = listOf(
        "PD2338_A_16.2.13.2.W10.V000L1",   // OriginOS 6 / 内核 5.15.197（CVE-2026-43499）
    )

    /** [build] 是否为 GhostLock 链 + KSU 部署支持的版本。 */
    fun isGhostlockKsuBuild(build: String): Boolean =
        GHOSTLOCK_KSU_BUILDS.any { it.equals(build, true) }

    /**
     * **GhostLock (CVE-2026-43499) 执行流**的 LPE 版本。
     *
     * 与旧 LPE 版本（`exploit_vivo_neo9`，GPU 漏洞 spray + Shizuku 部署）的区别:
     *   - exploit **全程无特权**，二进制随 APK 的 `lib/arm64-v8a/` 打包，
     *     运行时从 `nativeLibraryDir` 直接执行 —— **不需要 Shizuku、不需要推文件**
     *     （同 GhostLock App 的做法）;
     *   - su 命令通道走 app 私有目录的 unix socket（exploit 支持 `SU_SOCK_PATH`
     *     环境变量），SELinux enforcing 下也可达;
     *   - 执行前置：**锁屏静置**（mcast route 是大核双线程竞态，负载会毁掉竞态）。
     */
    val GHOSTLOCK_BUILDS = listOf(
        "PD2338_A_16.2.13.2.W10.V000L1",   // CVE-2026-43499 (GhostLock), 仅临时 root, 无 Shizuku
    )

    /** [build] 是否走 GhostLock (CVE-2026-43499) 执行流。 */
    fun isGhostlockBuild(build: String): Boolean =
        GHOSTLOCK_BUILDS.any { it.equals(build, true) }

    /** 全部已适配的版本目录（完整 + 仅 LPE + GhostLock/KSU）。 */
    val ALL_ADAPTED_BUILDS: List<String>
        get() = ADAPTED_BUILDS + LPE_ONLY_BUILDS + GHOSTLOCK_KSU_BUILDS

    /** 随包默认适配的那一版（= `assets/` 里那批绑定产物的目录名之一）。 */
    const val TARGET_BUILD = "PD2338_A_15.1.14.7.W10.V000L1"

    /** [build] 是否只适配了 LPE / 临时 root。 */
    fun isLpeOnlyBuild(build: String): Boolean =
        !ADAPTED_BUILDS.any { it.equals(build, true) } &&
            LPE_ONLY_BUILDS.any { it.equals(build, true) }

    /**
     * 软件版本号的候选 property（按可靠性排序，取到第一个含"版本核"的即用）。
     *
     * 全都是 vivo 自己的版本类 prop（同一台机器上会重复出现同一个版本号，是 vivo 的多份镜像），
     * 任意一个存在就够；列这么多个是为了跨 OriginOS 版本/机型都能命中。
     */
    private val SOFTWARE_VERSION_PROPS = listOf(
        "ro.vivo.default.version",           // PD2338_A_14.0.17.2.W10.V000L1
        "ro.vivo.product.version",           // PD2338C_A_14.0.17.2.W10.V000L1
        "ro.build.version.bbk",              // 同上
        "ro.vivo.carton.version",            // 同上
        "ro.vivo.build.version",             // PD2338C_A_14.0.17.2.W10
        "ro.build.software.version",         // 同上
        "ro.vivo.dyn.software.version",      // PD2338_A_14.0.17.2.W10
        "persist.vivo.dyn.lastversion",      // 同上
        "ro.vivo.build.version.incremental", // 14.0.17.2.W10（只有核）
        "ro.build.display.id",               // OriginOS 5 上是版本号；OriginOS 4 上是 AOSP id
    )

    /** OriginOS 名称的 prop（`OriginOS 4`），仅用于展示。 */
    private const val OS_NAME_PROP = "ro.vivo.os.build.display.id"

    /** 版本核正则：`A_14.0.17.2.W10` / `14.0.17.2.W10` -> 取 `14.0.17.2.W10`。 */
    private val CORE_RE = Regex("([0-9]+(?:\\.[0-9]+){1,3})\\.W([0-9]+)", RegexOption.IGNORE_CASE)

    /**
     * 版本核：把同一固件的各种写法归一成 `x.y.z.Wnn`。
     *
     * 归一化是**必须**的：设备报 `PD2338C_A_14.0.17.2.W10`（带 C、无批次后缀），
     * assets 目录名是 `PD2338_A_14.0.17.2.W10.V000L1`（无 C、带后缀）——
     * 直接字符串比永远不会相等，而版本核 `14.0.17.2.W10` 唯一标识一版固件。
     * 反过来，没有 `.W<数字>` 的串（`UP1A.231005.007 release-keys`、`14.1`）核为空，
     * 不会误命中。
     */
    internal fun versionCore(s: String): String =
        CORE_RE.find(s.trim())
            ?.let { "${it.groupValues[1]}.W${it.groupValues[2]}" }
            .orEmpty()

    /** 漏洞可利用的 OriginOS 大版本区间 (含端点)。 */
    const val OS_MIN = 4
    const val OS_MAX = 6

    data class Info(
        val device: String,
        val model: String,
        /** `Build.DISPLAY`。**只做诊断用**——它不是软件版本号（见 [SOFTWARE_VERSION_PROPS]）。 */
        val build: String,
        /** 设备软件版本号（如 `PD2338_A_14.0.17.2.W10.V000L1`）；读不到为空串。 */
        val softwareVersion: String,
        /** 提供该版本号的 property 名（诊断用）。 */
        val versionProp: String,
        /** 版本核（如 `14.0.17.2.W10`）。 */
        val versionCore: String,
        /** 匹配到的 `assets/` 版本目录名；未适配为空串。 */
        val buildDir: String,
        val sdk: Int,
        /** OriginOS 大版本 (由 property 换算) 或空串 (property 读不到)。 */
        val osMajor: String,
        /** property 原文, 便于排查 (形如 "14.1")。 */
        val osRaw: String,
        /** OriginOS 名称（`OriginOS 4`），读不到为空串。 */
        val osName: String,
        /** 由 SDK 推断的 OriginOS 大版本 (兜底)。 */
        val osMajorFromSdk: Int,
        val isTargetDevice: Boolean,
        val isTargetBuild: Boolean,
        /** 本版本只适配了 LPE / 临时 root（模块那套还没做）。 */
        val isLpeOnly: Boolean,
        /** 版本号是靠 `Build.DISPLAY` 兜底得到的（property 一个都没读到）—— 界面要说明。 */
        val usedDisplayFallback: Boolean,
    ) {
        /** 完整支持: 目标机型 + 完整适配的版本（能用 KernelSU 那套）。 */
        val fullySupported: Boolean get() = isTargetDevice && isTargetBuild && !isLpeOnly

        /** 在漏洞可利用的 OriginOS 区间内 (不代表已适配)。 */
        val inVulnRange: Boolean
            get() = osMajorFromSdk in OS_MIN..OS_MAX

        /** 界面用的短结论。 */
        val summary: String
            get() = when {
                !isTargetDevice -> "非目标机型"
                isTargetBuild && isLpeOnly -> "已适配（仅临时 root）"
                isTargetBuild -> "已适配"
                inVulnRange -> "未适配（版本不同）"
                else -> "版本超出漏洞范围"
            }

        /** 界面上显示的版本号（软件版本号优先，读不到才退回到 Build.DISPLAY）。 */
        val versionDisplay: String
            get() = softwareVersion.ifEmpty { build }

        /** 实际用于显示的 OriginOS 大版本。 */
        val osDisplay: String
            get() = osMajor.ifEmpty { osMajorFromSdk.toString() }

        /** 详细说明 (用于提示与关于页)。 */
        val detail: String
            get() = buildString {
                append("机型 $device / $model\n")
                append("软件版本 ${versionDisplay}\n")
                // osName 形如 "OriginOS 4"，自带前缀；读不到才自己拼
                append(if (osName.isNotEmpty()) osName else "OriginOS $osDisplay")
                if (osRaw.isNotEmpty()) append(" (ro.vivo.os.version=$osRaw)")
                append("\nAndroid API $sdk\n")
                append("判定: $summary")
                if (usedDisplayFallback && isTargetBuild) {
                    append("\n\n(注：没读到 vivo 的版本 property，是按 Build.DISPLAY 兜底判定的)")
                }
                if (isTargetBuild && isLpeOnly) {
                    if (isGhostlockBuild(buildDir)) {
                        append("\n\n本版本走 **GhostLock (CVE-2026-43499) 执行流**：")
                        append("exploit 全程无特权、随 APK 打包，**不需要 Shizuku**，一键直接提权")
                        append("（临时 root，per-boot：重启后重打一次即可）。")
                        append("\n\n⚠️ 发射前请**锁屏静置**：竞态窗口对后台负载极其敏感，")
                        append("负载下命中率会从 ~100% 跌到 0。")
                    } else {
                        append("\n\n本版本只适配了 **LPE + 临时 root su**：")
                        append("可以提取临时 root 并用内置 su（`su -c 'id'` → uid=0），")
                        append("但 KernelSU 那套内核模块还没按本版本重编，「激活 KSU」会被拒绝。")
                    }
                }
                if (!isTargetBuild) {
                    append("\n\n本工程做过偏移适配的版本：")
                    append(ALL_ADAPTED_BUILDS.joinToString())
                    append("。")
                    append("\n本机识别到：")
                    append(if (softwareVersion.isNotEmpty()) softwareVersion else "（未读到版本号）")
                    if (versionCore.isNotEmpty()) append("（版本核 $versionCore）")
                    append("。")
                    if (inVulnRange) {
                        append("\n当前版本在漏洞可利用区间 (OriginOS $OS_MIN~$OS_MAX) 内，")
                        append("但未做适配，命中失败或内核 panic 的概率不可忽略。")
                    } else {
                        append("\n当前版本不在漏洞可利用区间 (OriginOS $OS_MIN~$OS_MAX)，预期无法命中。")
                    }
                }
            }
    }

    // ---- 版本号探测（带缓存：一次进程内不会变）----

    @Volatile
    private var cached: Info? = null

    /**
     * 解析本机对应的 `assets/` 版本目录名（部署前取件用）。未适配返回空串。
     *
     * 这里是**唯一**决定"用哪一版绑定产物"的地方 —— 取到空串时调用方必须拒绝继续，
     * 绝不能退回 `Build.DISPLAY` 去拼路径（拼不出来会被当成"缺文件"，而拼错了是内核 panic）。
     */
    fun resolveBuildDir(): String = probe(shellOk = true).buildDir

    /** 设备软件版本号（读不到为空串）。 */
    fun softwareVersion(): String = probe(shellOk = true).versionDisplay

    /** 丢弃缓存（授权状态变化后重新探测）。 */
    fun invalidate() {
        cached = null
    }

    /**
     * 探测设备信息。`shellOk` 为 true 时用 Shizuku 的 shell 补取 property
     * （反射被 hidden API 限制挡住时的回退）；为 false 时只用进程内可读的途径。
     * 机型判定不依赖权限。
     */
    fun probe(shellOk: Boolean): Info {
        // 缓存只在"够用"时复用：先用无权限路径探过（shellOk=false）再想用 shell 补，
        // 要允许重新探一次，否则会一直卡在退化结果上。
        cached?.let { if (shellOk || !it.usedDisplayFallback) return it }

        val device = Build.DEVICE ?: "?"
        val model = Build.MODEL ?: "?"
        val display = Build.DISPLAY ?: "?"
        val sdk = Build.VERSION.SDK_INT

        val props = readVersionCandidates(shellOk)
        val hit = props.firstOrNull { versionCore(it.second).isNotEmpty() }
        val software = hit?.second.orEmpty()
        val propName = hit?.first.orEmpty()
        val core = versionCore(software)
        // 退回 Build.DISPLAY：OriginOS 5 上它就是版本号（OriginOS 4 上不是，见类注释）
        val fallbackCore = if (core.isEmpty()) versionCore(display) else ""
        val effectiveCore = core.ifEmpty { fallbackCore }
        val dir = ALL_ADAPTED_BUILDS.firstOrNull { versionCore(it) == effectiveCore } ?: ""

        var osRaw = ""
        var osMajor = ""
        if (shellOk) {
            val out = runCatching {
                Shell.run("getprop ro.vivo.os.version 2>/dev/null", 3_000).trim().substringBefore('\n')
            }.getOrDefault("")
            // Shell 通道本身出问题时（Shizuku 授权丢失 / binder 异常），run() 会把异常文本
            // 当作输出返回 —— 只接受"看着像版本号"的短串，免得把异常信息显示到界面上。
            if (out.isNotEmpty() && out.length <= 16 && out.all { it.isDigit() || it == '.' }) {
                osRaw = out
                osMajor = parseOsMajor(out)
            }
        }
        // OriginOS 名称优先用 prop（值形如 "OriginOS 4"，自带前缀）
        val osName = SysProps.get(OS_NAME_PROP)

        val info = Info(
            device = device,
            model = model,
            build = display,
            softwareVersion = software,
            versionProp = propName,
            versionCore = effectiveCore,
            buildDir = dir,
            sdk = sdk,
            osMajor = osMajor,
            osRaw = osRaw,
            osName = osName,
            osMajorFromSdk = sdkToOsMajor(sdk),
            isTargetDevice = device.equals(TARGET_DEVICE, true) || model.equals(TARGET_MODEL, true),
            isTargetBuild = dir.isNotEmpty(),
            isLpeOnly = isLpeOnlyBuild(dir),
            usedDisplayFallback = core.isEmpty() && fallbackCore.isNotEmpty(),
        )
        cached = info
        return info
    }

    /** 依次读候选 prop，返回"prop 名 -> 值"（去掉空值与无版本核的值）。 */
    private fun readVersionCandidates(shellOk: Boolean): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        val missing = ArrayList<String>()
        for (k in SOFTWARE_VERSION_PROPS) {
            val v = SysProps.get(k)
            if (v.isNotEmpty()) out += k to v else missing += k
        }
        if (out.any { versionCore(it.second).isNotEmpty() } || missing.isEmpty()) return out

        // 进程内一个都没读到（hidden API 被挡）-> 用 shell 一次性补取
        if (shellOk) {
            val script = missing.joinToString("; ") { "echo \"==$it\"; getprop $it" }
            val txt = runCatching { Shell.run(script, 8_000) }.getOrDefault("")
            var key: String? = null
            for (line in txt.lines()) {
                val t = line.trim()
                when {
                    t.startsWith("==") -> key = t.substring(2).trim()
                    t.isEmpty() || t.startsWith("[rc=") -> {}
                    key != null -> {
                        out += key to t
                        key = null
                    }
                }
            }
        }
        return out
    }

    /**
     * vivo 的 `ro.vivo.os.version` 与 Android 版本号对齐 (OriginOS 4 -> "14.0",
     * OriginOS 5 -> "15.0", 设备实测), 所以 >= 10 时要减 10 才是 OriginOS 大版本;
     * 少数机型直接给 "5.0" 这类 OriginOS 版本号, 此时原值即大版本。
     */
    internal fun parseOsMajor(raw: String): String {
        val n = Regex("(\\d+)").find(raw)?.groupValues?.get(1)?.toIntOrNull() ?: return ""
        val major = if (n >= 10) n - 10 else n
        return major.toString()
    }

    /** Android 15 -> OriginOS 5, Android 14 -> OriginOS 4, Android 16 -> OriginOS 6 (vivo 的对应关系)。 */
    private fun sdkToOsMajor(sdk: Int): Int = when {
        sdk >= 36 -> 6
        sdk == 35 -> 5
        sdk == 34 -> 4
        else -> 0
    }
}
