package com.neoroot.ksuonetap.core

import android.util.Base64

/**
 * base64 编码 (NO_WRAP)。
 *
 * 两处用到:
 *   - 终端: 命令先写成脚本落盘再执行, 传给 `su -c` / `su_ksu -c` 的只有一个 base64 串
 *     —— 直接塞原文会被引号与换行破坏
 *   - rootd 文件队列的 `B64:` 协议 (部署 [4] 与「激活 KSU」经它投递命令)
 */
object B64 {
    fun encode(s: String): String =
        Base64.encodeToString(s.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
}
