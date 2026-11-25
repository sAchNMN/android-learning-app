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
import com.szc.learning.ui.theme.clearWindowBackground
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
 * 收藏 / 最近播放 / 自建歌单的通用列表页。
 *
 * 用一个 Activity 三种模式，而不是三个几乎一样的页面：
 * 行布局、点击播放、空态都相同，差异只有"数据从哪来"和标题。
 *
 * 自建歌单模式多一个长按移除 —— 它是唯一允许行级修改的列表。
 */
class TrackListActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTrackListBinding
    private val viewModel: MusicViewModel by viewModels()

    private var mode: String = MODE_FAVORITES
    private var ownPlaylistId: String? = null
    private var tracks: List<MusicTrack> = emptyList()

    private val adapter = TrackAdapter(
        onClick = { index -> if (index in tracks.indices) play(tracks, index) },
        onLongClick = { track -> if (mode == MODE_OWN) confirmRemove(track) },
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        // 主题要在 super 之前套上：晚一步，窗口会先按清单里的默认主题创建
        ThemeManager.applyPalette(this)
        super.onCreate(savedInstanceState)
        // 放在 super 之后：AppCompat 这时才把亮暗写进 resources，
        // enableEdgeToEdge 读到的才是最终模式，状态栏图标不会反色
        enableEdgeToEdge()
        binding = ActivityTrackListBinding.inflate(layoutInflater)
        setContentView(binding.root)
        clearWindowBackground()

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
        ownPlaylistId = intent.getStringExtra(EXTRA_PLAYLIST_ID)
        if (mode != MODE_OWN) {
            binding.trackListTitle.setText(
                when (mode) {
                    MODE_RECENT -> R.string.playlist_recent_title
                    else -> R.string.playlist_favorite_title
                },
            )
        }
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
                    viewModel.local,
                    viewModel.favorites,
                    viewModel.recent,
                ) { _, _, _ -> Unit }.collect { render() }
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
            MODE_OWN -> ownPlaylistId?.let { viewModel.localPlaylist(it)?.tracks }.orEmpty()
            else -> viewModel.favoriteTracks()
        }
        if (mode == MODE_OWN) {
            // 歌单名可能刚被改过，每次渲染都跟着 store 走，不做一次性的 Intent 快照
            val title = ownPlaylistId?.let { viewModel.localPlaylist(it)?.title }.orEmpty()
            binding.trackListTitle.text = title.ifBlank { getString(R.string.local_playlist_title) }
        }
        adapter.submitList(tracks)
        binding.trackListEmpty.visibility =
            if (tracks.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
    }

    /**
     * 从自建歌单里移除。
     *
     * 只摘歌单里的这一份，不动收藏、不动本地文件 —— 用户删的是「分组」，
     * 不是「这首歌」。所以文案要写明这一点，避免误以为收藏也被删了。
     */
    private fun confirmRemove(track: MusicTrack) {
        val id = ownPlaylistId ?: return
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.local_playlist_delete_confirm, track.title))
            .setPositiveButton(R.string.track_remove_from_playlist) { _, _ ->
                viewModel.removeFromPlaylist(id, track.key)
                toast(R.string.track_remove_done)
                render()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
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
        private const val EXTRA_PLAYLIST_ID = "tracklist.playlist_id"
        const val MODE_FAVORITES = "favorites"
        const val MODE_RECENT = "recent"
        const val MODE_OWN = "own"

        fun startFavorites(context: Context) = start(context, MODE_FAVORITES)

        fun startRecent(context: Context) = start(context, MODE_RECENT)

        /** 自建歌单详情。歌单被删了也照常打开，页面会显示空态而不是崩。 */
        fun startOwn(context: Context, playlistId: String) {
            context.startActivity(
                Intent(context, TrackListActivity::class.java)
                    .putExtra(EXTRA_MODE, MODE_OWN)
                    .putExtra(EXTRA_PLAYLIST_ID, playlistId),
            )
        }

        private fun start(context: Context, mode: String) {
            context.startActivity(
                Intent(context, TrackListActivity::class.java).putExtra(EXTRA_MODE, mode),
            )
        }
    }
}