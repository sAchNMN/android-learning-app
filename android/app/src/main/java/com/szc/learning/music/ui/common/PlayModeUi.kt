package com.szc.learning.music.ui.common

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.szc.learning.R
import com.szc.learning.music.player.PlayMode

/**
 * 播放方式的图标与文案。
 *
 * 放在 UI 层而不是 PlayMode 里：player 包不引 R，
 * 否则以后写 JVM 单测会被资源表拖住。
 */
@get:DrawableRes
val PlayMode.iconRes: Int
    get() = when (this) {
        PlayMode.SEQUENCE -> R.drawable.ic_sequence
        PlayMode.REPEAT_ALL -> R.drawable.ic_repeat
        PlayMode.REPEAT_ONE -> R.drawable.ic_repeat_one
        PlayMode.SHUFFLE -> R.drawable.ic_shuffle
        PlayMode.RECOMMEND -> R.drawable.ic_recommend
    }

@get:StringRes
val PlayMode.labelRes: Int
    get() = when (this) {
        PlayMode.SEQUENCE -> R.string.player_mode_sequence
        PlayMode.REPEAT_ALL -> R.string.player_mode_repeat_all
        PlayMode.REPEAT_ONE -> R.string.player_mode_repeat_one
        PlayMode.SHUFFLE -> R.string.player_mode_shuffle
        PlayMode.RECOMMEND -> R.string.player_mode_recommend
    }
