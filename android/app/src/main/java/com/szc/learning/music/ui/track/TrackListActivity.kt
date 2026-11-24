package com.szc.learning.music.ui.track

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.szc.learning.R
import com.szc.learning.ui.theme.ThemeManager
import com.szc.learning.core.common.AppResult
import com.szc.learning.core.common.music.MusicTrack
import com.szc.learning.core.common.toast
import com.szc.learning.core.network.music.ShareResult
import com.szc.learning.databinding.ActivityTrackListBinding
import com.szc.learning.music.ui.MusicViewModel
import com.szc.learning.music.ui.OnlinePlayEvent

import com.szc.learning.ui.main.MainActivity
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * 收藏 / 最近播放 / 云端热歌的通用列表页。
 *
 * 用一个 Activity 三种模式，而不是三个几乎一样的页面：
 * 行布局、点击播放、空态都相同，差异只有"数据从哪来"和标题。
 */
class TrackListActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTrackListBinding
    private val viewModel: MusicViewModel by viewModels()

    private var mode: String = MODE_FAVORITES
    private var tracks: List<MusicTrack> = emptyList()

    private val adapter = TrackAdapter { index ->
        if (index in tracks.indices) play(tracks, index)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // 主题要在 super 之前套上：晚一步，窗口会先按清单里的默认主题创建
        ThemeManager.applyPalette(this)
        super.onCreate(savedInstanceState)
        // 放在 super 之后：AppCompat 这时才把亮暗写进 resources，
        // enableEdgeToEdge 读到的才是最终模式，状态栏图标不会反色
        enableEdgeToEdge()
        binding = ActivityTrackListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 导航栏保持透明：不要系统给手势条下面加那层白色蒙版
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            window.isNavigationBarContrastEnforced = false
        }

        ViewCompat.setOnApplyWindowInsetsListener(binding.trackListRoot) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(top = bars.top, bottom = bars.bottom)
            insets
        }

        mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_FAVORITES
        binding.trackListTitle.setText(
            when (mode) {
                MODE_RECENT -> R.string.playlist_recent_title
                MODE_CLOUD -> R.string.playlist_cloud_title
                else -> R.string.playlist_favorite_title
            },
        )
        binding.trackListBack.setOnClickListener { finish() }
        binding.trackListPlayAll.setOnClickListener {
            if (tracks.isNotEmpty()) play(tracks, 0)
        }
        binding.trackListShare.setOnClickListener { shareCurrentList() }

        binding.trackList.layoutManager = LinearLayoutManager(this)
        binding.trackList.adapter = adapter

        viewModel.loadLocal()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(
                    viewModel.cloud,
                    viewModel.local,
                    viewModel.favorites,
                    viewModel.recent,
                ) { _, _, _, _ -> Unit }.collect { render() }
            }
        }
        render()
    }

    private fun openPlayer() {
        startActivity(MainActivity.playerIntent(this))
    }

    /**
     * 走统一入口播放。
     *
     * 收藏列表里可能混着在线曲目（uri 为空），直接 `play()` 会把空地址塞给 ExoPlayer；
     * [MusicViewModel.playTracks] 会先把在线曲目换好地址再起播。
     */
    private fun play(list: List<MusicTrack>, index: Int) {
        viewModel.playTracks(list, index) { event ->
            when (event) {
                is OnlinePlayEvent.Ready -> openPlayer()
                is OnlinePlayEvent.Failed -> toast(event.message)
            }
        }
    }

    private fun render() {
        tracks = when (mode) {
            MODE_RECENT -> viewModel.recent.value
            MODE_CLOUD -> viewModel.cloud.value
            else -> viewModel.favoriteTracks()
        }
        adapter.submitList(tracks)
        binding.trackListEmpty.visibility =
            if (tracks.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
    }

    // ---------------------------------------------------------------- 分享

    /**
     * 把当前列表生成一个 6 位分享码。
     *
     * 这是整个 App 唯一一个**不降级**的功能：短码必须在服务端分配。
     * 后端不在时直接失败并说明原因 —— 本地伪造一个码会让对方查出「码不存在」，
     * 比当场失败更糟。
     *
     * 本地曲目由 `MusicRepository.createShare` 过滤掉：`content://` 指的是
     * 本机文件，对方拿到只会是一首点开就报错的歌。
     */
    private fun shareCurrentList() {
        if (tracks.isEmpty()) {
            toast(R.string.share_empty)
            return
        }
        val title = binding.trackListTitle.text?.toString().orEmpty()
        lifecycleScope.launch {
            when (val result = viewModel.createShareCode(title, tracks)) {
                is AppResult.Success -> showShareCodeDialog(result.data)
                is AppResult.Failure -> toast(getString(R.string.share_failed, result.message))
            }
        }
    }

    private fun showShareCodeDialog(result: ShareResult) {
        // 码单独一行、前后各留一个空行：用户要念给别人听，也和说明文字区分开
        val body = buildString {
            appendLine(result.code)
            appendLine()
            append(
                getString(
                    R.string.share_dialog_message,
                    result.trackCount,
                    result.expireTime?.take(10).orEmpty(),
                ),
            )
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.share_dialog_title)
            .setMessage(body.trim())
            .setPositiveButton(R.string.share_copy) { _, _ ->
                copyToClipboard(result.code)
                toast(R.string.share_copied)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun copyToClipboard(text: String) {
        val manager = getSystemService(ClipboardManager::class.java) ?: return
        manager.setPrimaryClip(ClipData.newPlainText("aurora_share_code", text))
    }

    companion object {
        private const val EXTRA_MODE = "tracklist.mode"
        const val MODE_FAVORITES = "favorites"
        const val MODE_RECENT = "recent"
        const val MODE_CLOUD = "cloud"

        fun startFavorites(context: Context) = start(context, MODE_FAVORITES)

        fun startRecent(context: Context) = start(context, MODE_RECENT)

        fun startCloud(context: Context) = start(context, MODE_CLOUD)

        private fun start(context: Context, mode: String) {
            context.startActivity(
                Intent(context, TrackListActivity::class.java).putExtra(EXTRA_MODE, mode),
            )
        }
    }
}