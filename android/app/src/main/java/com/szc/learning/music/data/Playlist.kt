package com.szc.learning.music.data

import com.szc.learning.core.common.music.MusicTrack

/**
 * 推荐页的歌单卡片。
 *
 * 两种来源共用同一个模型：
 * - 本机拼出来的（收藏 / 云端 / 本地 / 最近播放 / 同歌手）
 * - 从音源账号同步回来的（字段来自后端，没有曲目详情，只有标题、封面和总数）
 *
 * 后者用 [declaredSize] 覆盖 [size]：它是「歌单里有几首」，
 * 而不是「本机临时装了几首」—— 否则卡片上会显示 1 首。
 */
data class Playlist(
    val key: String,
    val title: String,
    val tag: PlaylistTag,
    val tracks: List<MusicTrack>,
    val declaredSize: Int? = null,
    val coverUrl: String? = null,
) {
    val cover: MusicTrack? get() = tracks.firstOrNull()
    val size: Int get() = declaredSize ?: tracks.size
}

enum class PlaylistTag { FAVORITE, CLOUD, LOCAL, RECENT, ARTIST, SOURCE }
