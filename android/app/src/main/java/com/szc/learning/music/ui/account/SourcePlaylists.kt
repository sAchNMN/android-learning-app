package com.szc.learning.music.ui.account

import android.content.Context
import com.szc.learning.core.network.music.UserPlaylistInfo
import com.szc.learning.music.data.Playlist
import com.szc.learning.music.data.PlaylistTag
import com.szc.learning.music.ui.playlist.PlaylistDetailActivity

/*
 * 账号歌单的展示与跳转。
 *
 * 账号页和「我的」页都要摆这条横滑轨道，映射规则必须只有一份：
 * 卡片上只有标题、封面和总数，曲目点进详情页才拉 —— 平台歌单动辄几百首，
 * 一进页面全下回来既慢，也更容易被平台风控。
 */

/** 账号歌单 → 歌单卡。曲目为空，点开才拉。 */
fun UserPlaylistInfo.toPlaylistCard(): Playlist = Playlist(
    key = "source:$source:$id",
    title = name,
    tag = PlaylistTag.SOURCE,
    tracks = emptyList(),
    declaredSize = trackCount,
    coverUrl = coverUrl,
)

/**
 * 打开账号歌单详情。
 *
 * key 的格式是 `source:<平台>:<歌单id>`，拆出来交给详情页 ——
 * 详情页拿 source 决定用哪个音源取曲目，所以这里不能只传 id。
 */
fun Context.openSourcePlaylist(playlist: Playlist) {
    val parts = playlist.key.removePrefix("source:").split(":", limit = 2)
    if (parts.size != 2) return
    PlaylistDetailActivity.start(
        context = this,
        source = parts[0],
        id = parts[1],
        name = playlist.title,
        coverUrl = playlist.coverUrl,
        trackCount = playlist.size,
    )
}
