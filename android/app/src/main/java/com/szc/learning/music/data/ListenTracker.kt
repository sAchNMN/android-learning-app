package com.szc.learning.music.data

import com.szc.learning.core.common.music.MusicTrack

/** 埋点事件的形态。前三种由播放器产生，`like` 由收藏动作直接上报。 */
enum class ListenEventKind(val wire: String) {
    PLAY("play"),
    FINISH("finish"),
    SKIP("skip"),
    LIKE("like"),
}

data class ListenEvent(
    val track: MusicTrack,
    val kind: ListenEventKind,
    /** 触发时的播放位置。只用于服务端排查误判，不参与计分。 */
    val positionMs: Long,
)

/**
 * 把「播放器状态流」压成「播放 / 听完 / 跳过」三种事件。
 *
 * 抽成纯类是为了能被单测钉死：这套判定错一次，榜单就会被刷歪，
 * 而且**从界面上完全看不出来** —— 只是排名慢慢变得不合理。
 *
 * 三条判定规则，每条都有反面案例：
 *
 * 1. **只报 play，不报"打开播放页"**。播放器连着但没起播时不该计分。
 * 2. **跳过要设下限**。3 秒内切走的，是"没在听"而不是"不爱听"，
 *    记成 skip 会让误触把人家的歌踩下去。
 * 3. **单曲循环要算新的一遍**。位置从尾部绕回开头时，上一遍要先结算，
 *    否则循环听一晚上只有第一次算数 —— 这恰恰是最该被计入的"真喜欢"。
 */
class ListenTracker(
    private val finishMs: Long = FINISH_MS,
    private val minFinishMs: Long = MIN_FINISH_MS,
    private val minSkipMs: Long = MIN_SKIP_MS,
) {

    private var current: MusicTrack? = null

    /** 本次会话播到过的最远处。用它而不是当前位置判"听完"，因为用户会往回拖。 */
    private var maxPositionMs = 0L

    private var playReported = false

    /**
     * 喂一个采样点，返回这一拍产生的事件（通常为空）。
     *
     * 调用频率跟着播放器的 350ms 心跳走，所以这里必须是无副作用的轻计算 ——
     * 不能在里面发网络请求，也不该持有 Context。
     */
    fun sample(track: MusicTrack?, positionMs: Long, isPlaying: Boolean): List<ListenEvent> {
        val out = ArrayList<ListenEvent>(2)
        val position = positionMs.coerceAtLeast(0L)

        if (track?.key != current?.key) {
            settle(out)
            current = track
            maxPositionMs = 0L
            playReported = false
        }
        val active = current ?: return out

        // 单曲循环绕回开头：上一遍结算掉，这一遍重新开始
        if (playReported && isRewindToHead(active, position)) {
            settle(out)
            current = active
            maxPositionMs = 0L
            playReported = false
        }

        if (isPlaying && !playReported) {
            playReported = true
            out += ListenEvent(active, ListenEventKind.PLAY, position)
        }
        if (position > maxPositionMs) {
            maxPositionMs = position
        }
        return out
    }

    /** 主动结束（退出播放器 / 解绑服务）时结算，别把最后一首漏掉。 */
    fun flush(): List<ListenEvent> {
        val out = ArrayList<ListenEvent>(1)
        settle(out)
        current = null
        maxPositionMs = 0L
        playReported = false
        return out
    }

    /** 结算当前这一遍：播够了算听完，播了一会儿算跳过，刚点开就不算。 */
    private fun settle(out: MutableList<ListenEvent>) {
        val track = current ?: return
        if (!playReported) return
        val played = maxPositionMs
        when {
            played >= thresholdOf(track.durationMs) ->
                out += ListenEvent(track, ListenEventKind.FINISH, played)

            played >= minSkipMs ->
                out += ListenEvent(track, ListenEventKind.SKIP, played)
        }
    }

    /**
     * "听完"的门槛：半首歌或 30 秒，取小的那个。
     *
     * 短歌不能被 30 秒卡死（30 秒的铃声永远听不完），长歌也不能要求听满一半 ——
     * 一首 6 分钟的歌，用户听到 3 分钟切走，主观上已经"听过了"。
     */
    private fun thresholdOf(durationMs: Long): Long {
        if (durationMs <= 0L) return finishMs
        val half = durationMs / 2
        return minOf(finishMs, maxOf(minFinishMs, half)).coerceAtMost(durationMs)
    }

    /**
     * 是不是从尾部绕回了开头。
     *
     * 必须两头都判：只看"位置很小"会把用户拖回开头也当成新的一遍，
     * 一次重听记成两次播放。要求"到过尾部"才认，是因为只有循环才会自己绕回去。
     */
    private fun isRewindToHead(track: MusicTrack, position: Long): Boolean {
        if (position > REWIND_HEAD_MS) return false
        if (maxPositionMs < REWIND_TAIL_MS) return false
        val duration = track.durationMs
        return duration <= 0L || maxPositionMs >= duration - REWIND_TAIL_MS
    }

    companion object {
        /** 30 秒：行业里"有效播放"的通行门槛。 */
        const val FINISH_MS = 30_000L

        /** 短歌的下限：再短也要听到一半才算。 */
        const val MIN_FINISH_MS = 10_000L

        /** 少于这个时长的切歌视为误触，不记跳过。 */
        const val MIN_SKIP_MS = 5_000L

        /** 绕回判定：位置回到 3 秒内，且此前播到了尾声 5 秒内。 */
        const val REWIND_HEAD_MS = 3_000L
        const val REWIND_TAIL_MS = 5_000L
    }
}
