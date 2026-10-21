package com.smswebgateway.android.sms

/** 手机号规范化与校验，规则与 docs/API.md「手机号」一节一致（纯逻辑，可单测）。 */
object PhoneNumber {
    private val SEND_REGEX = Regex("^\\+?[0-9]{3,20}$")
    const val MAX_RECEIVE_LENGTH = 32

    private fun isSeparator(c: Char) =
        c == ' ' || c == '-' || c == '(' || c == ')' || c == '.' || c == '\t' || c == '\u00A0'

    /** 去掉空格、`-`、`()`、`.`，保留开头 `+`。 */
    fun normalize(raw: String): String = raw.filterNot(::isSeparator)

    /** 发送用：必须通过 `^\+?[0-9]{3,20}$`，否则返回 null（含 `,` `;` 的多号码也会返回 null）。 */
    fun normalizeForSend(raw: String): String? {
        val n = normalize(raw.trim())
        return if (SEND_REGEX.matches(n)) n else null
    }

    fun isValidForSend(raw: String): Boolean = normalizeForSend(raw) != null

    /**
     * 接收/同步用：数字号码规范化；字母数字发件人（如 ICBC、10086）原样保留；最长 32；空则返回 "unknown"。
     */
    fun normalizeForReceive(raw: String?): String {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return "unknown"
        val stripped = normalize(trimmed)
        val result = if (SEND_REGEX.matches(stripped)) stripped else trimmed
        return result.take(MAX_RECEIVE_LENGTH)
    }
}
