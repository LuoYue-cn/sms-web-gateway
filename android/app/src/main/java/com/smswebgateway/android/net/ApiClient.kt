package com.smswebgateway.android.net

import com.smswebgateway.android.BuildConfig
import com.smswebgateway.android.data.AppLog
import com.smswebgateway.android.data.Prefs
import com.smswebgateway.android.data.SecureTokenStore
import com.smswebgateway.android.service.Unbind
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

sealed interface ApiResult<out T> {
    data class Ok<T>(val data: T) : ApiResult<T>

    /** 服务端返回了 `ok:false` 或非 2xx。 */
    data class Err(
        val httpStatus: Int,
        val code: String,
        val message: String,
        val details: JsonObject? = null,
    ) : ApiResult<Nothing> {
        val isRevoked get() = httpStatus == 401 && code == "device_revoked"
        val isAuth get() = httpStatus == 401
        fun detailString(key: String): String? = details?.get(key)?.let { (it as? JsonPrimitive)?.contentOrNull }
    }

    /** 网络层失败（DNS / 连接 / 超时 / TLS 校验失败等）。 */
    data class NetworkError(val message: String, val cause: Throwable? = null) : ApiResult<Nothing>
}

inline fun <T> ApiResult<T>.getOrNull(): T? = (this as? ApiResult.Ok<T>)?.data

fun ApiResult<*>.describe(): String = when (this) {
    is ApiResult.Ok -> "ok"
    is ApiResult.Err -> "$code ($httpStatus): $message"
    is ApiResult.NetworkError -> "网络错误: $message"
}

/**
 * 服务端 REST 客户端（docs/API.md）。
 * - HTTPS 使用系统默认的证书校验，不安装任何自定义 TrustManager / HostnameVerifier；
 * - 普通请求 connect/read/write 超时分别为 10/20/20 秒，长轮询读超时 = wait + 15 秒；
 * - 401 device_revoked 会触发 [Unbind.onRevoked]（清凭据、清本地数据、回到未绑定页）。
 */
class ApiClient(private val prefs: Prefs, private val tokens: SecureTokenStore) {
    private val json = ApiJson
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val pollHttp: OkHttpClient = http.newBuilder()
        .readTimeout(MAX_POLL_WAIT_S + 20L, TimeUnit.SECONDS)
        .callTimeout(MAX_POLL_WAIT_S + 35L, TimeUnit.SECONDS)
        .build()

    private fun baseUrl(): String? = prefs.serverUrl

    private fun url(path: String, query: Map<String, String> = emptyMap(), base: String? = baseUrl()): okhttp3.HttpUrl? {
        val b = base ?: return null
        val builder = "$b/api/v1$path".toHttpUrlOrNull()?.newBuilder() ?: return null
        query.forEach { (k, v) -> builder.addQueryParameter(k, v) }
        return builder.build()
    }

    private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                if (cont.isActive) cont.resume(response) else response.close()
            }
        })
    }

    private suspend fun <T> execute(
        client: OkHttpClient,
        request: Request,
        serializer: KSerializer<T>?,
        handleRevoked: Boolean = true,
    ): ApiResult<T> {
        val response = try {
            client.newCall(request).await()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: javax.net.ssl.SSLException) {
            // 证书错误一律失败，不重试、不忽略
            return ApiResult.NetworkError("TLS/证书校验失败：${e.message.orEmpty().take(100)}", e)
        } catch (e: IOException) {
            return ApiResult.NetworkError(e.message?.take(120) ?: e.javaClass.simpleName, e)
        } catch (e: Exception) {
            return ApiResult.NetworkError(e.message?.take(120) ?: e.javaClass.simpleName, e)
        }
        response.use { r ->
            val text = try {
                r.body?.string().orEmpty()
            } catch (e: IOException) {
                return ApiResult.NetworkError("读取响应失败", e)
            }
            val root: JsonObject? = try {
                json.parseToJsonElement(text).jsonObject
            } catch (e: Exception) {
                null
            }
            val okFlag = (root?.get("ok") as? JsonPrimitive)?.booleanOrNull
            if (r.isSuccessful && okFlag != false) {
                if (serializer == null) {
                    @Suppress("UNCHECKED_CAST")
                    return ApiResult.Ok(Unit as T)
                }
                val dataEl: JsonElement = root?.get("data") ?: JsonObject(emptyMap())
                return try {
                    ApiResult.Ok(json.decodeFromJsonElement(serializer, dataEl))
                } catch (e: Exception) {
                    ApiResult.Err(r.code, "bad_response", "响应格式不符合预期")
                }
            }
            val err = (root?.get("error") as? JsonObject)
            val code = (err?.get("code") as? JsonPrimitive)?.contentOrNull ?: "http_${r.code}"
            val message = (err?.get("message") as? JsonPrimitive)?.contentOrNull ?: r.message.ifEmpty { "请求失败" }
            val details = err?.get("details") as? JsonObject
            val e = ApiResult.Err(r.code, code, message.take(200), details)
            if (handleRevoked && e.isRevoked) {
                AppLog.w("Api", "设备令牌已被吊销，清除本地凭据")
                Unbind.onRevoked()
            }
            return e
        }
    }

    private fun builder(url: okhttp3.HttpUrl, auth: Boolean): Request.Builder {
        val b = Request.Builder().url(url).header("Accept", "application/json")
            .header("User-Agent", "SmsGate-Android/${BuildConfig.VERSION_NAME}")
        if (auth) {
            val t = tokens.get()
            if (t != null) b.header("Authorization", "Bearer $t")
        }
        return b
    }

    private suspend fun <T> get(path: String, ser: KSerializer<T>?, query: Map<String, String> = emptyMap(), poll: Boolean = false): ApiResult<T> {
        val u = url(path, query) ?: return ApiResult.NetworkError("服务器地址无效")
        if (tokens.get() == null) return ApiResult.Err(401, "no_token", "尚未绑定")
        return execute(if (poll) pollHttp else http, builder(u, true).get().build(), ser)
    }

    private suspend fun <T, B> post(path: String, body: B?, bodySer: KSerializer<B>?, ser: KSerializer<T>?): ApiResult<T> {
        val u = url(path) ?: return ApiResult.NetworkError("服务器地址无效")
        if (tokens.get() == null) return ApiResult.Err(401, "no_token", "尚未绑定")
        val text = if (body != null && bodySer != null) json.encodeToString(bodySer, body) else "{}"
        return execute(http, builder(u, true).post(text.toRequestBody(jsonType)).build(), ser)
    }

    // ---------------- 绑定（无需 Bearer，base 由调用方传入，此时尚未保存） ----------------
    suspend fun pair(baseUrl: String, req: PairRequest): ApiResult<PairResponse> {
        val u = url("/device/pair", base = baseUrl) ?: return ApiResult.NetworkError("服务器地址无效")
        val text = json.encodeToString(PairRequest.serializer(), req)
        return execute(http, builder(u, false).post(text.toRequestBody(jsonType)).build(), PairResponse.serializer(), handleRevoked = false)
    }

    // ---------------- 设备 API ----------------
    suspend fun heartbeat(req: HeartbeatRequest) =
        post("/device/heartbeat", req, HeartbeatRequest.serializer(), HeartbeatResponse.serializer())

    suspend fun syncMessages(req: SyncRequest) =
        post("/device/messages/sync", req, SyncRequest.serializer(), SyncResult.serializer())

    suspend fun pollTasks(waitS: Int): ApiResult<PollResponse> =
        get("/device/tasks/poll", PollResponse.serializer(), mapOf("wait" to waitS.coerceIn(0, MAX_POLL_WAIT_S).toString()), poll = true)

    suspend fun claimTask(taskId: String) =
        post("/device/tasks/claim", ClaimRequest(taskId), ClaimRequest.serializer(), ClaimResponse.serializer())

    suspend fun taskResult(req: TaskResultRequest) =
        post("/device/tasks/result", req, TaskResultRequest.serializer(), TaskResultResponse.serializer())

    suspend fun postSyncProgress(req: SyncProgressDto) =
        post<Unit, SyncProgressDto>("/device/sync/progress", req, SyncProgressDto.serializer(), null)

    suspend fun getSyncProgress() = get("/device/sync/progress", SyncProgressDto.serializer())

    companion object {
        const val MAX_POLL_WAIT_S = 25
    }
}
