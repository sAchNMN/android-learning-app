package com.szc.learning.music.data.room

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 纠偏阈值的边界测试。
 *
 * 这些数字不是随手定的，改任何一档都会直接变成"听得出来"的问题，
 * 所以用测试钉死，避免以后调参时不小心把 SEEK 档缩到 100ms
 * （那样每两秒断一次音，用户会以为播放器坏了）。
 */
class DriftPolicyTest {

    @Test
    fun `超过硬阈值直接跳`() {
        assertEquals(DriftPolicy.Action.SEEK, DriftPolicy.decide(401))
        assertEquals(DriftPolicy.Action.SEEK, DriftPolicy.decide(-401))
        assertEquals(DriftPolicy.Action.SEEK, DriftPolicy.decide(5_000))
    }

    @Test
    fun `恰好等于硬阈值仍然变速追`() {
        // 边界取开区间：400 还能追上（10 秒），没必要断音
        assertEquals(DriftPolicy.Action.SPEED_UP, DriftPolicy.decide(400))
        assertEquals(DriftPolicy.Action.SLOW_DOWN, DriftPolicy.decide(-400))
    }

    @Test
    fun `中间档变速`() {
        assertEquals(DriftPolicy.Action.SPEED_UP, DriftPolicy.decide(81))
        assertEquals(DriftPolicy.Action.SPEED_UP, DriftPolicy.decide(300))
        assertEquals(DriftPolicy.Action.SLOW_DOWN, DriftPolicy.decide(-81))
        assertEquals(DriftPolicy.Action.SLOW_DOWN, DriftPolicy.decide(-300))
    }

    @Test
    fun `软阈值以内什么都不做`() {
        assertEquals(DriftPolicy.Action.HOLD, DriftPolicy.decide(0))
        assertEquals(DriftPolicy.Action.HOLD, DriftPolicy.decide(80))
        assertEquals(DriftPolicy.Action.HOLD, DriftPolicy.decide(-80))
        assertEquals(DriftPolicy.Action.HOLD, DriftPolicy.decide(79))
    }

    @Test
    fun `目标位置按房主时钟线性外推`() {
        // 锚点：房主时钟 5000ms 时播到 1000ms；现在房主时钟 6000ms → 应该到 2000ms
        assertEquals(
            2_000L,
            DriftPolicy.targetPosition(anchorPosMs = 1_000, anchorHostMs = 5_000, hostNowMs = 6_000),
        )
    }

    @Test
    fun `目标位置不会算成负数`() {
        // 消息比本地时钟"更新"（乱序或时钟刚重置）：不能返回负进度，ExoPlayer 会抛异常
        assertEquals(
            0L,
            DriftPolicy.targetPosition(anchorPosMs = 0, anchorHostMs = 5_000, hostNowMs = 4_000),
        )
        assertEquals(
            0L,
            DriftPolicy.targetPosition(anchorPosMs = 100, anchorHostMs = 5_000, hostNowMs = 0),
        )
    }
}
