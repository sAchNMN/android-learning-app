package com.szc.learning.music.data

import com.szc.learning.core.common.music.MusicTrack
import com.szc.learning.core.common.music.TrackSource
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 队列拖动在 feed 上的落点。
 *
 * 这类错误界面上看不出来：拖动看起来生效了，翻页却翻到别的歌，
 * 或者下一次拖同一行时整体差一首 —— 只能靠测试钉住方向语义。
 */
class QueueOrderTest {

    private fun track(id: String) = MusicTrack(
        id = id,
        title = id,
        artist = "歌手",
        album = "",
        durationMs = 200_000,
        uri = "https://example.com/$id.mp3",
        source = TrackSource.ONLINE,
        sourceKey = "netease",
    )

    private fun feed(vararg ids: String) = ids.map(::track)

    /** 真实 key 形如 `ONLINE:netease:A`，直接用 id 拼，免得被前缀绊住。 */
    private fun key(id: String) = track(id).key

    private fun ids(list: List<MusicTrack>) = list.map { it.id }

    @Test
    fun `往后拖插在锚点之后`() {
        // C 拖到 E 的位置：锚点是 E，插在它后面
        val result = QueueOrder.move(feed("A", "B", "C", "D", "E", "F"), key("C"), key("E"), true)
        assertEquals(listOf("A", "B", "D", "E", "C", "F"), ids(result))
    }

    @Test
    fun `往前拖插在锚点之前`() {
        // F 拖到 B 的位置：锚点是 B，插在它前面
        val result = QueueOrder.move(feed("A", "B", "C", "D", "E", "F"), key("F"), key("B"), false)
        assertEquals(listOf("A", "F", "B", "C", "D", "E"), ids(result))
    }

    @Test
    fun `拖到队尾`() {
        val result = QueueOrder.move(feed("A", "B", "C"), key("A"), key("C"), true)
        assertEquals(listOf("B", "C", "A"), ids(result))
    }

    @Test
    fun `拖到队首`() {
        val result = QueueOrder.move(feed("A", "B", "C"), key("C"), key("A"), false)
        assertEquals(listOf("C", "A", "B"), ids(result))
    }

    @Test
    fun `feed 里有未入队的曲目时原地不动`() {
        // X 没换到地址，不在播放器队列里；C 从 3 拖到 E 之后，X 应该还在原位
        val result = QueueOrder.move(
            feed("A", "B", "X", "C", "D", "E", "F"),
            key("C"),
            key("E"),
            true,
        )
        assertEquals(listOf("A", "B", "X", "D", "E", "C", "F"), ids(result))
    }

    @Test
    fun `锚点不在 feed 里时退回末尾`() {
        val result = QueueOrder.move(feed("A", "B", "C"), key("A"), key("不存在"), true)
        assertEquals(listOf("B", "C", "A"), ids(result))
    }

    @Test
    fun `找不到被拖的曲目时原样返回`() {
        val original = feed("A", "B", "C")
        val result = QueueOrder.move(original, key("不存在"), key("B"), true)
        assertEquals(ids(original), ids(result))
    }

    @Test
    fun `相邻互换不会丢歌`() {
        val result = QueueOrder.move(feed("A", "B"), key("B"), key("A"), false)
        assertEquals(listOf("B", "A"), ids(result))
        assertEquals(2, result.size)
    }
}
