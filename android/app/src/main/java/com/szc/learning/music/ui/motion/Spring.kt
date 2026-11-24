package com.szc.learning.music.ui.motion

import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import android.content.Context
import android.os.Build
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import com.szc.learning.music.ui.widget.dp
import java.util.WeakHashMap
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * 全项目共用的弹性动效。
 *
 * 数学部分照 Apple「duration + bounce」的弹簧参数写法：
 * `ω0 = 2π / duration`，`ζ = 1 - bounce`，解析解而不是逐帧欧拉积分。
 * 好处是掉帧 / 120Hz / 60Hz 下曲线一致，过冲幅度也可预测。
 *
 * 所有入口都尊重系统的「动画时长缩放」：设为 0 时直接落到终态，
 * 不再播放弹性，这是无障碍里 Reduce Motion 在 Android 上的等价物。
 */
object Motion {

    const val SMOOTH = 0.0f
    const val SNAPPY = 0.15f
    const val BOUNCY = 0.3f

    /** 系统动画缩放为 0 或 Animator 被全局停用时返回 true。 */
    fun animationsDisabled(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !ValueAnimator.areAnimatorsEnabled()
        ) {
            return true
        }
        val scale = runCatching {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f,
            )
        }.getOrDefault(1f)
        return scale <= 0.01f
    }

    fun springInterpolator(
        durationSeconds: Float = 0.5f,
        bounce: Float = 0.2f,
    ): TimeInterpolator = SpringInterpolator(durationSeconds, bounce)

    internal fun solve(w0: Float, zeta: Float, x0: Float, v0: Float, t: Float): FloatArray {
        if (zeta < 1f) {
            val a = zeta * w0
            val wd = w0 * sqrt(1f - zeta * zeta)
            val s = kotlin.math.sin(wd * t)
            val c = kotlin.math.cos(wd * t)
            val e = exp(-a * t)
            val b = (v0 + a * x0) / wd
            return floatArrayOf(
                e * (x0 * c + b * s),
                e * (v0 * c - (x0 * wd + a * b) * s),
            )
        }
        if (abs(zeta - 1f) < 0.0001f) {
            val e = exp(-w0 * t)
            val b = v0 + w0 * x0
            return floatArrayOf(
                e * (x0 + b * t),
                e * (v0 - w0 * b * t),
            )
        }
        val q = sqrt(zeta * zeta - 1f)
        val r1 = -w0 * (zeta - q)
        val r2 = -w0 * (zeta + q)
        val c2 = (v0 - r1 * x0) / (r2 - r1)
        val c1 = x0 - c2
        val e1 = exp(r1 * t)
        val e2 = exp(r2 * t)
        return floatArrayOf(
            c1 * e1 + c2 * e2,
            c1 * r1 * e1 + c2 * r2 * e2,
        )
    }
}

/**
 * 把弹簧解映射成 0..1 的插值器，允许轻微超过 1（这就是"弹"的来源）。
 * `duration` 是感知上的落定时长，不是严格的收敛时间。
 */
class SpringInterpolator(
    durationSeconds: Float = 0.5f,
    bounce: Float = 0.2f,
) : TimeInterpolator {

    private val duration = durationSeconds.coerceIn(0.08f, 3f)
    private val w0 = (2.0 * PI / duration).toFloat()
    private val zeta = 1f - bounce.coerceIn(-0.95f, 0.95f)

    override fun getInterpolation(input: Float): Float {
        if (input <= 0f) return 0f
        if (input >= 1f) return 1f
        val x = Motion.solve(w0, zeta, -1f, 0f, input * duration)[0]
        return 1f + x
    }
}

/**
 * 可重定向的弹簧标量：保留当前速度，目标可以中途改。
 *
 * 底部导航的胶囊滑动、玻璃镜头状态都靠它；一帧里推进 dt 秒，
 * 和 `Choreographer` 配合使用。到达静止后 [update] 返回 false，调用方停止排帧。
 */
class SpringValue(
    initial: Float,
    private var durationSeconds: Float = 0.5f,
    private var bounce: Float = 0.2f,
) {
    private val w0: Float
        get() = (2.0 * PI / durationSeconds.coerceIn(0.08f, 3f)).toFloat()

    private val zeta: Float
        get() = 1f - bounce.coerceIn(-0.95f, 0.95f)

    var value: Float = initial
        private set

    var velocity: Float = 0f
        private set

    var targetValue: Float = initial
        private set

    var isAtRest: Boolean = true
        private set

    /** 立即落到某个值，不播放动画。 */
    fun snapTo(value: Float) {
        this.value = value
        targetValue = value
        velocity = 0f
        isAtRest = true
    }

    /**
     * 改目标。`launch = true` 时给一个指向目标的初速度，
     * 对应 Apple 说的 "surface launches"：第一帧就全速，而不是从 0 缓入。
     */
    fun animateTo(target: Float, launch: Boolean = false) {
        targetValue = target
        if (launch) {
            val speed = (target - value) * w0
            if (abs(velocity) < abs(speed)) velocity = speed
        }
        isAtRest = abs(targetValue - value) < EPS && abs(velocity) < EPS
        if (isAtRest) {
            value = targetValue
            velocity = 0f
        }
    }

    /** 推进 [dtSeconds]；仍在动返回 true。 */
    fun update(dtSeconds: Float): Boolean {
        if (isAtRest) return false
        val dt = dtSeconds.coerceIn(0f, 1f / 30f)
        if (dt <= 0f) return true
        val solved = Motion.solve(
            w0,
            zeta,
            value - targetValue,
            velocity,
            dt,
        )
        value = targetValue + solved[0]
        velocity = solved[1]
        if (abs(value - targetValue) < EPS && abs(velocity) < EPS) {
            value = targetValue
            velocity = 0f
            isAtRest = true
            return false
        }
        return true
    }

    private companion object {
        const val EPS = 0.0015f
    }
}

// ------------------------------------------------------------------ View 快捷方法

private val runningAnimators = WeakHashMap<View, ValueAnimator>()

private fun View.trackAnimator(animator: ValueAnimator) {
    runningAnimators.remove(this)?.cancel()
    runningAnimators[this] = animator
    animator.addListener(object : android.animation.AnimatorListenerAdapter() {
        override fun onAnimationEnd(animation: android.animation.Animator) {
            if (runningAnimators[this@trackAnimator] === animation) {
                runningAnimators.remove(this@trackAnimator)
            }
        }
    })
}

/**
 * 弹性缩放。从当前 scale 出发，所以可以中断/反向（按下去又滑走也不会跳）。
 */
fun View.springScaleTo(
    target: Float,
    durationSeconds: Float = 0.5f,
    bounce: Float = Motion.SNAPPY,
) {
    if (Motion.animationsDisabled(context)) {
        scaleX = target
        scaleY = target
        return
    }
    val startX = scaleX
    val startY = scaleY
    if (abs(startX - target) < 0.0005f && abs(startY - target) < 0.0005f) return
    val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = (durationSeconds * 1000f).toLong().coerceAtLeast(1L)
        interpolator = SpringInterpolator(durationSeconds, bounce)
        addUpdateListener {
            val t = it.animatedValue as Float
            scaleX = startX + (target - startX) * t
            scaleY = startY + (target - startY) * t
        }
    }
    trackAnimator(animator)
    animator.start()
}

/**
 * 果冻按压：按下时纵向比横向多压一点（squash），松手用带过冲的弹簧弹回。
 * 等比缩放看起来只是"变小"，非等比才有"捏了一下又弹回来"的手感 ——
 * 这是液态玻璃里最便宜也最有效的物理感来源。
 */
fun View.installSpringJelly(
    pressedScale: Float = 0.95f,
    squash: Float = 0.02f,
    restingScale: Float = 1f,
) {
    var pressed = false

    fun animateTo(target: Float, squashNow: Float, durationSeconds: Float, bounce: Float) {
        if (Motion.animationsDisabled(context)) {
            scaleX = target
            scaleY = target
            return
        }
        val startX = scaleX
        val startY = scaleY
        val endX = target
        val endY = target * (1f - squashNow)
        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = (durationSeconds * 1000f).toLong().coerceAtLeast(1L)
            interpolator = SpringInterpolator(durationSeconds, bounce)
            addUpdateListener {
                val t = it.animatedValue as Float
                scaleX = startX + (endX - startX) * t
                scaleY = startY + (endY - startY) * t
            }
        }
        trackAnimator(animator)
        animator.start()
    }

    setOnTouchListener { _, event ->
        when (event.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> {
                pressed = true
                animateTo(pressedScale, squash, 0.2f, Motion.SMOOTH)
            }

            android.view.MotionEvent.ACTION_UP,
            android.view.MotionEvent.ACTION_CANCEL,
            -> if (pressed) {
                pressed = false
                animateTo(restingScale, 0f, 0.55f, 0.45f)
            }
        }
        false
    }
}

/**
 * 按压缩放：按下快速收紧（无回弹），松手用带 bounce 的弹簧回位。
 * 返回 false 不会吃掉 click。
 */
fun View.installSpringPress(
    pressedScale: Float = 0.94f,
    restingScale: Float = 1f,
) {
    var pressed = false
    setOnTouchListener { _, event ->
        when (event.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> {
                pressed = true
                springScaleTo(pressedScale, 0.22f, Motion.SMOOTH)
            }

            android.view.MotionEvent.ACTION_UP,
            android.view.MotionEvent.ACTION_CANCEL,
            -> if (pressed) {
                pressed = false
                springScaleTo(restingScale, 0.5f, 0.35f)
            }
        }
        false
    }
}

/** 点赞/收藏的"心跳"：先缩小，再带一次明显过冲弹回。 */
fun View.springPop(
    startScale: Float = 0.68f,
    durationSeconds: Float = 0.62f,
    bounce: Float = 0.52f,
) {
    scaleX = startScale
    scaleY = startScale
    springScaleTo(1f, durationSeconds, bounce)
}

/**
 * 列表错峰入场：从下方屏幕外滑入，带 spring 过冲。
 * [delayMs] 控制每一行的节奏，[distanceDp] 是起始位移。
 */
fun View.springEnter(
    delayMs: Long = 0L,
    distanceDp: Float = 64f,
    durationSeconds: Float = 0.64f,
    bounce: Float = 0.26f,
) {
    if (Motion.animationsDisabled(context)) {
        alpha = 1f
        translationY = 0f
        scaleX = 1f
        scaleY = 1f
        return
    }
    val distance = dp(distanceDp)
    alpha = 0f
    translationY = distance
    scaleX = 0.985f
    scaleY = 0.985f
    val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        startDelay = delayMs
        duration = (durationSeconds * 1000f).toLong().coerceAtLeast(1L)
        interpolator = SpringInterpolator(durationSeconds, bounce)
        addUpdateListener {
            val t = it.animatedValue as Float
            alpha = (t * 1.55f).coerceIn(0f, 1f)
            translationY = distance * (1f - t)
            val s = 0.985f + 0.015f * t
            scaleX = s
            scaleY = s
        }
    }
    trackAnimator(animator)
    animator.start()
    // 归位兜底：springEnter 先把 view 写成 alpha=0，再靠动画推回终态。
    // 动画一旦被中断（view 被复用 / 从 window 移除 / 被别的动画接管却没重新设初态），
    // 透明态就会永久留下 —— 表现就是内容"少了一半、停在原地不飞入"。
    // 超时后若仍是透明的就强制落终态；已有新动画接管时不插手，避免打断它。
    postDelayed({
        if (runningAnimators[this] !== animator) return@postDelayed
        if (alpha < 0.99f || abs(translationY) > 0.5f) {
            alpha = 1f
            translationY = 0f
            scaleX = 1f
            scaleY = 1f
        }
    }, delayMs + animator.duration + 200L)
}

/** 首页首屏：RecyclerView 可见子项按位置自上而下错峰进入。 */
fun RecyclerView.playStaggeredEntrance(
    initialDelayMs: Long = 40L,
    stepMs: Long = 46L,
    maxChildCount: Int = 9,
) {
    post {
        val count = childCount.coerceAtMost(maxChildCount)
        for (i in 0 until count) {
            val child = getChildAt(i) ?: continue
            val position = getChildAdapterPosition(child)
            if (position == RecyclerView.NO_POSITION) continue
            // 头部自己拆分镜，别让它整块滑动和内部动画打架
            if (position == 0 && child.height > dp(220f)) continue
            child.springEnter(
                delayMs = initialDelayMs + position * stepMs,
                distanceDp = if (position == 0) 96f else 72f,
            )
        }
    }
}

/** 轻震动反馈。点赞、切 tab、确认类操作各用一次，别连发。 */
fun View.springHaptic() {
    val constant = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        HapticFeedbackConstants.CONFIRM
    } else {
        HapticFeedbackConstants.VIRTUAL_KEY
    }
    performHapticFeedback(constant)
}