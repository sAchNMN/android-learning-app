package com.szc.learning.music.ui.widget

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import com.google.android.material.imageview.ShapeableImageView
import com.szc.learning.R
import com.szc.learning.core.common.music.MusicTrack
import com.szc.learning.music.ui.common.bindArt
import com.szc.learning.music.ui.glass.GlassSurfaceView
import com.szc.learning.music.ui.motion.installSpringJelly
import com.szc.learning.music.ui.motion.springEnter

/**
 * 悬浮迷你播放器。
 *
 * 它是"播放状态"和"当前页面"之间唯一的常驻纽扣：无论切到哪个 tab 都在，
 * 点一下展开成全屏播放页。
 *
 * 材质与底部导航同源（[GlassSurfaceView]）：半透明玻璃让身后滚动的列表透出来，
 * 等于把这一条 60dp 的"遮挡带"还给了内容；文字靠磨砂底色 + 描边保持可读。
 * 按下去时镜面光斑跟着手指走，松手淡出 —— 这是玻璃"液态"的来源。
 */
class MiniPlayerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : GlassSurfaceView(context, attrs) {

    var onOpen: (() -> Unit)? = null
    var onPlayToggle: (() -> Unit)? = null
    var onNext: (() -> Unit)? = null

    private val cover: ShapeableImageView
    private val title: TextView
    private val artist: TextView
    private val playButton: ImageButton
    private val progressLine: SeekLine
    private var lastTrackKey: String? = null
    private var shown = false

    init {
        // 和底部导航的 26dp 形成一组圆角阶梯：小的更圆，大的更方
        cornerRadiusDp = 20f
        lensFollowTouch = true

        LayoutInflater.from(context).inflate(R.layout.view_mini_player, this, true)
        cover = findViewById(R.id.miniCover)
        title = findViewById(R.id.miniTitle)
        artist = findViewById(R.id.miniArtist)
        playButton = findViewById(R.id.miniPlay)
        progressLine = findViewById(R.id.miniProgress)

        // 进度线不接收触摸，整条卡片都是"展开"的热区
        progressLine.isClickable = false
        progressLine.isEnabled = false

        playButton.setOnClickListener { onPlayToggle?.invoke() }
        findViewById<View>(R.id.miniNext).setOnClickListener { onNext?.invoke() }
        setOnClickListener { onOpen?.invoke() }
        // 按下时纵向压得比横向多，松手过冲弹回 —— 果冻感来自非等比缩放
        installSpringJelly(pressedScale = 0.96f, squash = 0.02f)
    }

    fun bind(
        track: MusicTrack?,
        isPlaying: Boolean,
        progress: Float,
    ) {
        if (track == null) {
            if (visibility != View.GONE) {
                visibility = View.GONE
                shown = false
                alpha = 1f
                translationY = 0f
            }
            return
        }
        if (visibility != View.VISIBLE) {
            visibility = View.VISIBLE
            if (!shown) {
                shown = true
                springEnter(
                    delayMs = 40L,
                    distanceDp = 26f,
                    durationSeconds = 0.52f,
                    bounce = 0.3f,
                )
            }
        }

        title.text = track.title
        artist.text = track.artist.ifBlank {
            context.getString(R.string.player_unknown_artist)
        }
        playButton.setImageResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
        playButton.contentDescription =
            context.getString(if (isPlaying) R.string.player_pause else R.string.player_play)
        progressLine.progress = progress

        // 封面只换一次，避免每 350ms 的位置回调都触发一次解码
        if (lastTrackKey != track.key) {
            lastTrackKey = track.key
            cover.bindArt(track, sizePx = 120)
        }
    }
}