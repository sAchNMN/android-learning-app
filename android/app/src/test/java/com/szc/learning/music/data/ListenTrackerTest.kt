package com.szc.learning.music.data

import com.szc.learning.core.common.music.MusicTrack
import com.szc.learning.core.common.music.TrackSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 埋点判定的边界测试。
 *
 * 这类错误有一个共同点：**界面上完全看不出来**。榜单只会慢慢变得不合理，
 * 而那时已经很难回头判断是哪条规则错了。所以每条规则都用测试钉死。
 */
class ListenTrackerTest {

    private fun track(id: String = "1", durationMs: Long = 200_000) = MusicTrack(
        id = id,
        title = "晴天",
        artist = "周杰伦",
        album = "叶惠美",
        durationMs = durationMs,
        uri = "",
        source = TrackSource.ONLINE,
        sourceKey = "netease",
    )

    @Test
    fun `起播只报一次 play`() {
        val tracker = ListenTracker()
        val first = tracker.sample(track(), positionMs = 0, isPlaying = true)
        assertEquals(listOf(ListenEventKind.PLAY), first.map { it.kind })

        // 之后每一拍都还在播，但不能再报 play，否则热度会被心跳刷满
        repeat(10) { index ->
            val next = tracker.sample(track(), 350L * (index + 1), isPlaying = true)
            assertTrue(next.isEmpty())
        }
    }

    @Test
    fun `播够三十秒切歌算听完`() {
        val tracker = ListenTracker()
        tracker.sample(track("1"), 0, true)
        tracker.sample(track("1"), 31_000, true)

        val events = tracker.sample(track("2"), 0, true)
        assertEquals(listOf(ListenEventKind.FINISH, ListenEventKind.PLAY), events.map { it.kind })
    }

    @Test
    fun `听了一会儿就切走算跳过`() {
        val tracker = ListenTracker()
        tracker.sample(track("1"), 0, true)
        tracker.sample(track("1"), 8_000, true)

        val events = tracker.sample(track("2"), 0, true)
        assertEquals(listOf(ListenEventKind.SKIP, ListenEventKind.PLAY), events.map { it.kind })
    }

    @Test
    fun `秒切不算跳过`() {
        val tracker = ListenTracker()
        tracker.sample(track("1"), 0, true)
        tracker.sample(track("1"), 2_000, true)

        // 两秒就切走多半是误触或"下一首"找歌，记成跳过会平白把人家踩下去
        val events = tracker.sample(track("2"), 0, true)
        assertEquals(listOf(ListenEventKind.PLAY), events.map { it.kind })
    }

    @Test
    fun `往回拖进度不算新的一遍`() {
        val tracker = ListenTracker()
        tracker.sample(track(), 0, true)
        tracker.sample(track(), 60_000, true)

        val seekBack = tracker.sample(track(), 5_000, true)
        assertTrue(seekBack.isEmpty())

        // 拖回开头不该多记一次 play：用户是在重听，不是重新点开
        val plays = tracker.sample(track(), 6_000, true)
        assertTrue(plays.isEmpty())
    }

    @Test
    fun `单曲循环绕回开头算新的一遍`() {
        val tracker = ListenTracker()
        val song = track(durationMs = 60_000)
        tracker.sample(song, 0, true)
        tracker.sample(song, 58_000, true)

        // 位置从尾部跳回开头：上一遍先结算，这一遍重新计
        val events = tracker.sample(song, 1_000, true)
        assertEquals(listOf(ListenEventKind.FINISH, ListenEventKind.PLAY), events.map { it.kind })
    }

    @Test
    fun `短歌按一半算听完`() {
        // 20 秒的歌，听完门槛是 10 秒而不是 30 秒 —— 否则铃声永远"听不完"
        val tracker = ListenTracker()
        val short = track(durationMs = 20_000)
        tracker.sample(short, 0, true)
        tracker.sample(short, 10_500, true)

        val events = tracker.sample(track("2"), 0, true)
        assertEquals(listOf(ListenEventKind.FINISH, ListenEventKind.PLAY), events.map { it.kind })
    }

    @Test
    fun `暂停不结算`() {
        val tracker = ListenTracker()
        tracker.sample(track(), 0, true)
        tracker.sample(track(), 10_000, false)
        assertTrue(tracker.sample(track(), 10_000, false).isEmpty())

        // 恢复播放不该重报 play
        assertTrue(tracker.sample(track(), 10_000, true).isEmpty())
    }

    @Test
    fun `停止播放会结算当前这一遍`() {
        val tracker = ListenTracker()
        tracker.sample(track(), 0, true)
        tracker.sample(track(), 20_000, true)

        val events = tracker.sample(null, 0, false)
        assertEquals(listOf(ListenEventKind.SKIP), events.map { it.kind })
    }

    @Test
    fun `没起播的曲目不产生任何事件`() {
        val tracker = ListenTracker()
        // 播放器连着但是没播（比如刚 prepare 完）：不该计分，也不该算跳过
        val events = tracker.sample(track(), 0, isPlaying = false)
        assertTrue(events.isEmpty())
        assertTrue(tracker.flush().isEmpty())
    }

    @Test
    fun `flush 会结算未结束的一遍`() {
        val tracker = ListenTracker()
        tracker.sample(track(), 0, true)
        tracker.sample(track(), 40_000, true)

        val events = tracker.flush()
        assertEquals(listOf(ListenEventKind.FINISH), events.map { it.kind })
    }
}
