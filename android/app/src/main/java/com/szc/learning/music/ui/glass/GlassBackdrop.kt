package com.szc.learning.music.ui.glass

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import android.util.Log
import android.view.View
import androidx.annotation.RequiresApi
import com.szc.learning.music.ui.widget.dp

/**
 * 玻璃面板底下那层"折射底色"。
 *
 * 两套实现，接口只有一个 [drawInto]：
 * - [RenderNodeBackdrop]：API 31+。把页面内容录进一个 [RenderNode]（只记绘制指令，
 *   不做光栅化），模糊和折射由消费它的那个 View 的 [RenderEffect] 完成 ——
 *   整个过程中 CPU 不碰一个像素。
 * - [BitmapBackdrop]：API 31 以下的老路，1/8 小图 + CPU 盒式模糊，慢但能用。
 *
 * 之所以录 [RenderNode] 而不是 `View.draw(Bitmap 的 Canvas)`：后者是软件光栅化，
 * 每采一次就要把整页文字和封面重新画一遍，滚动时必掉帧；前者只是把已有的绘制
 * 指令抄一份，GPU 再按面板大小去光栅化，成本低一个数量级。
 */
interface GlassBackdrop {

    /** 在已经 clip 到面板范围的 canvas 上画出底色，坐标原点就是面板左上角。 */
    fun drawInto(canvas: Canvas, width: Float, height: Float)

    fun release() = Unit
}

/** API 31 以下的退路：一张已经模糊好的小图，直接拉伸到面板大小。 */
class BitmapBackdrop(val bitmap: Bitmap) : GlassBackdrop {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val rect = RectF()
    private val matrix = ColorMatrix()
    private var saturation = -1f

    /** 提一点饱和度，磨砂玻璃背后的颜色才不会发灰。 */
    fun setSaturation(value: Float) {
        if (value == saturation) return
        saturation = value
        matrix.setSaturation(value)
        paint.colorFilter = ColorMatrixColorFilter(matrix)
    }

    override fun drawInto(canvas: Canvas, width: Float, height: Float) {
        if (bitmap.isRecycled) return
        rect.set(0f, 0f, width, height)
        canvas.drawBitmap(bitmap, null, rect, paint)
    }

    override fun release() {
        if (!bitmap.isRecycled) bitmap.recycle()
    }
}

/**
 * GPU 版的折射底色。
 *
 * [record] 把 [View] 画进一个面板大小的 [RenderNode]：canvas 先平移，
 * 让"面板在屏幕上的那块区域"正好落在 node 的 0,0..w,h，于是 node 里就是
 * 面板正后方的内容，画的时候不用再做任何对齐。
 *
 * 注意：效果不能挂在 node 上 —— `Canvas.drawRenderNode` 画进别的 display list 时
 * RenderEffect 会被丢掉（实测）。所以 node 只负责"内容"，效果由 [effectFor]
 * 交给承载它的那个子 View（见 [GlassSurfaceView]）。挂在 View 上是有效的。
 */
@RequiresApi(Build.VERSION_CODES.S)
class RenderNodeBackdrop(private val context: Context) : GlassBackdrop {

    private val node = RenderNode("aurora-glass-backdrop")
    private val shader = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        runCatching { RuntimeShader(REFRACTION_AGSL) }
            .onFailure { Log.w(TAG, "AGSL 编译失败，退回纯模糊：${it.message}") }
            .getOrNull()
    } else {
        null
    }
    private var hasContent = false

    /** 面板相对源视图的偏移（源坐标 → 面板坐标）。 */
    private var offsetX = 0
    private var offsetY = 0

    fun record(
        source: View,
        dx: Int,
        dy: Int,
    ) {
        if (source.width <= 0 || source.height <= 0) return
        // 录制整页而不是只录面板那条：ViewGroup 画子树时会按 canvas 的裁剪矩形剔除
        // 视野外的子 View，先把 canvas 平移再画会导致所有子 View 都被判成"不可见"
        // （实测录出来是空的）。所以这里录全页，偏移留到绘制时再平移。
        val canvas = node.beginRecording(source.width, source.height)
        try {
            source.draw(canvas)
        } finally {
            node.endRecording()
        }
        node.setPosition(0, 0, source.width, source.height)
        offsetX = dx
        offsetY = dy
        hasContent = true
    }

    /**
     * 这一帧该挂在这个 View 上的 RenderEffect。
     * 液态：模糊 → 折射 shader（内含色散、边缘额外磨砂、饱和度）；
     * 高斯：只模糊。
     */
    fun effectFor(spec: GlassSpec, width: Int, height: Int): RenderEffect {
        val frost = spec.frost.coerceIn(0f, 1f)
        val radius = if (spec.mode == GlassMode.LIQUID) {
            context.dp(4f + 7f * frost)
        } else {
            context.dp(4.5f + 8f * frost)
        }
        val blur = RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP)
        if (spec.mode == GlassMode.BLUR || shader == null) return blur

        shader.setFloatUniform("size", width.toFloat(), height.toFloat())
        shader.setFloatUniform("bend", context.dp(1.5f + 4f * frost))
        shader.setFloatUniform("dispersion", context.dp(0.4f + 1f * frost))
        shader.setFloatUniform("saturation", 1.15f + 0.45f * frost)
        shader.setFloatUniform("edgeSoftness", context.dp(1f + 2.5f * frost))
        val refract = RenderEffect.createRuntimeShaderEffect(shader, "content")
        return RenderEffect.createChainEffect(refract, blur)
    }

    override fun drawInto(canvas: Canvas, width: Float, height: Float) {
        if (!hasContent) return
        canvas.save()
        canvas.translate(offsetX.toFloat(), offsetY.toFloat())
        canvas.drawRenderNode(node)
        canvas.restore()
    }

    override fun release() {
        hasContent = false
        runCatching { node.discardDisplayList() }
    }

    private companion object {
        const val TAG = "GlassBackdrop"

        /**
         * AGSL：边缘折射 + 色散 + 边缘柔化 + 饱和度。
         *
         * p 是面板内的像素坐标，size 是面板尺寸；离边框越近 t 越大，
         * 取样点朝中心收得越多，视觉上就是玻璃边缘把背后的内容"掰"了一下。
         * 三个通道各自偏移一点，形成真实的色散彩边；边缘再叠一点额外的模糊，
         * 让边框附近的影像"化"开，和中心形成通透的层次。
         */
        const val REFRACTION_AGSL = """
            uniform shader content;
            uniform float2 size;
            uniform float bend;
            uniform float dispersion;
            uniform float saturation;
            uniform float edgeSoftness;

            half4 main(float2 p) {
                float2 center = size * 0.5;
                float2 edgeDist = min(p, size - p);
                float edge = min(edgeDist.x, edgeDist.y);
                float band = max(min(size.x, size.y) * 0.16, 1.0);
                float t = 1.0 - smoothstep(0.0, band, edge);

                // 边缘把取样点朝中心收，形成折射；越靠边收得越多
                float2 toCenter = center - p;
                float len = max(length(toCenter), 1.0);
                float2 dir = toCenter / len;
                float2 uv = p + dir * (pow(t, 2.2) * bend);

                // 色散：红蓝通道反向错开，绿通道居中
                float2 offset = dir * (dispersion * t * t);
                half4 base = content.eval(uv);
                half red = content.eval(uv + offset).r;
                half blue = content.eval(uv - offset).b;

                // 边缘额外柔化：多采两个点做均化，边框附近自然"化"开
                float soft = edgeSoftness * t;
                if (soft > 0.5) {
                    half4 a = content.eval(uv + float2(soft, 0.0));
                    half4 b = content.eval(uv + float2(-soft, 0.0));
                    half4 c = content.eval(uv + float2(0.0, soft));
                    half4 d = content.eval(uv + float2(0.0, -soft));
                    base = (base + a + b + c + d) * 0.2;
                }

                half4 outc = half4(red, base.g, blue, base.a);
                half luma = dot(outc.rgb, half3(0.299, 0.587, 0.114));
                half s = half(saturation);
                outc.rgb = clamp(mix(half3(luma), outc.rgb, s), 0.0, 1.0);
                return outc;
            }
        """
    }
}