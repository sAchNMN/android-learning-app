package com.szc.learning.music.ui.common

import android.content.Context
import com.szc.learning.R
import com.szc.learning.core.common.music.MusicTrack
import com.szc.learning.music.data.Playlist
import com.szc.learning.music.data.PlaylistTag
import com.szc.learning.music.data.StoredPlaylist

/**
 * 把曲库切成歌单卡片。
 *
 * 没有服务端歌单表，所以这里不假装有运营：第一张给收藏，
 * 然后云端 / 本地 / 最近播放，再补同歌手合集。数据空就不生成，
 * 卡片区自然收缩，不会留一排空壳。
 */
object Playlists {

    fun build(
        context: Context,
        cloud: List<MusicTrack>,
        local: List<MusicTrack>,
        recent: List<MusicTrack>,
        favorites: List<MusicTrack>,
        shared: List<StoredPlaylist> = emptyList(),
        limit: Int = 6,
    ): List<Playlist> {
        val out = mutableListOf<Playlist>()

        if (favorites.isNotEmpty()) {
            out += Playlist(
                key = "favorites",
                title = context.getString(R.string.playlist_favorite_title),
                tag = PlaylistTag.FAVORITE,
                tracks = favorites,
            )
        }

        // 导入的分享歌单排在收藏之后：刚导入的人第一眼就要能找到它，
        // 而不是被云端/本地/最近那几张常驻卡片挤到最下面
        for (playlist in shared) {
            if (out.size >= limit) break
            if (playlist.tracks.isEmpty()) continue
            out += Playlist(
                key = "shared:${playlist.code}",
                title = playlist.title,
                tag = PlaylistTag.SHARED,
                tracks = playlist.tracks,
                coverUrl = playlist.tracks.firstOrNull()?.artUri,
            )
        }

        if (cloud.isNotEmpty()) {
            out += Playlist(
                key = "cloud",
                title = context.getString(R.string.playlist_cloud_title),
                tag = PlaylistTag.CLOUD,
                tracks = cloud,
            )
        }
        if (local.isNotEmpty()) {
            out += Playlist(
                key = "local",
                title = context.getString(R.string.playlist_local_title),
                tag = PlaylistTag.LOCAL,
                tracks = local,
            )
        }
        if (recent.isNotEmpty()) {
            out += Playlist(
                key = "recent",
                title = context.getString(R.string.playlist_recent_title),
                tag = PlaylistTag.RECENT,
                tracks = recent,
            )
        }

        // 同一个歌手至少两首才值得单独成单，否则卡片看起来像重复
        val byArtist = (cloud + local)
            .filter { it.artist.isNotBlank() }
            .groupBy { it.artist }
            .filterValues { it.size >= 2 }
            .entries
            .sortedByDescending { it.value.size }

        for ((artist, tracks) in byArtist) {
            if (out.size >= limit) break
            out += Playlist(
                key = "artist:$artist",
                title = context.getString(R.string.playlist_artist_title, artist),
                tag = PlaylistTag.ARTIST,
                tracks = tracks,
            )
        }

        return out.distinctBy { it.key }.take(limit)
    }
}