package com.smswebgateway.android.net

import java.net.URI

sealed interface ServerUrlResult {
    /** [baseUrl] 不含末尾 `/`，也不含 `/api/v1`。 */
    data class Ok(val baseUrl: String) : ServerUrlResult
    data class Error(val message: String) : ServerUrlResult
}

/**
 * 服务器地址解析与策略：
 * - 默认只允许 https；
 * - 仅当 [allowCleartextLocal]（debug 构建）为 true 时，允许 http 指向 localhost / 回环 / 私有网段 / *.local；
 * - 不允许 URL 内嵌用户名密码。
 */
object ServerUrl {
    fun parse(input: String, allowCleartextLocal: Boolean): ServerUrlResult {
        var text = input.trim()
        if (text.isEmpty()) return ServerUrlResult.Error("请输入服务器地址")
        if (!text.contains("://")) {
            val host = hostOf("http://$text")
            text = if (allowCleartextLocal && host != null && isLocalHost(host)) "http://$text" else "https://$text"
        }
        val uri = try {
            URI(text)
        } catch (e: Exception) {
            return ServerUrlResult.Error("服务器地址格式不正确")
        }
        val scheme = uri.scheme?.lowercase()
        val host = uri.host
        if (host.isNullOrEmpty()) return ServerUrlResult.Error("服务器地址缺少主机名")
        if (uri.userInfo != null) return ServerUrlResult.Error("服务器地址不允许包含用户名或密码")
        when (scheme) {
            "https" -> Unit
            "http" -> if (!(allowCleartextLocal && isLocalHost(host))) {
                return ServerUrlResult.Error(
                    if (allowCleartextLocal) "HTTP 仅允许用于本机 / 局域网地址，请改用 HTTPS"
                    else "此版本只允许 HTTPS 连接服务器",
                )
            }
            else -> return ServerUrlResult.Error("只支持 http(s) 地址")
        }
        val port = if (uri.port > 0) ":${uri.port}" else ""
        var path = (uri.rawPath ?: "").trimEnd('/')
        if (path.endsWith("/api/v1")) path = path.removeSuffix("/api/v1")
        return ServerUrlResult.Ok("$scheme://${host.lowercase()}$port$path")
    }

    private fun hostOf(url: String): String? = try {
        URI(url).host
    } catch (e: Exception) {
        null
    }

    fun isLocalHost(hostRaw: String): Boolean {
        val host = hostRaw.trim('[', ']').lowercase()
        if (host == "localhost" || host.endsWith(".localhost") || host.endsWith(".local")) return true
        if (host == "::1") return true
        val parts = host.split('.')
        if (parts.size != 4) return false
        val n = parts.map { it.toIntOrNull() ?: return false }
        if (n.any { it !in 0..255 }) return false
        return n[0] == 127 ||
            n[0] == 10 ||
            (n[0] == 192 && n[1] == 168) ||
            (n[0] == 172 && n[1] in 16..31) ||
            (n[0] == 169 && n[1] == 254)
    }
}
