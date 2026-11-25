package com.szc.learning.music.ui.playlist

import android.app.Activity
import android.widget.EditText
import android.widget.FrameLayout
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.FragmentActivity
import com.szc.learning.R
import com.szc.learning.core.common.toast
import com.szc.learning.music.data.LocalPlaylist
import com.szc.learning.music.ui.MusicViewModel
import com.szc.learning.music.ui.widget.dp

/**
 * 「保存到歌单」弹层。
 *
 * 用系统单选列表而不是自绘底部弹层：这里的选项数量完全由用户建了几个歌单决定，
 * 可能是 1 个也可能是 20 个，列表自带滚动比固定高度好处理。
 *
 * 入口是**正在播放的那首**，不去猜用户长按了哪一行 —— 播放器菜单里能点的
 * 只有「当前这首歌」，语义明确，也不需要给列表页加上下文菜单。
 */
object SaveToPlaylistSheet {

    fun show(activity: FragmentActivity, viewModel: MusicViewModel) {
        val track = viewModel.currentTrack()
        if (track == null) {
            activity.toast(R.string.no_track_playing)
            return
        }
        val playlists = viewModel.localPlaylists()
        val labels = playlists.map { it.title }.toMutableList()
        labels += activity.getString(R.string.local_playlist_new)

        AlertDialog.Builder(activity)
            .setTitle(R.string.local_playlist_save_to)
            .setItems(labels.toTypedArray()) { _, which ->
                if (which < playlists.size) {
                    addTo(activity, viewModel, playlists[which], track)
                } else {
                    createThenAdd(activity, viewModel, track)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun addTo(
        activity: Activity,
        viewModel: MusicViewModel,
        playlist: LocalPlaylist,
        track: com.szc.learning.core.common.music.MusicTrack,
    ) {
        val added = viewModel.addToPlaylist(playlist.id, track)
        activity.toast(
            if (added) {
                activity.getString(R.string.local_playlist_added, playlist.title)
            } else {
                activity.getString(R.string.local_playlist_exists)
            },
        )
    }

    /** 新建 + 立刻加歌：用户点「新建歌单」时想的就是「把这首放进去」，不该再点一次。 */
    private fun createThenAdd(
        activity: FragmentActivity,
        viewModel: MusicViewModel,
        track: com.szc.learning.core.common.music.MusicTrack,
    ) {
        val input = EditText(activity).apply {
            hint = activity.getString(R.string.local_playlist_new_hint)
            setSingleLine()
        }
        val container = FrameLayout(activity).apply {
            setPadding(dp(22f).toInt(), dp(8f).toInt(), dp(22f).toInt(), 0)
            addView(input)
        }
        AlertDialog.Builder(activity)
            .setTitle(R.string.local_playlist_new)
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val created = viewModel.createPlaylist(input.text?.toString().orEmpty())
                addTo(activity, viewModel, created, track)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
