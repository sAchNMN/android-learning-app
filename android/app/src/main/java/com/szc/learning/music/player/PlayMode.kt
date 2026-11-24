package com.szc.learning.music.player

/**
 * 播放方式。五种互斥，同一时刻只有一种生效。
 *
 * 之前 shuffle 和 repeatMode 是两个独立开关，能同时点亮（"顺序循环 + 随机"），
 * 用户看到两个高亮图标会认为坏了。现在收成一个状态机，按钮点一下前进一格。
 *
 * [RECOMMEND] 是 media3 两开关表达不了的第三态，靠 [PlayerUiState.recommendMode]
 * 单独记住。它不改变"怎么播队列"，只决定"队列播完后要不要续实时推荐"。
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

    /**
     * 歌曲推荐：顺序播完当前队列后，自动续一批实时推荐，队列无限延长。
     *
     * 底层按 [SEQUENCE] 映射（不循环、不随机），这样播到队尾才有"播完"事件；
     * 队列没播完之前和顺序播放完全一样，不会突然塞进推荐歌。
     */
    RECOMMEND,
    ;

    fun next(): PlayMode = entries[(ordinal + 1) % entries.size]
}
