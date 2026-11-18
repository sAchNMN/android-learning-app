package com.szc.learning.music.ui.glass

import android.animation.ValueAnimator
import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Path
import android.graphics.RectF
import android.os.Build
import android.os.SystemClock
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.view.ViewTreeObserver
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import com.szc.learning.music.ui.motion.Motion
import com.szc.learning.music.ui.widget.Squircle
import com.szc.learning.music.ui.widget.dp
import kotlin.math.roundToInt

/**
 * 一块会呼吸的玻璃。迷你播放器和底部导航都继承它，所以设置面板里拖一次滑块，
 * 整个 App 的玻璃会同时变厚/变薄 —— 这是"材质"而不是某个控件的装饰。
 *
 * 职责有三块：
 * 1. 画：[GlassPainter] 负责外观，这里只管把圆角、深色、触摸镜面喂给它；
 * 2. 折射底色：把 [backdropSource] 录进一个 GPU [android.graphics.RenderNode]，
 *    液态用它做边缘折射，高斯模式用它做重磨砂；API 31 以下退回 CPU 小图模糊；
 * 3. 热更新：监听 SharedPreferences，设置面板一改就重绘，不需要谁去通知谁。
 *
 * GPU 路径的采样成本只是一次 display list 录制（不碰像素），所以可以每 ~50ms 采一次，
 * 滚动时几乎看不出来；CPU 退路压到 ~110ms。
 */
open class GlassSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    protected val glass = GlassPainter(context)

    /** 玻璃圆角（dp）。底部导航是 26，迷你播放器是 20。 */
    var cornerRadiusDp: Float = 20f
        set(value) {
            if (field == value) return
            field = value
            syncGlass()
            invalidateOutline()
            invalidate()
        }

    /** 深色玻璃：夜间主题，或压着深色封面的播放器页。 */
    var glassDark: Boolean = isNight(context)
        set(value) {
            if (field == value) return
            field = value
            syncGlass()
            invalidate()
        }

    /**
     * 折射底色的来源视图。通常是 Activity 里承载页面的那个容器。
     * 为 null 时玻璃退化成纯半透明染色（不会闪、也不会报错）。
     */
    var backdropSource: View? = null
        set(value) {
            if (field === value) return
            detachScrollListener()
            field = value
            attachScrollListener()
            dropBackdrop()
            requestBackdrop()
        }

    /** 设置面板里的实时预览用它临时覆盖已保存的外观，不落盘。 */
    var glassOverride: GlassSpec? = null
        set(value) {
            field = value
            syncGlass()
        }

    /** 触摸镜面跟随（液态手感）。底部导航有自己的触摸逻辑，不开启。 */
    var lensFollowTouch: Boolean = false

    /** 承载折射底色的子 View：效果必须挂在 View 上（挂 RenderNode 会被丢弃）。 */
    private val backdropView = BackdropView(context)

    private inner class BackdropView(context: Context) : View(context) {
        init {
            setWillNotDraw(false)
        }

        override fun onDraw(canvas: Canvas) {
            glass.draw(canvas, width, height, if (backdropReady) backdrop else null)
        }
    }

    private var backdrop: GlassBackdrop? = null
    private var nodeBackdrop: RenderNodeBackdrop? = null
    private var backdropReady = false
    private var lastCaptureAt = 0L
    private val captureRunnable = Runnable { captureBackdrop() }

    private val scrollListener = ViewTreeObserver.OnScrollChangedListener { requestBackdrop() }
    private var scrollSource: View? = null

    private val prefsListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> syncGlass() }

    private var lensFade: ValueAnimator? = null

    init {
        setWillNotDraw(false)
        clipToOutline = true
        // 底色必须垫在所有内容下面：XML 里的子 View 是构造完之后才加进来的，
        // 所以这里 addView(index = 0) 刚好把它放在最底层。
        addView(backdropView, 0, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        // 玻璃是半透明的，系统 elevation 阴影会从玻璃底下透出来，
        // 整块面板会发灰、发脏 —— 阴影必须关掉，边界交给描边和高光。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            outlineAmbientShadowColor = Color.TRANSPARENT
            outlineSpotShadowColor = Color.TRANSPARENT
        }
        outlineProvider = object : ViewOutlineProvider() {
            private val path = Path()
            private val rect = RectF()

            override fun getOutline(view: View, outline: Outline) {
                val radius = context.dp(cornerRadiusDp)
                // 裁剪轮廓要和画出来的形状一致：圆角矩形会把超级椭圆的四角切掉
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    rect.set(0f, 0f, view.width.toFloat(), view.height.toFloat())
                    Squircle.path(rect, radius, path)
                    outline.setPath(path)
                } else {
                    outline.setRoundRect(0, 0, view.width, view.height, radius)
                }
            }
        }
        syncGlass()
    }

    // ---------------------------------------------------------------- 公开 API

    /** 内容/背景变化后调用：重读设置，模糊模式下重新采样。 */
    fun refreshGlass() {
        syncGlass()
    }

    // ---------------------------------------------------------------- 状态

    private fun syncGlass() {
        glass.configure(
            spec = glassOverride ?: GlassSettings.spec(context),
            dark = glassDark,
            cornerRadius = context.dp(cornerRadiusDp),
        )
        if (backdropEnabled() && backdropSource != null) {
            requestBackdrop()
        } else if (backdropReady) {
            dropBackdrop()
        }
        invalidate()
    }

    /** 液态也可以有底色（GPU 折射），只有 API 31 以下才必须靠染色撑住透明度。 */
    private fun backdropEnabled(): Boolean =
        glass.spec.mode == GlassMode.BLUR || Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    private fun dropBackdrop() {
        backdropReady = false
        backdrop?.release()
        backdrop = null
        clearBackdropEffect()
    }

    private fun applyBackdropEffect() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val node = nodeBackdrop
        if (node == null || !backdropReady) {
            clearBackdropEffect()
            return
        }
        backdropView.setRenderEffect(node.effectFor(glass.spec, width, height))
    }

    private fun clearBackdropEffect() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            backdropView.setRenderEffect(null)
        }
    }

    private fun captureIntervalMs(): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            GPU_CAPTURE_INTERVAL_MS
        } else {
            CAPTURE_INTERVAL_MS
        }

    private fun requestBackdrop() {
        if (!isAttachedToWindow) return
        if (!backdropEnabled()) return
        val source = backdropSource ?: return
        if (source === this || source.width <= 0) return
        removeCallbacks(captureRunnable)
        val wait = (captureIntervalMs() - (SystemClock.uptimeMillis() - lastCaptureAt))
            .coerceAtLeast(0L)
        postDelayed(captureRunnable, wait)
    }

    /**
     * 把背后内容录一份到 GPU。
     *
     * 不用 PixelCopy：它会把浮在上面的玻璃自己一起截进去，滚两帧就糊成一片；
     * 直接录 [backdropSource] 的子树天然不含自己，而且只记绘制指令、不做光栅化。
     */
    private fun captureBackdrop() {
        if (!isAttachedToWindow || width <= 0 || height <= 0) return
        if (!backdropEnabled() || !isShown) return
        val source = backdropSource ?: return
        if (source === this || !source.isAttachedToWindow) return
        if (source.width <= 0 || source.height <= 0) return

        val selfLoc = IntArray(2)
        val sourceLoc = IntArray(2)
        getLocationInWindow(selfLoc)
        source.getLocationInWindow(sourceLoc)
        val dx = sourceLoc[0] - selfLoc[0]
        val dy = sourceLoc[1] - selfLoc[1]

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val node = nodeBackdrop ?: RenderNodeBackdrop(context).also { nodeBackdrop = it }
            try {
                node.record(source, dx, dy)
                backdrop = node
                backdropReady = true
                applyBackdropEffect()
            } catch (error: RuntimeException) {
                // 录制失败就退回纯染色，不能因为一层背景把界面画崩
                android.util.Log.w("GlassSurfaceView", "backdrop 录制失败", error)
                backdropReady = false
                backdrop = null
                clearBackdropEffect()
            }
        } else {
            captureWithBitmap(source, dx, dy)
        }

        lastCaptureAt = SystemClock.uptimeMillis()
        // 底色的内容换了，必须让承载它的子 View 重新录一次显示列表
        backdropView.invalidate()
        invalidate()
    }

    /** API 31 以下的退路：1/8 小图 + CPU 三遍盒式模糊。 */
    private fun captureWithBitmap(source: View, dx: Int, dy: Int) {
        val dstW = (width / SAMPLE_DIVISOR).coerceIn(12, 240)
        val dstH = (height / SAMPLE_DIVISOR).coerceIn(8, 240)
        val previous = backdrop as? BitmapBackdrop
        val target = previous?.bitmap
            ?.takeIf { !it.isRecycled && it.width == dstW && it.height == dstH }
            ?: Bitmap.createBitmap(dstW, dstH, Bitmap.Config.ARGB_8888)
        if (previous != null && previous.bitmap !== target) {
            previous.release()
            backdrop = null
        }

        val canvas = Canvas(target)
        canvas.scale(dstW.toFloat() / width, dstH.toFloat() / height)
        canvas.translate(dx.toFloat(), dy.toFloat())
        try {
            source.draw(canvas)
        } catch (error: RuntimeException) {
            // 极少见：子树里有画不进软件画布的内容（例如老的硬件位图）
            backdropReady = false
            return
        }

        val frost = glass.spec.frost.coerceIn(0f, 1f)
        blurInPlace(target, (2f + 5f * frost).roundToInt().coerceIn(2, 7))
        backdrop = (backdrop as? BitmapBackdrop) ?: BitmapBackdrop(target).also { backdrop = it }
        backdropReady = true
    }

    private fun blurInPlace(bitmap: Bitmap, radius: Int) {
        val w = bitmap.width
        val h = bitmap.height
        if (w < 2 || h < 2) return
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        boxBlur(pixels, w, h, radius)
        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
    }

    // ---------------------------------------------------------------- 生命周期

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        GlassSettings.preferences(context)
            .registerOnSharedPreferenceChangeListener(prefsListener)
        attachScrollListener()
        syncGlass()
    }

    override fun onDetachedFromWindow() {
        GlassSettings.preferences(context)
            .unregisterOnSharedPreferenceChangeListener(prefsListener)
        detachScrollListener()
        removeCallbacks(captureRunnable)
        lensFade?.cancel()
        lensFade = null
        dropBackdrop()
        nodeBackdrop?.release()
        nodeBackdrop = null
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        dropBackdrop()
        requestBackdrop()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE && isAttachedToWindow) {
            syncGlass()
        }
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE && isAttachedToWindow) syncGlass()
    }

    // ---------------------------------------------------------------- 绘制

    /**
     * 绘制顺序必须是：折射底色（子 View）→ 玻璃本体 → 其余内容。
     *
     * ViewGroup 默认的 onDraw → dispatchDraw 顺序做不到这一点（底色是带 RenderEffect
     * 的子 View，只能放在子 View 层），所以这里手动画：先画垫在 0 号的底色，
     * 紧接着画染色/高光/描边，再画剩下的子 View（封面、文字、按钮）。
     */
    override fun dispatchDraw(canvas: Canvas) {
        if (width <= 0 || height <= 0) return
        val time = SystemClock.uptimeMillis()
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            if (child.visibility != VISIBLE && child.animation == null) continue
            drawChild(canvas, child, time)
            if (child === backdropView) {
                glass.drawOverlay(canvas, width, height)
                onGlassDrawn(canvas)
            }
        }
    }

    /** 给子类在"玻璃之上、内容之下"画东西的机会（底部导航的选中胶囊）。 */
    protected open fun onGlassDrawn(canvas: Canvas) = Unit

    // ---------------------------------------------------------------- 触摸镜面

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (lensFollowTouch) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN,
                MotionEvent.ACTION_MOVE,
                -> {
                    lensFade?.cancel()
                    lensFade = null
                    glass.lensX = event.x
                    glass.lensY = event.y
                    glass.lensStrength = 1f
                    invalidate()
                }

                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL,
                -> fadeLens()
            }
        }
        return super.dispatchTouchEvent(event)
    }

    private fun fadeLens() {
        lensFade?.cancel()
        if (Motion.animationsDisabled(context)) {
            glass.lensStrength = 0f
            invalidate()
            return
        }
        val start = glass.lensStrength
        if (start <= 0.001f) return
        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 460L
            interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener {
                glass.lensStrength = (start * (1f - it.animatedValue as Float)).coerceAtLeast(0f)
                invalidate()
            }
        }
        lensFade = animator
        animator.start()
    }

    // ---------------------------------------------------------------- 采样监听

    private fun attachScrollListener() {
        if (!isAttachedToWindow) return
        val source = backdropSource ?: return
        if (scrollSource === source) return
        detachScrollListener()
        source.viewTreeObserver.addOnScrollChangedListener(scrollListener)
        scrollSource = source
    }

    private fun detachScrollListener() {
        val source = scrollSource ?: return
        val observer = source.viewTreeObserver
        if (observer.isAlive) observer.removeOnScrollChangedListener(scrollListener)
        scrollSource = null
    }

    private companion object {
        /** 每 1/8 像素采一个点（仅 CPU 退路用）。 */
        const val SAMPLE_DIVISOR = 8

        /** GPU 路径的采样间隔：录制 display list 很便宜，可以采得勤一点。 */
        const val GPU_CAPTURE_INTERVAL_MS = 50L

        /** CPU 退路的采样间隔：软件光栅化 + 盒式模糊，压到 ~9 次/秒。 */
        const val CAPTURE_INTERVAL_MS = 110L

        fun isNight(context: Context): Boolean =
            (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
    }
}

/**
 * 可分离的三遍盒式模糊，在几十像素的小图上做，成本可以忽略。
 * 三遍盒式（而不是一遍）是为了让边缘更接近高斯而不是方块。
 */
private fun boxBlur(pixels: IntArray, width: Int, height: Int, radius: Int) {
    if (radius < 1) return
    val temp = IntArray(pixels.size)
    repeat(3) {
        blurHorizontal(pixels, temp, width, height, radius)
        blurVertical(temp, pixels, width, height, radius)
    }
}

private fun blurHorizontal(src: IntArray, dst: IntArray, width: Int, height: Int, radius: Int) {
    val span = radius * 2 + 1
    val last = width - 1
    for (y in 0 until height) {
        val row = y * width
        var a = 0
        var r = 0
        var g = 0
        var b = 0
        for (i in -radius..radius) {
            val p = src[row + i.coerceIn(0, last)]
            a += (p ushr 24) and 0xFF
            r += (p ushr 16) and 0xFF
            g += (p ushr 8) and 0xFF
            b += p and 0xFF
        }
        for (x in 0..last) {
            dst[row + x] = ((a / span) shl 24) or
                ((r / span) shl 16) or
                ((g / span) shl 8) or
                (b / span)
            val out = src[row + (x - radius).coerceIn(0, last)]
            val into = src[row + (x + radius + 1).coerceIn(0, last)]
            a += ((into ushr 24) and 0xFF) - ((out ushr 24) and 0xFF)
            r += ((into ushr 16) and 0xFF) - ((out ushr 16) and 0xFF)
            g += ((into ushr 8) and 0xFF) - ((out ushr 8) and 0xFF)
            b += (into and 0xFF) - (out and 0xFF)
        }
    }
}

private fun blurVertical(src: IntArray, dst: IntArray, width: Int, height: Int, radius: Int) {
    val span = radius * 2 + 1
    val last = height - 1
    for (x in 0 until width) {
        var a = 0
        var r = 0
        var g = 0
        var b = 0
        for (i in -radius..radius) {
            val p = src[i.coerceIn(0, last) * width + x]
            a += (p ushr 24) and 0xFF
            r += (p ushr 16) and 0xFF
            g += (p ushr 8) and 0xFF
            b += p and 0xFF
        }
        for (y in 0..last) {
            dst[y * width + x] = ((a / span) shl 24) or
                ((r / span) shl 16) or
                ((g / span) shl 8) or
                (b / span)
            val out = src[(y - radius).coerceIn(0, last) * width + x]
            val into = src[(y + radius + 1).coerceIn(0, last) * width + x]
            a += ((into ushr 24) and 0xFF) - ((out ushr 24) and 0xFF)
            r += ((into ushr 16) and 0xFF) - ((out ushr 16) and 0xFF)
            g += ((into ushr 8) and 0xFF) - ((out ushr 8) and 0xFF)
            b += (into and 0xFF) - (out and 0xFF)
        }
    }
}