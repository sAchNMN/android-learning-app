package com.szc.learning.core.network.music

import com.google.gson.annotations.SerializedName

/** 聚合搜索响应：单个平台失败只进 `errors`，不拖垮整次搜索。 */
data class OnlineSearchDto(
    @SerializedName("keyword") val keyword: String = "",
    @SerializedName("tracks") val tracks: List<OnlineTrackDto> = emptyList(),
    @SerializedName("errors") val errors: Map<String, String> = emptyMap(),
)

/** 归一化后的在线曲目，字段与后端 `OnlineTrack` 一一对应。 */
data class OnlineTrackDto(
    @SerializedName("source") val source: String = "",
    @SerializedName("id") val id: String = "",
    @SerializedName("title") val title: String = "",
    @SerializedName("artist") val artist: String = "",
    @SerializedName("album") val album: String = "",
    @SerializedName("durationMs") val durationMs: Long = 0,
    @SerializedName("coverUrl") val coverUrl: String? = null,
    @SerializedName("playable") val playable: Boolean? = null,
    @SerializedName("hash") val hash: String? = null,
    @SerializedName("albumId") val albumId: String? = null,
    @SerializedName("albumAudioId") val albumAudioId: String? = null,
)

/**
 * 取址结果。
 *
 * 注意 `playable=false` 仍然是一次**成功响应**：它携带 category/message，
 * 客户端要展示「需要登录/需要会员」，而不是当成网络错误。
 */
data class OnlinePlayDto(
    @SerializedName("source") val source: String = "",
    @SerializedName("id") val id: String = "",
    @SerializedName("url") val url: String? = null,
    @SerializedName("playable") val playable: Boolean = false,
    @SerializedName("quality") val quality: String? = null,
    @SerializedName("category") val category: String? = null,
    @SerializedName("message") val message: String? = null,
    @SerializedName("switchedFrom") val switchedFrom: String? = null,
    @SerializedName("switchedTo") val switchedTo: String? = null,
    @SerializedName("trial") val trial: Boolean = false,
)

/** 音源管理项。cookie 原文永远不下发，只有 `hasCookie`。 */
data class MusicSourceDto(
    @SerializedName("key") val key: String = "",
    @SerializedName("label") val label: String = "",
    @SerializedName("enabled") val enabled: Boolean = true,
    @SerializedName("priority") val priority: Int = 10,
    @SerializedName("hasCookie") val hasCookie: Boolean = false,
)

/** 更新音源：三个字段都可空，传空串 cookie 表示清除。 */
data class SourceUpdateBody(
    @SerializedName("enabled") val enabled: Boolean? = null,
    @SerializedName("priority") val priority: Int? = null,
    @SerializedName("cookie") val cookie: String? = null,
)