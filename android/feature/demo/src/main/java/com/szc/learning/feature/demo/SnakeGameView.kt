package com.szc.learning.feature.demo

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * 贪吃蛇画布：棋盘、食物、蛇、遮罩、悬浮信息全部自绘。
 *
 * 两条关键设计：
 * 1. 棋盘底色铺满整屏，但**可玩网格让开系统栏**，否则食物会生成在状态栏
 *    下面，玩家看不见也吃不到。
 * 2. 悬浮信息（得分/最高分/提示）会在蛇头靠近时线性淡出，避免挡视线。
 *
 * 状态流转：READY →(轻点) RUNNING →(轻点) PAUSED →(轻点) RUNNING
 * 撞墙或咬到自己时进入 OVER，轻点重开。
 */
class SnakeGameView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    /** 游戏结束时回调最终得分。 */
    var onGameOver: ((score: Int) -> Unit)? = null

    /** 历史最高分，由外部（Activity + SharedPreferences）维护。 */
    var bestScore: Int = 0
        set(value) {
            field = value
            invalidate()
        }

    private enum class State { READY, RUNNING, PAUSED, OVER }

    private val density = resources.displayMetrics.density

    private val boardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = COLOR_BOARD }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COLOR_GRID
        style = Paint.Style.STROKE
        strokeWidth = density
    }
    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = COLOR_SNAKE_BODY }
    private val headPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = COLOR_SNAKE_HEAD }
    private val eyePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = COLOR_EYE }
    private val foodPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = COLOR_FOOD }
    private val dimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
    private val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = COLOR_PILL }
    private val scorePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COLOR_TEXT
        textSize = dp(13f)
        isFakeBoldText = true
        textAlign = Paint.Align.LEFT
    }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COLOR_TEXT_DIM
        textSize = dp(13f)
        textAlign = Paint.Align.CENTER
    }
    private val overlayTitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COLOR_TEXT
        textSize = dp(26f)
        isFakeBoldText = true
        textAlign = Paint.Align.CENTER
    }
    private val overlayBodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COLOR_TEXT_DIM
        textSize = dp(14f)
        textAlign = Paint.Align.CENTER
    }

    private val boardRect = RectF()
    private val tempRect = RectF()

    /** 每个悬浮胶囊的屏幕矩形，用于计算蛇头与它的距离。 */
    private val pillRects = mutableListOf<RectF>()

    private var cellSize = 0f
    private var originX = 0f
    private var originY = 0f
    private var rows = 0
    private var insetTop = 0
    private var insetBottom = 0

    private var rules: SnakeRules? = null
    private var state = State.READY

    /** 胶囊当前不透明度，向目标值缓动。 */
    private var pillAlpha = 1f

    private val stepHandler = Handler(Looper.getMainLooper())
    private val stepRunnable = Runnable { advanceFrame() }

    // ---------- 对外 API ----------

    /** 由 Activity 的 WindowInsets 回调注入系统栏高度。 */
    fun setContentInsets(top: Int, bottom: Int) {
        if (insetTop == top && insetBottom == bottom) return
        insetTop = top
        insetBottom = bottom
        updateGeometry(width, height)
    }

    /** 页面不可见时停表，避免后台空转。 */
    fun pauseIfRunning() {
        if (state == State.RUNNING) {
            state = State.PAUSED
            stepHandler.removeCallbacks(stepRunnable)
            invalidate()
        }
    }

    // ---------- 尺寸与几何 ----------

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateGeometry(w, h)
    }

    /**
     * 重算格子尺寸并重建棋盘。
     *
     * insets 到达时间晚于 onSizeChanged，所以两处都调用这里；
     * 只要几何真的变了就重建规则对象并回到 READY，防止残局错位。
     */
    private fun updateGeometry(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return

        val newCell = w.toFloat() / COLS
        val playableHeight = (h - insetTop - insetBottom).coerceAtLeast(1)
        val newRows = floor(playableHeight / newCell).toInt().coerceAtLeast(MIN_ROWS)

        val geometryUnchanged = newCell == cellSize && newRows == rows && rules != null

        cellSize = newCell
        rows = newRows
        originX = 0f
        originY = insetTop + (playableHeight - rows * newCell) / 2f
        boardRect.set(0f, 0f, w.toFloat(), h.toFloat())

        if (!geometryUnchanged) {
            rules = SnakeRules(COLS, rows).apply { start() }
            state = State.READY
            stepHandler.removeCallbacks(stepRunnable)
        }
        pillAlpha = 1f
        invalidate()
    }

    // ---------- 游戏循环 ----------

    private fun advanceFrame() {
        val game = rules ?: return
        if (state != State.RUNNING) return

        when (game.step()) {
            SnakeRules.Outcome.DIED -> {
                state = State.OVER
                invalidate()
                onGameOver?.invoke(game.score)
                return
            }
            SnakeRules.Outcome.ATE, SnakeRules.Outcome.MOVED -> Unit
        }

        invalidate()
        scheduleNextStep()
    }

    private fun scheduleNextStep() {
        stepHandler.removeCallbacks(stepRunnable)
        if (state == State.RUNNING) {
            stepHandler.postDelayed(stepRunnable, currentStepInterval())
        }
    }

    /** 初始 180ms，每吃 4 个食物快 10ms，下限 80ms。 */
    private fun currentStepInterval(): Long {
        val eaten = rules?.score ?: 0
        val speedUp = (eaten / FOOD_PER_SPEEDUP) * SPEEDUP_MILLIS
        return (BASE_INTERVAL_MILLIS - speedUp).coerceAtLeast(MIN_INTERVAL_MILLIS)
    }

    // ---------- 输入 ----------

    private var downX = 0f
    private var downY = 0f

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                return true
            }

            MotionEvent.ACTION_UP -> {
                val dx = event.x - downX
                val dy = event.y - downY
                val threshold = max(dp(24f), cellSize * 0.5f)

                if (abs(dx) < threshold && abs(dy) < threshold) {
                    handleTap()
                } else {
                    handleSwipe(dx, dy)
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun handleTap() {
        when (state) {
            State.READY -> {
                state = State.RUNNING
                invalidate()
                scheduleNextStep()
            }
            State.RUNNING -> {
                state = State.PAUSED
                stepHandler.removeCallbacks(stepRunnable)
                invalidate()
            }
            State.PAUSED -> {
                state = State.RUNNING
                invalidate()
                scheduleNextStep()
            }
            State.OVER -> {
                rules?.start()
                state = State.READY
                pillAlpha = 1f
                invalidate()
            }
        }
    }

    private fun handleSwipe(dx: Float, dy: Float) {
        if (state != State.RUNNING) return
        val direction = if (abs(dx) > abs(dy)) {
            if (dx > 0) SnakeRules.Dir.RIGHT else SnakeRules.Dir.LEFT
        } else {
            if (dy > 0) SnakeRules.Dir.DOWN else SnakeRules.Dir.UP
        }
        rules?.turn(direction)
    }

    // ---------- 绘制 ----------

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val game = rules ?: return

        // 1. 棋盘
        canvas.drawRect(boardRect, boardPaint)
        drawGrid(canvas)

        // 2. 食物
        if (game.food >= 0) {
            val (fx, fy) = cellCenter(game.food)
            canvas.drawCircle(fx, fy, cellSize * 0.32f, foodPaint)
        }

        // 3. 蛇
        drawSnake(canvas, game)

        // 4. 遮罩
        val dimAmount = when (state) {
            State.READY -> DIM_READY
            State.PAUSED -> DIM_PAUSED
            State.OVER -> DIM_OVER
            State.RUNNING -> 0
        }
        if (dimAmount > 0) {
            dimPaint.alpha = dimAmount
            canvas.drawRect(boardRect, dimPaint)
        }

        // 5. 悬浮信息（画在遮罩之上，否则暂停时会被压暗）
        drawHud(canvas, game)

        if (dimAmount > 0) {
            drawStateOverlay(canvas, game)
        }

        updatePillFade(game)
    }

    private fun drawGrid(canvas: Canvas) {
        for (col in 0..COLS) {
            val x = originX + col * cellSize
            canvas.drawLine(x, originY, x, originY + rows * cellSize, gridPaint)
        }
        for (row in 0..rows) {
            val y = originY + row * cellSize
            canvas.drawLine(originX, y, originX + COLS * cellSize, y, gridPaint)
        }
    }

    private fun drawSnake(canvas: Canvas, game: SnakeRules) {
        val radius = cellSize * 0.22f
        val inset = cellSize * 0.08f

        // 从尾部往前画，让头部压在最上层
        for (index in game.body.indices.reversed()) {
            val cell = game.body[index]
            val (cx, cy) = cellCenter(cell)
            val isHead = index == 0

            tempRect.set(
                cx - cellSize / 2 + inset,
                cy - cellSize / 2 + inset,
                cx + cellSize / 2 - inset,
                cy + cellSize / 2 - inset,
            )
            canvas.drawRoundRect(tempRect, radius, radius, if (isHead) headPaint else bodyPaint)

            if (isHead) drawEyes(canvas, cx, cy, game)
        }
    }

    private fun drawEyes(canvas: Canvas, cx: Float, cy: Float, game: SnakeRules) {
        val head = game.head
        val hasNext = game.body.size > 1
        val next = if (hasNext) game.body[1] else head

        // 朝向：头格与第二节的坐标差
        val dx = (head % COLS) - (next % COLS)
        val dy = (head / COLS) - (next / COLS)

        val eyeOffset = cellSize * 0.18f
        val forward = cellSize * 0.12f
        val radius = cellSize * 0.075f

        // 垂直于前进方向的两个眼睛
        val perpX = if (dx == 0) eyeOffset else 0f
        val perpY = if (dy == 0) eyeOffset else 0f
        val fx = cx + dx * forward
        val fy = cy + dy * forward

        canvas.drawCircle(fx + perpX, fy + perpY, radius, eyePaint)
        canvas.drawCircle(fx - perpX, fy - perpY, radius, eyePaint)
    }

    private fun drawHud(canvas: Canvas, game: SnakeRules) {
        pillRects.clear()

        val pillHeight = dp(28f)
        val padH = dp(12f)
        val margin = dp(12f)
        val top = insetTop + margin

        // 左上：当前得分
        val scoreText = "得分 ${game.score}"
        val scoreWidth = scorePaint.measureText(scoreText) + padH * 2
        val scoreRect = RectF(margin, top, margin + scoreWidth, top + pillHeight)
        drawPill(canvas, scoreRect, scoreText, scorePaint)
        pillRects.add(RectF(scoreRect))

        // 右上：历史最高
        val bestText = "最高 $bestScore"
        val bestWidth = scorePaint.measureText(bestText) + padH * 2
        val bestRect = RectF(
            width - margin - bestWidth,
            top,
            width - margin,
            top + pillHeight,
        )
        drawPill(canvas, bestRect, bestText, scorePaint)
        pillRects.add(RectF(bestRect))

        // 底部居中：操作提示，仅 READY 状态显示
        if (state == State.READY) {
            val hint = "滑动开始 · 轻点暂停"
            val hintWidth = hintPaint.measureText(hint) + padH * 2
            val bottom = height - insetBottom - margin
            val hintRect = RectF(
                (width - hintWidth) / 2,
                bottom - pillHeight,
                (width + hintWidth) / 2,
                bottom,
            )
            drawPill(canvas, hintRect, hint, hintPaint)
            pillRects.add(RectF(hintRect))
        }
    }

    private fun drawPill(canvas: Canvas, rect: RectF, text: String, paint: Paint) {
        // 只缩放 alpha 通道，保留胶囊底色原本的 0x2B 透明度
        val baseAlpha = (COLOR_PILL ushr 24) and 0xFF
        pillPaint.alpha = (baseAlpha * pillAlpha).toInt().coerceIn(0, 255)

        val radius = rect.height() / 2
        canvas.drawRoundRect(rect, radius, radius, pillPaint)

        val textAlpha = (255 * pillAlpha).toInt().coerceIn(0, 255)
        val previousAlpha = paint.alpha
        paint.alpha = textAlpha

        val baseline = rect.centerY() - (paint.descent() + paint.ascent()) / 2
        val x = if (paint.textAlign == Paint.Align.CENTER) rect.centerX() else rect.left + dp(12f)
        canvas.drawText(text, x, baseline, paint)

        paint.alpha = previousAlpha
    }

    private fun drawStateOverlay(canvas: Canvas, game: SnakeRules) {
        val centerX = width / 2f
        val centerY = originY + rows * cellSize / 2

        when (state) {
            State.READY -> {
                canvas.drawText("贪吃蛇", centerX, centerY - dp(6f), overlayTitlePaint)
                canvas.drawText(
                    "滑动换向 · 吃满加速",
                    centerX,
                    centerY + dp(24f),
                    overlayBodyPaint,
                )
            }

            State.PAUSED -> {
                canvas.drawText("已暂停", centerX, centerY - dp(6f), overlayTitlePaint)
                canvas.drawText("轻点继续", centerX, centerY + dp(24f), overlayBodyPaint)
            }

            State.OVER -> {
                canvas.drawText("游戏结束", centerX, centerY - dp(16f), overlayTitlePaint)
                canvas.drawText(
                    "本局得分 ${game.score} · 最高 $bestScore",
                    centerX,
                    centerY + dp(18f),
                    overlayBodyPaint,
                )
                canvas.drawText("轻点重新开始", centerX, centerY + dp(46f), overlayBodyPaint)
            }

            State.RUNNING -> Unit
        }
    }

    /**
     * 蛇头靠近胶囊时让胶囊淡出。
     *
     * 先取所有胶囊里「离蛇头最近」的那个距离，换算成格数：
     * 1.1 格内完全透明，3.4 格外完全不透明，中间线性。
     * 每帧向目标缓动 0.2，未收敛时继续请求下一帧，收敛后自然停帧。
     */
    private fun updatePillFade(game: SnakeRules) {
        val (hx, hy) = cellCenter(game.head)
        var nearest = Float.MAX_VALUE
        for (rect in pillRects) {
            val nx = hx.coerceIn(rect.left, rect.right)
            val ny = hy.coerceIn(rect.top, rect.bottom)
            nearest = min(nearest, hypot(hx - nx, hy - ny))
        }

        val distanceInCells = if (cellSize > 0) nearest / cellSize else Float.MAX_VALUE
        val target = ((distanceInCells - FADE_NEAR_CELLS) / (FADE_FAR_CELLS - FADE_NEAR_CELLS))
            .coerceIn(0f, 1f)

        val delta = target - pillAlpha
        if (abs(delta) < 0.01f) {
            pillAlpha = target
        } else {
            pillAlpha += delta * FADE_EASING
            postInvalidateOnAnimation()
        }
    }

    private fun cellCenter(cell: Int): Pair<Float, Float> {
        val col = cell % COLS
        val row = cell / COLS
        return (originX + (col + 0.5f) * cellSize) to (originY + (row + 0.5f) * cellSize)
    }

    private fun dp(value: Float): Float = value * density

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stepHandler.removeCallbacks(stepRunnable)
    }

    private companion object {
        const val COLS = 20
        const val MIN_ROWS = 8

        const val BASE_INTERVAL_MILLIS = 180L
        const val MIN_INTERVAL_MILLIS = 80L
        const val SPEEDUP_MILLIS = 10L
        const val FOOD_PER_SPEEDUP = 4

        const val FADE_NEAR_CELLS = 1.1f
        const val FADE_FAR_CELLS = 3.4f
        const val FADE_EASING = 0.2f

        const val DIM_READY = 0x66
        const val DIM_PAUSED = 0x99
        const val DIM_OVER = 0xAA

        val COLOR_BOARD = Color.parseColor("#0E1116")
        val COLOR_GRID = Color.parseColor("#1FFFFFFF")
        val COLOR_SNAKE_HEAD = Color.parseColor("#34D399")
        val COLOR_SNAKE_BODY = Color.parseColor("#059669")
        val COLOR_FOOD = Color.parseColor("#FB7185")
        val COLOR_EYE = Color.parseColor("#0E1116")
        val COLOR_PILL = Color.parseColor("#2BFFFFFF")
        val COLOR_TEXT = Color.parseColor("#F8FAFC")
        val COLOR_TEXT_DIM = Color.parseColor("#94A3B8")
    }
}
