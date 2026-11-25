package com.szc.learning.core.network.music

import com.szc.learning.core.common.music.MusicTrack
import com.szc.learning.core.common.music.TrackSource

/**
 * 播放埋点的上报体，字段与后端 `ListenEventBody` 对齐。
 *
 * `event` 只取 play / finish / skip / like —— 权重由服务端定，
 * 客户端连"自己这条值几分"都不该知道：那等于把榜单交给调用方。
 */
data class ListenEventRequest(
    val deviceId: String,
    val source: String,
    val trackId: String,
    val title: String,
    val artist: String,
    val cover: String?,
    val durationMs: Long,
    val event: String,
    val positionMs: Long,
)

/** 榜单里的一行，已经转成可直接播放的 [MusicTrack]。 */
data class RankedTrack(
    val rank: Int,
    val track: MusicTrack,
    /** 热度分：热歌榜是窗口内得分，飙升榜是增量。 */
    val score: Long,
    /** 飙升榜的增量，热歌榜恒为 0。 */
    val delta: Long,
)

/**
 * 一次榜单查询的结果。
 *
 * [message] 非空表示查不了（表没建 / 后端不可达），此时 [tracks] 一定是空的。
 * 榜单是**必须依赖后端**的功能：它天生是跨用户聚合，单机算不出来，
 * 所以这里不做任何"本地榜单"的降级 —— 那只会是一份自己骗自己的数据。
 */
data class RankSnapshot(
    val type: String,
    val windowStart: String?,
    val updatedAt: String?,
    val eventCount: Long,
    val tracks: List<RankedTrack>,
    val message: String? = null,
)

// ---------------------------------------------------------------- 传输结构

data class RankTrackDto(
    val rank: Int = 0,
    val songKey: String? = null,
    val title: String? = null,
    val artist: String? = null,
    val cover: String? = null,
    val source: String? = null,
    val trackId: String? = null,
    val durationMs: Long = 0,
    val score: Long = 0,
    val delta: Long = 0,
)

data class RankSnapshotDto(
    val type: String? = null,
    val windowStart: String? = null,
    val updatedAt: String? = null,
    val eventCount: Long = 0,
    val tracks: List<RankTrackDto>? = null,
    val message: String? = null,
)

/**
 * 榜单行 → 可播曲目。
 *
 * `uri` 留空：榜单里只有平台 id，真实地址要点播时才换（和搜索结果同一条路径）。
 * 真把它当成本地文件路径填进去，ExoPlayer 会直接报 FileNotFound。
 */
internal fun RankTrackDto.toRankedTrack(): RankedTrack? {
    val id = trackId.orEmpty()
    val name = title.orEmpty()
    if (id.isBlank() || name.isBlank()) return null
    return RankedTrack(
        rank = rank,
        track = MusicTrack(
            id = id,
            title = name,
            artist = artist.orEmpty(),
            album = "",
            durationMs = durationMs,
            uri = "",
            artUri = cover?.takeIf { it.isNotBlank() },
            // 榜单只收在线曲目（本机文件别人播不了），source 就是平台标识
            source = TrackSource.ONLINE,
            sourceKey = source.orEmpty(),
        ),
        score = score,
        delta = delta,
    )
}
