package com.szc.learning.feature.demo

/**
 * 贪吃蛇纯规则实现，零 Android 依赖，可直接单元测试。
 *
 * 棋盘用一维下标表示：`index = row * cols + col`。
 * 蛇身 [body] 以头部为第 0 个元素，尾部为最后一个。
 *
 * 规则约定：
 * - 撞墙即死，不穿墙；
 * - 不吃到食物时尾格会让位，因此「头进入当前尾格」不算碰撞；
 * - 吃到食物时全身参与碰撞判定；
 * - 反向与重复方向被丢弃，连续转向最多排队 2 个。
 */
class SnakeRules(
    val cols: Int,
    val rows: Int,
) {

    enum class Dir { UP, DOWN, LEFT, RIGHT }

    enum class Outcome { MOVED, ATE, DIED }

    private val _body = ArrayDeque<Int>()

    /** 蛇身，头部在前。外部只读。 */
    val body: List<Int> get() = _body

    /** 蛇头所在格下标。 */
    val head: Int get() = _body.first()

    var score: Int = 0
        private set

    var alive: Boolean = true
        private set

    /** 食物所在格下标，无空位时为 -1。 */
    var food: Int = -1
        private set

    private var dir: Dir = Dir.RIGHT
    private val pendingDirs = ArrayDeque<Dir>()

    /** 重置到开局状态：长度 3，居中朝右。 */
    fun start() {
        _body.clear()
        alive = true
        score = 0
        dir = Dir.RIGHT
        pendingDirs.clear()

        val centerX = cols / 2
        val centerY = rows / 2
        val headIndex = centerY * cols + centerX
        _body.addLast(headIndex)
        _body.addLast(headIndex - 1)
        _body.addLast(headIndex - 2)

        food = randomFreeCell()
    }

    /** 推进一格。返回本步结果，死亡后继续调用仍返回 [Outcome.DIED] 且不再移动。 */
    fun step(): Outcome {
        if (!alive) return Outcome.DIED

        pendingDirs.removeFirstOrNull()?.let { dir = it }

        val headIndex = _body.first()
        val x = headIndex % cols
        val y = headIndex / cols
        val (nx, ny) = when (dir) {
            Dir.UP -> x to y - 1
            Dir.DOWN -> x to y + 1
            Dir.LEFT -> x - 1 to y
            Dir.RIGHT -> x + 1 to y
        }

        if (nx !in 0 until cols || ny !in 0 until rows) {
            alive = false
            return Outcome.DIED
        }

        val next = ny * cols + nx
        val willEat = next == food

        // 不吃食物时尾巴会让位，只有「除尾之外」的身体格才算碰撞
        val occupied = if (willEat) _body else _body.subList(0, _body.size - 1)
        if (occupied.contains(next)) {
            alive = false
            return Outcome.DIED
        }

        _body.addFirst(next)

        return if (willEat) {
            score++
            food = randomFreeCell()
            Outcome.ATE
        } else {
            _body.removeLast()
            Outcome.MOVED
        }
    }

    /**
     * 请求转向。反向、与当前方向重复、排队已满时丢弃该输入。
     *
     * 排队机制是为了让快速连续滑动不丢指令：
     * 例如先向下再向左，下一格先向下，再下一格向左。
     */
    fun turn(direction: Dir) {
        if (!alive) return

        val effective = pendingDirs.lastOrNull() ?: dir
        if (direction == effective || direction.isOppositeOf(effective)) return
        if (pendingDirs.size >= MAX_PENDING_DIRS) return

        pendingDirs.addLast(direction)
    }

    /** 测试与调试用：把食物放到指定格。 */
    fun placeFoodAt(cell: Int) {
        food = cell
    }

    private fun randomFreeCell(): Int {
        val occupied = _body.toHashSet()
        val free = ArrayList<Int>(cols * rows - occupied.size)
        for (cell in 0 until cols * rows) {
            if (cell !in occupied) free.add(cell)
        }
        return if (free.isEmpty()) -1 else free.random()
    }

    private fun Dir.isOppositeOf(other: Dir): Boolean = when (this) {
        Dir.UP -> other == Dir.DOWN
        Dir.DOWN -> other == Dir.UP
        Dir.LEFT -> other == Dir.RIGHT
        Dir.RIGHT -> other == Dir.LEFT
    }

    private companion object {
        const val MAX_PENDING_DIRS = 2
    }
}
