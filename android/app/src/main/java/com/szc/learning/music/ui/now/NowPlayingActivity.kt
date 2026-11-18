package com.szc.learning.music.ui.now

import android.content.Intent
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.szc.learning.R
import com.szc.learning.ui.theme.themeColor
import com.szc.learning.ui.theme.ThemeManager
import com.szc.learning.core.common.music.MusicTrack
import com.szc.learning.core.common.music.formatDuration
import com.szc.learning.databinding.ActivityNowPlayingBinding
import com.szc.learning.databinding.DialogQueueBinding
import com.szc.learning.music.player.PlayerUiState
import com.szc.learning.music.ui.MusicViewModel
import com.szc.learning.music.ui.common.bindArt
import com.szc.learning.music.ui.motion.installSpringPress
import com.szc.learning.music.ui.motion.springHaptic
import com.szc.learning.music.ui.motion.springPop
import com.szc.learning.music.ui.track.TrackAdapter
import com.szc.learning.music.ui.widget.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 正在播放。
 *
 * 深色是刻意的：从播放器页滑进来、或从浅色首页点开，都进入同一个"看演出"的状态。
 * 背景不是纯黑，而是当前封面的模糊放大 + 一层深色渐变，
 * 换歌的时候整页色调会跟着封面慢慢换。
 */
class NowPlayingActivity : AppCompatActivity() {

    private lateinit var binding: ActivityNowPlayingBinding
    private val viewModel: MusicViewModel by viewModels()

    private var boundKey: String? = null
    private var currentTrack: MusicTrack? = null
    private var lyricJob: Job? = null
    private var lyricText: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        // 主题要在 super 之前套上：晚一步，窗口会先按清单里的默认主题创建
        ThemeManager.applyPalette(this)
        super.onCreate(savedInstanceState)
        // 放在 super 之后：AppCompat 这时才把亮暗写进 resources，
        // enableEdgeToEdge 读到的才是最终模式，状态栏图标不会反色
        enableEdgeToEdge()
        binding = ActivityNowPlayingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 导航栏保持透明：不要系统给手势条下面加那层白色蒙版
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            window.isNavigationBarContrastEnforced = false
        }

        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.nowRoot) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(bottom = bars.bottom)
            binding.nowTopBar.updatePadding(top = bars.top + view.context.dp(8f).toInt())
            insets
        }

        binding.nowSeek.showKnob = true
        binding.nowSeek.onDark = true
        binding.nowSeek.onSeekFinished = { fraction -> viewModel.seekToFraction(fraction) }

        binding.nowCollapse.setOnClickListener { finish() }
        binding.nowPlay.setOnClickListener { viewModel.toggle() }
        binding.nowNext.setOnClickListener { viewModel.next() }
        binding.nowPrev.setOnClickListener { viewModel.previous() }
        binding.nowShuffle.setOnClickListener { viewModel.toggleShuffle() }
        binding.nowRepeat.setOnClickListener { viewModel.cycleRepeat() }
        binding.nowQueue.setOnClickListener { showQueue() }
        binding.nowShare.setOnClickListener { currentTrack?.let { share(it) } }
        binding.nowMore.setOnClickListener { currentTrack?.let { showMore(it) } }
        binding.nowFavorite.setOnClickListener { currentTrack?.let { toggleFavorite(it) } }
        binding.nowLyric.setOnClickListener {
            lyricText?.takeIf { it.isNotBlank() }?.let { showLyrics(it) }
        }
        listOf(
            binding.nowCollapse,
            binding.nowQueue,
            binding.nowPlay,
            binding.nowNext,
            binding.nowPrev,
            binding.nowShuffle,
            binding.nowRepeat,
            binding.nowShare,
            binding.nowMore,
            binding.nowFavorite,
        ).forEach { it.installSpringPress(0.86f) }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.playerState.collect { state -> render(state) }
            }
        }
    }

    /**
     * 在线曲目没有内嵌歌词，走 `/app/music/lyric`；本地/云端仍由仓库解析。
     * 只取一行做预览，点一下看全文。
     */
    private fun loadLyric(track: MusicTrack) {
        lyricJob?.cancel()
        lyricText = null
        binding.nowLyric.text = getString(R.string.player_lyrics_loading)
        lyricJob = lifecycleScope.launch {
            val text = viewModel.lyrics(track)
            // 加载期间已经切歌：这次结果作废，不能盖掉新歌的歌词
            if (currentTrack?.key != track.key) return@launch
            lyricText = text
            binding.nowLyric.text =
                text?.let { firstLyricLine(it) } ?: getString(R.string.player_lyric_placeholder)
        }
    }

    /** 去掉 `[00:12.34]` 时间码，取第一句真正有内容的歌词（跳过作词/作曲这类署名行）。 */
    private fun firstLyricLine(text: String): String? = text.lineSequence()
        .map { it.replace(Regex("^\\[[^\\]]*\\]"), "").trim() }
        .firstOrNull { line ->
            line.isNotEmpty() && LYRIC_META.none { line.startsWith(it) }
        }

    private fun showLyrics(text: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.player_lyrics_title)
            .setMessage(text)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun render(state: PlayerUiState) {
        val track = state.track
        if (track == null) {
            // 在线曲目是先 startActivity 再等 MediaController 回传当前曲目：
            // 队列已经建好但状态还没到的那一帧不能直接关页，否则用户点完歌只看到一闪
            if (state.queueSize == 0) {
                finish()
            }
            return
        }
        val changed = currentTrack?.key != track.key
        currentTrack = track
        if (changed) loadLyric(track)

        binding.nowTitle.text = track.title
        binding.nowArtist.text = track.artist.ifBlank {
            getString(R.string.player_unknown_artist)
        }
        binding.nowPosition.text = formatDuration(state.positionMs)
        binding.nowDuration.text = formatDuration(state.durationMs)
        binding.nowSeek.progress = state.progress

        binding.nowPlay.setImageResource(if (state.isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
        binding.nowPlay.contentDescription =
            getString(if (state.isPlaying) R.string.player_pause else R.string.player_play)

        val accent = themeColor(R.attr.auAccent)
        val idle = themeColor(R.attr.auOnDarkSecondary)
        binding.nowShuffle.imageTintList =
            android.content.res.ColorStateList.valueOf(if (state.shuffle) accent else idle)
        binding.nowRepeat.imageTintList = android.content.res.ColorStateList.valueOf(
            if (state.repeatMode != androidx.media3.common.Player.REPEAT_MODE_OFF) accent else idle,
        )

        if (boundKey != track.key) {
            boundKey = track.key
            binding.nowCover.bindArt(track, sizePx = 900)
            binding.nowBackdrop.bindArt(track, sizePx = 1400)
            applyBackdropBlur()
            renderFavorite(track)
        }
    }

    private fun applyBackdropBlur() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        binding.nowBackdrop.setRenderEffect(
            RenderEffect.createBlurEffect(48f, 48f, Shader.TileMode.CLAMP),
        )
        binding.nowBackdrop.scaleX = 1.12f
        binding.nowBackdrop.scaleY = 1.12f
    }

    private fun renderFavorite(track: MusicTrack) {
        val favorite = viewModel.isFavorite(track.key)
        binding.nowFavorite.setImageResource(
            if (favorite) R.drawable.ic_heart_filled else R.drawable.ic_heart,
        )
        binding.nowFavorite.imageTintList = android.content.res.ColorStateList.valueOf(
            themeColor(if (favorite) R.attr.auLike else R.attr.auOnDark),
        )
    }

    private fun toggleFavorite(track: MusicTrack) {
        val favorite = viewModel.toggleFavorite(track)
        renderFavorite(track)
        // 收藏是"心跳"：先缩一下，再用一次明显过冲弹回来
        binding.nowFavorite.springPop(
            startScale = if (favorite) 0.66f else 0.82f,
            durationSeconds = if (favorite) 0.62f else 0.46f,
            bounce = if (favorite) 0.52f else 0.3f,
        )
        binding.nowFavorite.springHaptic()
        toast(if (favorite) R.string.player_added_favorite else R.string.player_removed_favorite)
    }

    private fun share(track: MusicTrack) {
        val text = getString(
            R.string.player_share_text,
            track.title,
            track.artist.ifBlank { getString(R.string.player_unknown_artist) },
        )
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(send, getString(R.string.player_share)))
    }

    private fun showMore(track: MusicTrack) {
        val favorite = viewModel.isFavorite(track.key)
        val actions = arrayOf(
            getString(if (favorite) R.string.player_unlike else R.string.player_like),
            getString(R.string.player_queue),
            getString(R.string.player_share),
        )
        MaterialAlertDialogBuilder(this)
            .setItems(actions) { _, which ->
                when (which) {
                    0 -> toggleFavorite(track)
                    1 -> showQueue()
                    else -> share(track)
                }
            }
            .show()
    }

    private fun showQueue() {
        val dialog = BottomSheetDialog(this)
        val sheet = DialogQueueBinding.inflate(layoutInflater)
        dialog.setContentView(sheet.root)

        val queue = viewModel.queue.value
        sheet.queueCount.text = getString(R.string.queue_count, queue.size)
        val adapter = TrackAdapter(activeKey = viewModel.playerState.value.track?.key) { index ->
            viewModel.playAt(index)
            dialog.dismiss()
        }
        sheet.queueList.layoutManager = LinearLayoutManager(this)
        sheet.queueList.adapter = adapter
        adapter.submitList(queue)

        dialog.show()
    }

    private fun toast(resId: Int) {
        Toast.makeText(this, resId, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        val LYRIC_META = listOf("作词", "作曲", "编曲", "制作人", "混音", "母带", "录音", "出品", "监制")
    }
}