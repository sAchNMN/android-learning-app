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

    /**
     * 进度快照。**只在离散事件时更新**（seek / 切歌 / 暂停 / 恢复）。
     *
     * 连续进度在 [PlayerController.position] 那条流里 —— 350ms 一次的 tick 若写进这里，
     * 整份状态每 tick 换一次对象，所有订阅者都得重跑一遍 render。
     */
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val hasNext: Boolean = false,
    val hasPrevious: Boolean = false,
    val shuffle: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    /**
     * 「歌曲推荐」是 media3 两个开关表达不了的第三态，单独记在这里。
     * 它不改变底层循环/随机（RECOMMEND 映射成顺序播放），只决定队列播完后
     * 要不要续实时推荐。
     */
    val recommendMode: Boolean = false,
    val queueSize: Int = 0,
    /** 播放倍速。ExoPlayer 保持音高（默认），1.5x 不会变成花栗鼠。 */
    val speed: Float = 1f,
) {
    val hasTrack: Boolean get() = track != null

    // 进度比例已经搬到使用处自己算（position / duration）：这里是「离散状态快照」，
    // 300ms 级的进度不该混进来，否则每次 tick 都要重建整份 state

    val isRepeatOne: Boolean get() = repeatMode == Player.REPEAT_MODE_ONE
    val isRepeatAll: Boolean get() = repeatMode == Player.REPEAT_MODE_ALL

    /**
     * 底层两个开关 + 独立的推荐开关合成一个对外状态。优先级：
     * recommendMode > shuffle > repeatMode —— 随机也带着 REPEAT_MODE_ALL，
     * 而推荐模式底层是顺序播放，只能靠各自的标志位区分。
     */
    val playMode: PlayMode
        get() = when {
            recommendMode -> PlayMode.RECOMMEND
            shuffle -> PlayMode.SHUFFLE
            repeatMode == Player.REPEAT_MODE_ONE -> PlayMode.REPEAT_ONE
            repeatMode == Player.REPEAT_MODE_ALL -> PlayMode.REPEAT_ALL
            else -> PlayMode.SEQUENCE
        }
}
