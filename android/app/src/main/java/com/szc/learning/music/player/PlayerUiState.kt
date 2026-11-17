package com.szc.learning.music.player

import androidx.media3.common.Player

/**
 * 播放器对外暴露的唯一状态快照。
 *
 * 不用把 MediaController 直接交给 UI：那样 Fragment 就要处理连接未完成的空窗期，
 * 而且 UI 会绑死在 media3 的类型上。这里一次性摊平成纯数据，UI 只订阅 StateFlow。
 */
data class PlayerUiState(
    val track: com.szc.learning.core.common.music.MusicTrack? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val hasNext: Boolean = false,
    val hasPrevious: Boolean = false,
    val shuffle: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val queueSize: Int = 0,
) {
    val hasTrack: Boolean get() = track != null

    /** 0f~1f，未知时长时返回 0 而不是 NaN。 */
    val progress: Float
        get() = if (durationMs <= 0L) 0f else (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)

    val isRepeatOne: Boolean get() = repeatMode == Player.REPEAT_MODE_ONE
    val isRepeatAll: Boolean get() = repeatMode == Player.REPEAT_MODE_ALL
}
