package com.szc.learning.music.data.source

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.szc.learning.core.network.SharedHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 客户端音源的公共请求层。
 *
 * 两个平台（网易云 / 酷狗）的差异只在参数拼装和字段名，HTTP 部分完全一样，
 * 所以请求、Cookie 透传、日志、JSON 取值都收在这里，provider 只写业务。
 *
 * 所有出站请求都先过 [SourceThrottle]：同域名串行 + 最小间隔 + 风控熔断。
 * 这是客户端直连的必需品 —— 没有服务端帮我们排队，一次连点就可能把登录态打降权。
 *
 * 一条硬规则：**任何 `JsonObject` 的取值都必须走本文件里的 [str] / [longValue] /
 * [objectOrNull] / [arrayOrNull]**。Gson 的 `getAsJsonObject(key)` 遇到 `"key": null`
 * 会抛 `ClassCastException` 而不是返回 null —— 平台限流时返回 `{"data":null}`
 * 是常态，直接取值会崩掉整个播放页。
 */
internal object SourceHttp {

    /** 桌面 Chrome。三个平台的网页接口都按它返回完整字段。 */
    const val DESKTOP_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    /** 共享连接池：三个平台共用一条，避免每次切音源都重建 TLS 握手。 */
    val client: OkHttpClient = SharedHttp.base.newBuilder()
        .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .followRedirects(true)
        .build()

    // ---------------------------------------------------------------- 请求

    /**
     * GET 一段文本。失败返回 null —— 调用方靠 null 判断降级，不该收到异常。
     *
     * 节流等待与 HTTP 调用都可取消：调用方 `withTimeout` / 协程取消时，
     * 排队中的请求立刻放弃，在途请求 `call.cancel()`，不会继续占后面的名额。
     *
     * @param referer 平台要校验来源，传错直接 403（QQ 的歌词接口尤其严格）
     */
    suspend fun getText(
        url: String,
        referer: String,
        cookie: String = "",
        ua: String = DESKTOP_UA,
    ): String? {
        val host = SourceThrottle.hostOf(url)
        // 1.1 冷却期内不发请求：继续打只会延长平台的封禁
        if (!SourceThrottle.acquire(host)) return null

        val builder = Request.Builder()
            .url(url)
            .get()
            .header("User-Agent", ua)
            .header("Referer", referer)
            .header("Accept", "application/json, text/plain, */*")
        if (cookie.isNotBlank()) builder.header("Cookie", cookie.trim())
        return withContext(Dispatchers.IO) {
            runCatching {
                client.newCall(builder.build()).await().use { response ->
                    // 1.2 一次性读完 body：OkHttp 的 ResponseBody 只能读一次
                    val body = runCatching { response.body?.string() }.getOrNull()
                    // 1.3 判定风控（403/429 或响应体里的限流码），命中就进冷却
                    SourceThrottle.inspect(host, response.code, body)
                    if (!response.isSuccessful) null else body
                }
            }.getOrNull()
        }
    }

    /** GET 一段 JSON。非对象、空串、解析失败一律返回 null。 */
    suspend fun getJson(
        url: String,
        referer: String,
        cookie: String = "",
        ua: String = DESKTOP_UA,
    ): JsonObject? {
        val raw = getText(url, referer, cookie, ua) ?: return null
        return runCatching { JsonParser.parseString(raw).asJsonObject }.getOrNull()
    }

    /**
     * 探一个音频直链：跟随 302 后判断最终地址确实是音频流。
     *
     * 网易云的增强接口偶尔对免费曲目也不下发 url，这时外链接口
     * `/song/media/outer/url` 会 302 到真实 CDN。直接把这个地址交给播放器不行 ——
     * 版权受限时它 302 回来的是错误页，看起来像「能播」。
     * 所以只返回**确认是 audio 且跳转离开了外链接口**的最终 URL。
     */
    suspend fun probeAudio(
        url: String,
        referer: String,
        cookie: String = "",
    ): String? {
        val host = SourceThrottle.hostOf(url)
        if (!SourceThrottle.acquire(host)) return null
        val builder = Request.Builder()
            .url(url)
            .get()
            .header("User-Agent", DESKTOP_UA)
            .header("Referer", referer)
            .header("Range", "bytes=0-0")
        if (cookie.isNotBlank()) builder.header("Cookie", cookie.trim())
        return withContext(Dispatchers.IO) {
            runCatching {
                client.newCall(builder.build()).await().use { response ->
                    SourceThrottle.inspect(host, response.code, null)
                    val finalUrl = response.request.url.toString()
                    val type = response.body?.contentType()?.toString().orEmpty()
                    when {
                        !response.isSuccessful -> null
                        !type.contains("audio", ignoreCase = true) -> null
                        finalUrl.contains("outer/url") -> null
                        else -> finalUrl
                    }
                }
            }.getOrNull()
        }
    }

    // ---------------------------------------------------------------- 编码

    fun encode(value: String): String = try {
        URLEncoder.encode(value, "UTF-8")
    } catch (ex: Exception) {
        value
    }

    fun md5(value: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    /**
     * 去掉平台给歌词/歌名里塞的 `<em>` 高亮标签。
     * 搜索结果如果原样展示，列表里会出现一堆尖括号。
     */
    fun stripTags(value: String): String =
        value.replace(TAG, "").replace("&amp;", "&").replace("&quot;", "\"")
            .replace("&apos;", "'").replace("&lt;", "<").replace("&gt;", ">").trim()

    fun joinArtists(names: List<String>): String =
        names.filter { it.isNotBlank() }.joinToString(" / ")

    /** `http://` 统一升级到 https：release 包的 network_security_config 全局禁明文。 */
    fun secure(url: String): String =
        if (url.startsWith("http://")) "https://" + url.substring(7) else url

    private val TAG = Regex("<[^>]+>")

    private const val CONNECT_TIMEOUT_MS = 8_000L
    private const val READ_TIMEOUT_MS = 12_000L
}

/**
 * 可取消的 OkHttp 调用。
 *
 * 用 enqueue 而不是阻塞的 execute：协程取消时 `call.cancel()` 会真的掐断连接，
 * 而不是等 HTTP 自己返回后才发现结果已经没人要了。
 */
private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isActive) continuation.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            if (continuation.isActive) continuation.resume(response) else response.close()
        }
    })
}

// ---------------------------------------------------------------- JSON 安全取值

/**
 * 取子对象。键不存在、值为 null、类型不是对象 —— 三种情况都返回 null。
 * 这是全项目唯一允许读取嵌套对象的方式，理由见 [SourceHttp] 的类注释。
 */
internal fun JsonObject.objectOrNull(key: String): JsonObject? {
    val element = get(key) ?: return null
    return if (element.isJsonObject) element.asJsonObject else null
}

internal fun JsonObject.arrayOrNull(key: String): JsonArray? {
    val element = get(key) ?: return null
    return if (element.isJsonArray) element.asJsonArray else null
}

/** 按顺序取第一个非空字符串字段。平台之间字段名差异大，靠它抹平。 */
internal fun JsonObject.str(vararg keys: String): String {
    for (key in keys) {
        val element = get(key) ?: continue
        if (element.isJsonNull) continue
        val value = runCatching { element.asString }.getOrNull() ?: continue
        if (value.isNotBlank()) return value
    }
    return ""
}

internal fun JsonObject.longValue(vararg keys: String): Long {
    for (key in keys) {
        val element = get(key) ?: continue
        if (element.isJsonNull) continue
        val value = runCatching { element.asLong }.getOrNull() ?: continue
        if (value != 0L) return value
    }
    return 0L
}

internal fun JsonObject.intValue(vararg keys: String): Int {
    for (key in keys) {
        val element = get(key) ?: continue
        if (element.isJsonNull) continue
        val value = runCatching { element.asInt }.getOrNull() ?: continue
        if (value != 0) return value
    }
    return 0
}

/** 取布尔：兼容 `true` / `1` / `"1"` / `"true"` 四种写法。 */
internal fun JsonObject.truthy(vararg keys: String): Boolean {
    for (key in keys) {
        val element: JsonElement = get(key) ?: continue
        if (element.isJsonNull) continue
        if (element.isJsonPrimitive) {
            val primitive = element.asJsonPrimitive
            if (primitive.isBoolean && primitive.asBoolean) return true
            if (primitive.isNumber && primitive.asInt > 0) return true
            if (primitive.isString && (primitive.asString == "1" || primitive.asString.equals("true", true))) {
                return true
            }
        }
    }
    return false
}

/** Cookie 串 → Map。值里可能含 `=`（比如 token），所以只按第一个 `=` 切。 */
internal fun parseCookie(cookie: String): Map<String, String> {
    val jar = LinkedHashMap<String, String>()
    if (cookie.isBlank()) return jar
    for (part in cookie.split(";")) {
        val index = part.indexOf('=')
        if (index <= 0) continue
        jar[part.substring(0, index).trim()] = part.substring(index + 1).trim()
    }
    return jar
}

internal fun cookieValue(cookie: String, vararg keys: String): String {
    val jar = parseCookie(cookie)
    for (key in keys) {
        val value = jar[key]
        if (!value.isNullOrBlank()) return value
    }
    return ""
}
