package com.szc.learning.music.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * 队列删除区间的表征测试。
 *
 * 逐条删除改成按区间删除后，索引合并必须与逐条删除的结果严格等价：
 * 区间升序、互不相邻、并集等于输入集合。
 */
class QueueEditTest {

    @Test
    fun `empty input yields no ranges`() {
        assertTrue(QueueEdit.ranges(emptyList()).isEmpty())
    }

    @Test
    fun `consecutive indices merge into one range`() {
        assertEquals(listOf(1..3), QueueEdit.ranges(listOf(3, 1, 2)))
    }

    @Test
    fun `gaps split ranges`() {
        assertEquals(listOf(1..3, 7..7, 9..10), QueueEdit.ranges(listOf(1, 2, 3, 7, 9, 10)))
    }

    @Test
    fun `duplicates do not produce duplicates`() {
        assertEquals(listOf(4..5), QueueEdit.ranges(listOf(4, 4, 5, 5)))
    }

    /** 随机输入：区间互不相邻、升序，展开后与去重排序后的输入一致。 */
    @Test
    fun `ranges reconstruct the input set`() {
        val random = Random(20261125)
        repeat(200) {
            val size = random.nextInt(0, 500)
            val input = List(size) { random.nextInt(0, 1000) }
            val ranges = QueueEdit.ranges(input)

            val flattened = ranges.flatMap { range -> range.toList() }
            assertEquals(input.distinct().sorted(), flattened)
            ranges.zipWithNext().forEach { (a, b) ->
                assertTrue("ranges must be ascending and non-adjacent: $a / $b", b.first > a.last + 1)
            }
        }
    }
}
