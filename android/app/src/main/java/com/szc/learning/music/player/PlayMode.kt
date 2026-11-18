package com.szc.learning.music.player

/**
 * 播放方式。四种互斥，同一时刻只有一种生效。
 *
 * 之前 shuffle 和 repeatMode 是两个独立开关，能同时点亮（"顺序循环 + 随机"），
 * 用户看到两个高亮图标会认为坏了。现在收成一个状态机，按钮点一下前进一格。
 */
enum class PlayMode {
    /** 顺序播放：播完最后一首就停。 */
    SEQUENCE,

    /** 列表循环。 */
    REPEAT_ALL,

    /** 单曲循环。 */
    REPEAT_ONE,

    /** 随机播放：打乱顺序，并且循环下去。 */
    SHUFFLE,
    ;

    fun next(): PlayMode = entries[(ordinal + 1) % entries.size]
}
