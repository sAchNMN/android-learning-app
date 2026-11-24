package com.szc.learning.music.ui.playlist

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.szc.learning.R
import com.szc.learning.core.common.AppResult
import com.szc.learning.core.common.music.MusicTrack
import com.szc.learning.core.common.toast
import com.szc.learning.databinding.ActivityPlaylistDetailBinding
import com.szc.learning.music.ui.MusicViewModel
import com.szc.learning.music.ui.OnlinePlayEvent
import com.szc.learning.music.ui.common.bindArt
import com.szc.learning.music.ui.common.sourceLabel
import com.szc.learning.music.ui.track.TrackAdapter
import com.szc.learning.ui.main.MainActivity
import com.szc.learning.ui.theme.ThemeManager
import kotlinx.coroutines.launch

/**
 * 音源歌单详情。
 *
 * 之前推荐页的歌单卡是「点一下就整张开播」，用户看到的是播放器在放一首从没见过的歌，
 * 却不知道这张歌单里到底有什么。这里补上那一步：先看清曲目，再决定播哪首。
 *
 * 数据每次进来现拉（歌单卡片只有标题/封面/总数），所以页面自己持有加载态；
 * 播放仍然走 [MusicViewModel.playTracks]，在线曲目会先换地址再进队列。
 */
class PlaylistDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPlaylistDetailBinding
    private val viewModel: MusicViewModel by viewModels()

    private var tracks: List<MusicTrack> = emptyList()

    private val adapter = TrackAdapter { index ->
        if (index in tracks.indices) play(index)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // 主题要在 super 之前套上：晚一步，窗口会先按清单里的默认主题创建
        ThemeManager.applyPalette(this)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityPlaylistDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (android.os.Build.VERSION.SDK_INT >= 29) {
            window.isNavigationBarContrastEnforced = false
        }

        ViewCompat.setOnApplyWindowInsetsListener(binding.playlistDetailRoot) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(top = bars.top, bottom = bars.bottom)
            insets
        }

        val source = intent.getStringExtra(EXTRA_SOURCE).orEmpty()
        val playlistId = intent.getStringExtra(EXTRA_ID).orEmpty()
        val name = intent.getStringExtra(EXTRA_NAME).orEmpty()
        val coverUrl = intent.getStringExtra(EXTRA_COVER)
        val declaredCount = intent.getIntExtra(EXTRA_COUNT, 0)

        binding.playlistDetailTitle.text = name
        binding.playlistDetailCount.text = buildCountLine(source, declaredCount)
        binding.playlistDetailCover.bindArt(
            MusicTrack(
                id = "$source:$playlistId",
                title = name,
                artist = "",
                album = "",
                durationMs = 0,
                uri = "",
                artUri = coverUrl,
            ),
            sizePx = 320,
        )

        binding.playlistDetailBack.setOnClickListener { finish() }
        binding.playlistDetailPlayAll.setOnClickListener {
            if (tracks.isNotEmpty()) play(0)
        }

        binding.playlistDetailList.layoutManager = LinearLayoutManager(this)
        binding.playlistDetailList.adapter = adapter

        if (playlistId.isBlank()) {
            showHint(R.string.playlist_detail_empty)
            return
        }
        load(source, playlistId, name)
    }

    private fun load(source: String, playlistId: String, name: String) {
        lifecycleScope.launch {
            when (val result = viewModel.playlistTracks(source, playlistId, name)) {
                is AppResult.Success -> {
                    tracks = result.data
                    adapter.submitList(result.data)
                    binding.playlistDetailCount.text = buildCountLine(source, result.data.size)
                    binding.playlistDetailPlayAll.isVisible = result.data.isNotEmpty()
                    if (result.data.isEmpty()) {
                        showHint(R.string.playlist_detail_empty)
                    } else {
                        binding.playlistDetailHint.isVisible = false
                    }
                }

                is AppResult.Failure -> showHint(result.message)
            }
        }
    }

    private fun showHint(text: String) {
        binding.playlistDetailHint.text = text
        binding.playlistDetailHint.isVisible = true
        binding.playlistDetailPlayAll.isVisible = false
    }

    private fun showHint(resId: Int) = showHint(getString(resId))

    /** 「网易云音乐 · 51 首」；平台名比裸数字有用，用户要确认这是哪边同步来的。 */
    private fun buildCountLine(source: String, count: Int): String {
        val platform = if (source.isBlank()) "" else sourceLabel(this, source)
        return when {
            platform.isBlank() -> getString(R.string.playlist_detail_count, count)
            else -> platform + " · " + getString(R.string.playlist_detail_count, count)
        }
    }

    private fun play(index: Int) {
        viewModel.playTracks(tracks, index) { event ->
            when (event) {
                is OnlinePlayEvent.Ready -> {
                    startActivity(MainActivity.playerIntent(this))
                }

                is OnlinePlayEvent.Failed -> toast(event.message)
            }
        }
    }

    companion object {
        private const val EXTRA_SOURCE = "playlist.source"
        private const val EXTRA_ID = "playlist.id"
        private const val EXTRA_NAME = "playlist.name"
        private const val EXTRA_COVER = "playlist.cover"
        private const val EXTRA_COUNT = "playlist.count"

        fun start(
            context: Context,
            source: String,
            id: String,
            name: String,
            coverUrl: String?,
            trackCount: Int,
        ) {
            context.startActivity(
                Intent(context, PlaylistDetailActivity::class.java)
                    .putExtra(EXTRA_SOURCE, source)
                    .putExtra(EXTRA_ID, id)
                    .putExtra(EXTRA_NAME, name)
                    .putExtra(EXTRA_COVER, coverUrl)
                    .putExtra(EXTRA_COUNT, trackCount),
            )
        }
    }
}
