package com.szc.learning.core.network.music

import com.google.gson.annotations.SerializedName

/**
 * 云端曲目。字段与后端 `MusicTrack` 一一对应。
 *
 * `durationMs` 用毫秒而不是秒：客户端所有播放位置都是毫秒，
 * 单位在边界上转换一次就够，不要在 UI 层再乘 1000。
 *
 * `url` 允许为空 —— 后端可以只存文件名，由客户端按 `stream/{id}` 拼出地址，
 * 这样服务端换域名/加 CDN 时不用改数据。
 */
data class MusicTrackDto(
    @SerializedName("id") val id: Long = 0,
    @SerializedName("title") val title: String = "",
    @SerializedName("artist") val artist: String = "",
    @SerializedName("album") val album: String = "",
    @SerializedName("durationMs") val durationMs: Long = 0,
    @SerializedName("url") val url: String? = null,
    @SerializedName("cover") val cover: String? = null,
    @SerializedName("lyrics") val lyrics: String? = null,
)
