package com.szc.learning.music.ui.widget

import android.annotation.SuppressLint
import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.imageview.ShapeableImageView
import com.szc.learning.R
import com.szc.learning.core.common.music.MusicTrack
import com.szc.learning.music.ui.common.bindArt

/**
 * 悬浮迷你播放器。
 *
 * 它是"播放状态"和"当前页面"之间唯一的常驻纽扣：
 * 无论切到哪个 tab 都在，点一下展开成全屏播放页。
 *
 * 自带布局（不是被 include 进来的），这样 activity_main.xml 里只有一行，
 * 内部结构和逻辑也就不用散落在 Activity 里。
 */
class MiniPlayerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : GlassView(context, attrs) {

    var onOpen: (() -> Unit)? = null
    var onPlayToggle: (() -> Unit)? = null
    var onNext: (() -> Unit)? = null

    private val cover: ShapeableImageView
    private val title: TextView
    private val artist: TextView
    private val playButton: ImageButton
    private val progressLine: SeekLine
    private var lastTrackKey: String? = null

    init {
        LayoutInflater.from(context).inflate(R.layout.view_mini_player, this, true)
        cover = findViewById(R.id.miniCover)
        title = findViewById(R.id.miniTitle)
        artist = findViewById(R.id.miniArtist)
        playButton = findViewById(R.id.miniPlay)
        progressLine = findViewById(R.id.miniProgress)

        corner = context.dp(19f)
        elevation = context.dp(10f)
        topAlpha = 0.72f
        bottomAlpha = 0.34f

        // 进度线不接收触摸，整条 pill 都是"展开"的热区
        progressLine.isClickable = false
        progressLine.isEnabled = false

        playButton.setOnClickListener { onPlayToggle?.invoke() }
        findViewById<View>(R.id.miniNext).setOnClickListener { onNext?.invoke() }
        setOnClickListener { onOpen?.invoke() }
        attachPressFeedback()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachPressFeedback() {
        setOnTouchListener { view, event ->
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN ->
                    view.animate().scaleX(0.975f).scaleY(0.975f).setDuration(110).start()

                android.view.MotionEvent.ACTION_UP,
                android.view.MotionEvent.ACTION_CANCEL ->
                    view.animate().scaleX(1f).scaleY(1f).setDuration(160).start()
            }
            false
        }
    }

    fun bind(
        track: MusicTrack?,
        isPlaying: Boolean,
        progress: Float,
    ) {
        if (track == null) {
            visibility = View.GONE
            return
        }
        visibility = View.VISIBLE

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
