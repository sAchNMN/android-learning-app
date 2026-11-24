package com.szc.learning.music.player

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.SessionCommand
import com.szc.learning.core.common.music.MusicTrack
import com.szc.learning.core.common.music.TrackSource

/**
 * `MusicTrack` ↔ `MediaItem` 的唯一转换点。
 *
 * 原先这两段逻辑私藏在 `PlayerController` 里，结果 `PlaybackService` 想从
 * `MediaItem` 还原曲目（通知栏红心、恢复播放）时拿不到，只能复制一份 ——
 * 两份一旦漂移，恢复出来的曲目就会缺字段（少了 sourceKey 的在线曲目会变成"本地"）。
 *
 * `extras` 里必须把所有还原需要的字段都带上：在线曲目的 `key` 是
 * `ONLINE:平台:id`，从 mediaId 反推会把平台名当成 id 的一部分。
 */
internal object TrackMediaItem {

    const val KEY_DURATION = "aurora.duration"
    const val KEY_ALBUM = "aurora.album"
    const val KEY_ART = "aurora.art"
    const val KEY_SOURCE = "aurora.source"
    const val KEY_FOLDER = "aurora.folder"
    const val KEY_TRACK_ID = "aurora.trackId"
    const val KEY_SOURCE_KEY = "aurora.sourceKey"
    const val KEY_HASH = "aurora.hash"
    const val KEY_ALBUM_ID = "aurora.albumId"
    const val KEY_ALBUM_AUDIO_ID = "aurora.albumAudioId"
    const val KEY_PLAYABLE = "aurora.playable"

    fun toMediaItem(track: MusicTrack): MediaItem {
        val extras = Bundle().apply {
            putLong(KEY_DURATION, track.durationMs)
            putString(KEY_ALBUM, track.album)
            putString(KEY_ART, track.artUri)
            putString(KEY_SOURCE, track.source.name)
            putString(KEY_FOLDER, track.folder)
            putString(KEY_TRACK_ID, track.id)
            putString(KEY_SOURCE_KEY, track.sourceKey)
            putString(KEY_HASH, track.hash)
            putString(KEY_ALBUM_ID, track.albumId)
            putString(KEY_ALBUM_AUDIO_ID, track.albumAudioId)
            putBoolean(KEY_PLAYABLE, track.playable)
        }
        return MediaItem.Builder()
            .setMediaId(track.key)
            .setUri(track.uri)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(track.title)
                    .setArtist(track.artist)
                    .setAlbumTitle(track.album)
                    .setArtworkUri(track.artUri?.let(Uri::parse))
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .setExtras(extras)
                    .build(),
            )
            .build()
    }

    fun toTrack(item: MediaItem): MusicTrack {
        val meta = item.mediaMetadata
        val extras = meta.extras
        val source = extras?.getString(KEY_SOURCE)
            ?.let { runCatching { TrackSource.valueOf(it) }.getOrNull() }
            ?: TrackSource.LOCAL
        return MusicTrack(
            id = extras?.getString(KEY_TRACK_ID) ?: item.mediaId.substringAfter(':', item.mediaId),
            title = meta.title?.toString().orEmpty(),
            artist = meta.artist?.toString().orEmpty(),
            album = extras?.getString(KEY_ALBUM).orEmpty(),
            durationMs = extras?.getLong(KEY_DURATION) ?: 0L,
            uri = item.localConfiguration?.uri?.toString().orEmpty(),
            artUri = extras?.getString(KEY_ART),
            source = source,
            folder = extras?.getString(KEY_FOLDER).orEmpty(),
            sourceKey = extras?.getString(KEY_SOURCE_KEY).orEmpty(),
            hash = extras?.getString(KEY_HASH).orEmpty(),
            albumId = extras?.getString(KEY_ALBUM_ID).orEmpty(),
            albumAudioId = extras?.getString(KEY_ALBUM_AUDIO_ID).orEmpty(),
            playable = extras?.getBoolean(KEY_PLAYABLE, true) ?: true,
        )
    }
}

/** 自定义会话命令：通知栏/锁屏的红心。 */
internal const val COMMAND_TOGGLE_LIKE = "aurora.toggle_like"

/** 自定义会话命令：Service 侧的定时关闭（T5）。 */
internal const val COMMAND_SET_SLEEP_TIMER = "aurora.set_sleep_timer"

/** 自定义会话命令：Service 侧的倍速（T5）。 */
internal const val COMMAND_SET_SPEED = "aurora.set_speed"

/** 定时关闭的命令参数：分钟数。0 = 取消，-1 = 播完当前歌曲。 */
internal const val ARG_MINUTES = "minutes"
internal const val SLEEP_MODE_TRACK_END = -1

/** 倍速的命令参数。 */
internal const val ARG_SPEED = "speed"

/**
 * 通知栏按钮要用的会话命令集合。
 *
 * 必须在这里集中定义：`onConnect` 里要把它加进 availableSessionCommands，
 * 按钮本身也用它构造 `CommandButton`，两处写错一个字符按钮就是死的。
 */
internal object SessionCommands {
    val toggleLike = SessionCommand(COMMAND_TOGGLE_LIKE, Bundle.EMPTY)
    val setSleepTimer = SessionCommand(COMMAND_SET_SLEEP_TIMER, Bundle.EMPTY)
    val setSpeed = SessionCommand(COMMAND_SET_SPEED, Bundle.EMPTY)

    val all: List<SessionCommand> = listOf(toggleLike, setSleepTimer, setSpeed)
}
