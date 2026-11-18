package com.szc.learning.music.data

import com.szc.learning.core.common.music.MusicTrack

/**
 * 首页/推荐页的歌单卡片。
 *
 * 数据是现算的（云端、本地、最近播放、同歌手合集），
 * 不做服务端歌单表 —— 这个项目没有社区和运营，歌单只是"曲库的另一种切法"。
 */
data class Playlist(
    val key: String,
    val title: String,
    val tag: PlaylistTag,
    val tracks: List<MusicTrack>,
) {
    val cover: MusicTrack? get() = tracks.firstOrNull()
    val size: Int get() = tracks.size
}

enum class PlaylistTag { FAVORITE, CLOUD, LOCAL, RECENT, ARTIST }