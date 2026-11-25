package com.szc.learning.music.data.room

import kotlin.math.abs

/**
 * 漂移纠正的决策规则。
 *
 * 单独抽出来是因为这是整个同步里**唯一会直接影响听感**的一段：
 * 决策错了，用户听到的就是"每隔两秒断一下"或者"一直在微微变调"。
 * 做成纯函数才能用单元测试把阈值钉死，不必每次都上两台设备手动听。
 *
 * ## 三档阈值的由来
 *
 * - **400ms 以上**：变速追不动。4% 的速度差追 400ms 要 10 秒，
 *   中间一直是两个声部，不如直接跳过去，一次断音换十秒的错位
 * - **80~400ms**：变速追。4% 人耳几乎听不出，追 200ms 只要 5 秒
 * - **80ms 以内**：不动。这一档是"已经对齐了"，
 *   继续微调反而会让两边的相位一直轻微摆动
 *
 * 80ms 这个下界来自实际听感：两台设备同时外放同一首歌，
 * 100ms 内听起来是"两个音箱"，200ms 开始像回声。
 */
internal object DriftPolicy {

    enum class Action {
        /** 偏差太大，直接跳。 */
        SEEK,

        /** 落后了，加速追。 */
        SPEED_UP,

        /** 超前了，减速等。 */
        SLOW_DOWN,

        /** 已经在容差内。 */
        HOLD,
    }

    const val HARD_SEEK_MS = 400L
    const val SOFT_BAND_MS = 80L

    /** 微调幅度。再大能听出"赶"，再小追不上晶振自身的漂移。 */
    const val FAST_SPEED = 1.04f
    const val SLOW_SPEED = 0.96f

    fun decide(driftMs: Long): Action = when {
        abs(driftMs) > HARD_SEEK_MS -> Action.SEEK
        driftMs > SOFT_BAND_MS -> Action.SPEED_UP
        driftMs < -SOFT_BAND_MS -> Action.SLOW_DOWN
        else -> Action.HOLD
    }

    /**
     * 按房主时钟推算目标位置。
     *
     * [hostNowMs] 是本地时钟换算到房主时钟之后的值，
     * 所以这个式子可以和 [RoomMessage.anchorHostMs] 直接相减。
     */
    fun targetPosition(anchorPosMs: Long, anchorHostMs: Long, hostNowMs: Long): Long =
        (anchorPosMs + (hostNowMs - anchorHostMs)).coerceAtLeast(0L)
}
