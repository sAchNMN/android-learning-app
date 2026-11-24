package com.szc.learning.music.data.room

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.szc.learning.music.data.source.objectOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/** 房间在服务端的快照。 */
data class RoomSnapshot(
    val code: String = "",
    val hostId: String = "",
    val members: List<MemberInfo> = emptyList(),
)

/**
 * 房间的 REST 调用。
 *
 * 没有走 Retrofit，跟 `SourceHttp` 一个理由：房间只有四个动作，
 * 而且**创建房间要立刻拿到码**才能继续下一步，用 OkHttp 直接同步调用最直白。
 * 另外它超时必须短 —— 后端不通时要马上回落成"只能局域网"，
 * 按 `ApiClient` 那套 15 秒 connect timeout，用户点一下要干等 15 秒。
 *
 * 注意：这里只管"谁在房间里"。**播放状态一律不走 HTTP**，
 * 那是 [RoomTransport] 的事。把状态塞进 REST 会让同步变成请求-响应模型，
 * 每台设备都得轮询，延迟最少一个 RTT 起步。
 */
class RoomApi(private val baseUrl: String) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_S, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    private val root: String = baseUrl.trimEnd('/') + "/app/music/room"

    private val jsonType = "application/json; charset=utf-8".toMediaType()

    suspend fun create(me: MemberInfo): RoomSnapshot? = post("$root/create", body(me, code = null))

    suspend fun join(code: String, me: MemberInfo): RoomSnapshot? =
        post("$root/join", body(me, code = code))

    suspend fun snapshot(code: String): RoomSnapshot? = runCatching {
        withContext(Dispatchers.IO) {
            val request = Request.Builder().url("$root/$code").get().build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                parseSnapshot(response.body?.string())
            }
        }
    }.getOrNull()

    /**
     * 更新自己的网络指纹。
     *
     * 加房间的人可能中途换网（从 WiFi 切到流量），指纹要跟着变，
     * 否则房主还会往一个已经不存在的局域网地址发包。
     */
    suspend fun report(code: String, me: MemberInfo): RoomSnapshot? =
        post("$root/$code/presence", body(me, code = code))

    suspend fun leave(code: String, deviceId: String) {
        runCatching {
            withContext(Dispatchers.IO) {
                val payload = gson.toJson(mapOf("code" to code, "deviceId" to deviceId))
                val request = Request.Builder()
                    .url("$root/leave")
                    .post(payload.toRequestBody(jsonType))
                    .build()
                client.newCall(request).execute().close()
            }
        }
    }

    private fun body(me: MemberInfo, code: String?) = gson.toJson(
        PresenceBody(
            code = code,
            deviceId = me.deviceId,
            nickname = me.nickname,
            subnet = me.subnet,
            gateway = me.gateway,
            lanPort = me.lanPort,
            lanIp = me.lanIp,
        ),
    )

    private suspend fun post(url: String, payload: String): RoomSnapshot? = runCatching {
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(url)
                .post(payload.toRequestBody(jsonType))
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                parseSnapshot(response.body?.string())
            }
        }
    }.getOrNull()

    private fun parseSnapshot(text: String?): RoomSnapshot? {
        if (text.isNullOrBlank()) return null
        val obj = runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull() ?: return null
        // 若依标准响应：{code:200, msg, data}
        val code = obj.get("code")?.takeIf { !it.isJsonNull }?.asInt ?: return null
        if (code != 200) return null
        val data = obj.objectOrNull("data") ?: return null
        val members = data.get("members")?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { element ->
            runCatching {
                val member = element.asJsonObject
                MemberInfo(
                    deviceId = member.get("deviceId")?.takeIf { !it.isJsonNull }?.asString.orEmpty(),
                    nickname = member.get("nickname")?.takeIf { !it.isJsonNull }?.asString.orEmpty(),
                    host = member.get("host")?.takeIf { !it.isJsonNull }?.asBoolean ?: false,
                    subnet = member.get("subnet")?.takeIf { !it.isJsonNull }?.asString,
                    gateway = member.get("gateway")?.takeIf { !it.isJsonNull }?.asString,
                    lanIp = member.get("lanIp")?.takeIf { !it.isJsonNull }?.asString,
                    lanPort = member.get("lanPort")?.takeIf { !it.isJsonNull }?.asInt ?: 0,
                )
            }.getOrNull()
        }.orEmpty()

        return RoomSnapshot(
            // JsonNull 不是 null 引用，?. 挡不住它 —— 直接 asString 会抛
            // UnsupportedOperationException。项目里在酷我歌词上踩过同样的坑
            code = data.get("code")?.takeIf { !it.isJsonNull }?.asString.orEmpty(),
            hostId = data.get("hostId")?.takeIf { !it.isJsonNull }?.asString.orEmpty(),
            members = members,
        )
    }

    private data class PresenceBody(
        val code: String?,
        val deviceId: String,
        val nickname: String,
        val subnet: String?,
        val gateway: String?,
        val lanPort: Int,
        val lanIp: String?,
    )

    private companion object {
        const val CONNECT_TIMEOUT_S = 4L
        const val READ_TIMEOUT_S = 6L
    }
}
