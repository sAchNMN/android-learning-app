package com.szc.learning.music.data.room

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 时钟偏移估计。
 *
 * 用例刻意构造了"去回不对称"的情形：真实网络里回程往往比去程更慢
 * （上行排队更多），这时候取所有样本的平均会把 offset 拉偏，
 * 只有取 RTT 最小的那个才接近真值 —— 这是 NTP 的核心取舍，值得钉住。
 *
 * 用例里的数字都按"房主时钟比本地快 4000ms"来构造：
 * 本地 t1 发出，房主在 t1 + 去程 + 4000 收到。
 */
class ClockSyncTest {

    @Test
    fun `完全对称时 offset 精确`() {
        val clock = ClockSync()
        // 本地 1000 发出，去程 10ms → 房主时钟 5010，立刻回，回程 10ms → 本地 1020
        clock.onAck(t1 = 1_000, t2 = 5_010, t3 = 5_010, t4 = 1_020)
        assertEquals(4_000L, clock.offset)
        assertEquals(20L, clock.bestRtt)
        assertEquals(10L, clock.oneWayMs)
        assertTrue(clock.synced)
    }

    @Test
    fun `取最小 RTT 的样本而不是平均`() {
        val clock = ClockSync()
        // 干净样本：往返各 10ms，offset 测得 4000
        clock.onAck(t1 = 1_000, t2 = 5_010, t3 = 5_010, t4 = 1_020)
        // 脏样本：回程多排了 300ms，offset 被拉偏到 3850，RTT 也涨到 320
        clock.onAck(t1 = 20_000, t2 = 24_010, t3 = 24_010, t4 = 20_320)

        // 必须仍是干净样本的值：平均会得到 3925，差 75ms 已经能听出来
        assertEquals(4_000L, clock.offset)
        assertEquals(20L, clock.bestRtt)
    }

    @Test
    fun `RTT 为负的样本被丢弃`() {
        val clock = ClockSync()
        // t3 比 t2 晚了 3 秒，但回程只花了 2 秒 —— 算出来 rtt = -1000，物理上不可能
        clock.onAck(t1 = 1_000, t2 = 2_000, t3 = 5_000, t4 = 3_000)
        assertFalse(clock.synced)
        assertEquals(0L, clock.offset)
    }

    @Test
    fun `RTT 超过三秒的样本被丢弃`() {
        val clock = ClockSync()
        clock.onAck(t1 = 0, t2 = 100, t3 = 100, t4 = 10_000)
        assertFalse(clock.synced)
    }

    @Test
    fun `重置后回到未同步状态`() {
        val clock = ClockSync()
        clock.onAck(t1 = 1_000, t2 = 5_010, t3 = 5_010, t4 = 1_020)
        assertTrue(clock.synced)

        clock.reset()
        assertFalse(clock.synced)
        assertEquals(0L, clock.offset)
        assertEquals(-1L, clock.oneWayMs)
    }

    @Test
    fun `窗口只保留最近八个样本`() {
        val clock = ClockSync()
        // 先塞一个极干净的样本（RTT 20ms）
        clock.onAck(t1 = 0, t2 = 4_010, t3 = 4_010, t4 = 20)
        assertEquals(20L, clock.bestRtt)

        // 再塞 9 个更差的（RTT 500ms，仍在 3 秒上限内）：干净样本会被挤出 8 格窗口
        repeat(9) { index ->
            val base = 100_000L + index * 10_000L
            clock.onAck(t1 = base, t2 = base + 250, t3 = base + 250, t4 = base + 500)
        }
        assertEquals(500L, clock.bestRtt)
    }
}
