package com.szc.learning.feature.demo

import com.szc.learning.feature.demo.SnakeRules.Dir
import com.szc.learning.feature.demo.SnakeRules.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 规则层单元测试。这些用例是行为的唯一契约，
 * 改动 [SnakeRules] 时必须全部通过。
 */
class SnakeRulesTest {

    private fun newGame(cols: Int = 10, rows: Int = 10): SnakeRules =
        SnakeRules(cols, rows).apply { start() }

    @Test
    fun start_hasThreeSegments_andFoodOffBody() {
        val game = newGame()
        assertEquals(3, game.body.size)
        assertEquals(0, game.score)
        assertTrue(game.alive)
        assertTrue(game.food >= 0)
        assertFalse(game.body.contains(game.food))
    }

    @Test
    fun step_movesHead_andKeepsLength() {
        val game = newGame()
        val headBefore = game.head
        val tailBefore = game.body.last()

        assertEquals(Outcome.MOVED, game.step())
        assertEquals(headBefore + 1, game.head)
        assertEquals(3, game.body.size)
        assertFalse(game.body.contains(tailBefore))
    }

    @Test
    fun step_ontoFood_scoresAndGrows() {
        val game = newGame()
        val target = game.head + 1
        game.placeFoodAt(target)

        assertEquals(Outcome.ATE, game.step())
        assertEquals(target, game.head)
        assertEquals(1, game.score)
        assertEquals(4, game.body.size)

        // 新食物必须落在空位上
        assertTrue(game.food >= 0)
        assertFalse(game.body.contains(game.food))
    }

    @Test
    fun step_intoWall_dies() {
        val game = newGame()
        repeat(4) { assertEquals(Outcome.MOVED, game.step()) }   // x: 5 -> 9
        assertEquals(Outcome.DIED, game.step())                  // 第 10 格出界
        assertFalse(game.alive)
        assertEquals(Outcome.DIED, game.step())                  // 死后不再移动
    }

    @Test
    fun step_intoOwnBody_dies() {
        val game = newGame()
        game.placeFoodAt(game.head + 1)
        assertEquals(Outcome.ATE, game.step())      // 长度 4
        game.placeFoodAt(game.head + 1)
        assertEquals(Outcome.ATE, game.step())      // 长度 5，蛇头 (7,5)

        game.turn(Dir.UP)
        assertEquals(Outcome.MOVED, game.step())    // (7,4)
        game.turn(Dir.LEFT)
        assertEquals(Outcome.MOVED, game.step())    // (6,4)
        game.turn(Dir.DOWN)
        assertEquals(Outcome.DIED, game.step())     // (6,5) 撞到自己的身体
        assertFalse(game.alive)
    }

    @Test
    fun turn_ignoresReverseAndDuplicate() {
        val game = newGame()
        val headBefore = game.head

        game.turn(Dir.LEFT)   // 与当前方向相反，应被丢弃
        game.turn(Dir.RIGHT)  // 与当前方向相同，应被丢弃
        assertEquals(Outcome.MOVED, game.step())

        assertEquals(headBefore + 1, game.head)
    }

    @Test
    fun turn_isQueued_soQuickDoubleSwipeWorks() {
        val game = newGame()
        val headBefore = game.head

        game.turn(Dir.DOWN)
        game.turn(Dir.LEFT)   // 连按两次：先向下，再向左

        game.step()
        assertEquals(headBefore + 10, game.head)          // 下移一行
        game.step()
        assertEquals(headBefore + 10 - 1, game.head)      // 再左移一格
    }

    @Test
    fun deadGame_ignoresTurn() {
        val game = newGame()
        repeat(5) { game.step() }
        assertFalse(game.alive)

        val headAfterDeath = game.head
        game.turn(Dir.UP)
        assertEquals(Outcome.DIED, game.step())
        assertEquals(headAfterDeath, game.head)
    }
}
