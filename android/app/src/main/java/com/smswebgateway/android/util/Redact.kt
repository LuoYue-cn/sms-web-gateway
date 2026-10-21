package com.smswebgateway.android.util

/** 日志脱敏：日志里不出现完整短信内容、完整手机号或 token。 */
object Redact {
    /** 13800138000 → 138****8000；字母发件人只保留首尾各 1 个字符。 */
    fun phone(p: String?): String {
        if (p.isNullOrEmpty()) return "-"
        return when {
            p.length <= 4 -> "*".repeat(p.length)
            p.length <= 7 -> p.take(1) + "*".repeat(p.length - 2) + p.takeLast(1)
            else -> p.take(3) + "****" + p.takeLast(4)
        }
    }

    /** 仅显示内容长度，不显示内容。 */
    fun content(c: String?): String = "<${c?.length ?: 0}字>"

    /** token 只显示末 4 位。 */
    fun token(t: String?): String = if (t.isNullOrEmpty()) "-" else "****" + t.takeLast(4)

    private val BEARER = Regex("(?i)(bearer\\s+)[A-Za-z0-9._~+/\\-]{6,}=*")
    private val KEY_VALUE =
        Regex("(?i)(token|authorization|pairing_token|device_token)(\"?\\s*[=:]\\s*\"?)(?!\\*{4})(?!bearer\\b)[A-Za-z0-9._~+/\\-]{6,}=*")

    /** 对任意即将写入日志的自由文本做兜底脱敏（先处理 Bearer，再处理 key=value / key: value）。 */
    fun scrub(text: String): String =
        KEY_VALUE.replace(BEARER.replace(text) { "${it.groupValues[1]}****" }) {
            "${it.groupValues[1]}${it.groupValues[2]}****"
        }
}
