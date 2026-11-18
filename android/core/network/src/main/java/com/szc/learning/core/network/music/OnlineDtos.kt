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

/**
 * 推荐响应。`basedOn` 是口味种子（歌手名），页面用它说明「为什么推这些」；
 * `reason` 取 favorites / likes / discover，对应「按收藏」「按后台记录」「无偏好兜底」。
 */
data class RecommendDto(
    @SerializedName("basedOn") val basedOn: List<String> = emptyList(),
    @SerializedName("tracks") val tracks: List<OnlineTrackDto> = emptyList(),
    @SerializedName("reason") val reason: String = "discover",
)

/** 收藏请求体。`source` 是 netease / kugou / cloud / local。 */
data class LikeBody(
    @SerializedName("deviceId") val deviceId: String,
    @SerializedName("source") val source: String,
    @SerializedName("id") val id: String,
    @SerializedName("title") val title: String,
    @SerializedName("artist") val artist: String,
    @SerializedName("album") val album: String,
    @SerializedName("durationMs") val durationMs: Long,
    @SerializedName("cover") val cover: String?,
    @SerializedName("hash") val hash: String,
    @SerializedName("albumId") val albumId: String,
    @SerializedName("albumAudioId") val albumAudioId: String,
    @SerializedName("liked") val liked: Boolean,
)

/** 单平台同步回执。`ok=false` 不是请求失败，只是这个平台没同步成功。 */
data class SyncNoteDto(
    @SerializedName("ok") val ok: Boolean = false,
    @SerializedName("source") val source: String = "",
    @SerializedName("message") val message: String = "",
)

data class LikeResultDto(
    @SerializedName("liked") val liked: Boolean = true,
    @SerializedName("saved") val saved: Boolean = false,
    @SerializedName("syncs") val syncs: List<SyncNoteDto> = emptyList(),
)

/** 后端存的喜欢记录，字段够还原成一条可播放的 MusicTrack。 */
data class UserLikeDto(
    @SerializedName("source") val source: String = "",
    @SerializedName("id") val id: String = "",
    @SerializedName("title") val title: String = "",
    @SerializedName("artist") val artist: String = "",
    @SerializedName("album") val album: String = "",
    @SerializedName("durationMs") val durationMs: Long = 0,
    @SerializedName("cover") val cover: String? = null,
    @SerializedName("hash") val hash: String? = null,
    @SerializedName("albumId") val albumId: String? = null,
    @SerializedName("albumAudioId") val albumAudioId: String? = null,
)

/** 音源账号里的歌单卡片。 */
data class UserPlaylistDto(
    @SerializedName("source") val source: String = "",
    @SerializedName("id") val id: String = "",
    @SerializedName("name") val name: String = "",
    @SerializedName("coverUrl") val coverUrl: String? = null,
    @SerializedName("trackCount") val trackCount: Int = 0,
    @SerializedName("own") val own: Boolean? = null,
)

data class PlaylistDetailDto(
    @SerializedName("source") val source: String = "",
    @SerializedName("id") val id: String = "",
    @SerializedName("name") val name: String = "",
    @SerializedName("coverUrl") val coverUrl: String? = null,
    @SerializedName("tracks") val tracks: List<OnlineTrackDto> = emptyList(),
)