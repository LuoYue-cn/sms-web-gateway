package com.smswebgateway.android.net

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class PairingPayload(val serverUrl: String, val pairingToken: String)

/** 解析二维码内容 `{"server_url":"…","pairing_token":"…"}`；格式不对返回 null。 */
object PairingPayloadParser {
    fun parse(text: String): PairingPayload? = try {
        val obj: JsonObject = Json.parseToJsonElement(text.trim()).jsonObject
        val url = obj["server_url"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val token = obj["pairing_token"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (url.isEmpty() || token.isEmpty()) null else PairingPayload(url, token)
    } catch (e: Exception) {
        null
    }
}
