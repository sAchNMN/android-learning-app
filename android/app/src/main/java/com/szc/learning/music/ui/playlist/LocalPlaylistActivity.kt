package com.szc.learning.music.ui.playlist

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.EditText
import android.widget.FrameLayout
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.recyclerview.widget.LinearLayoutManager
import com.szc.learning.R
import com.szc.learning.core.common.toast
import com.szc.learning.databinding.ActivityLocalPlaylistsBinding
import com.szc.learning.music.data.LocalPlaylist
import com.szc.learning.music.ui.MusicViewModel
import com.szc.learning.music.ui.track.TrackListActivity
import com.szc.learning.music.ui.widget.dp
import com.szc.learning.ui.theme.ThemeManager

/**
 * 自建歌单管理页。
 *
 * 只做歌单这一层的增删改，曲目增删在详情页做（[TrackListActivity] 的 own 模式）——
 * 把「挑歌」和「管歌单」拆成两个页面，比在一个页面里叠两套列表清爽。
 */
class LocalPlaylistActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLocalPlaylistsBinding
    private val viewModel: MusicViewModel by viewModels()

    private val adapter = LocalPlaylistAdapter(
        onClick = { playlist -> TrackListActivity.startOwn(this, playlist.id) },
        onMenu = { playlist -> showMenu(playlist) },
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyPalette(this)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityLocalPlaylistsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (android.os.Build.VERSION.SDK_INT >= 29) {
            window.isNavigationBarContrastEnforced = false
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.localPlaylistRoot) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(top = bars.top, bottom = bars.bottom)
            insets
        }

        binding.localPlaylistBack.setOnClickListener { finish() }
        binding.localPlaylistCreate.setOnClickListener { showCreateDialog() }
        binding.localPlaylistList.layoutManager = LinearLayoutManager(this)
        binding.localPlaylistList.adapter = adapter
    }

    override fun onResume() {
        super.onResume()
        // 详情页可能改过曲目，回来必须重读 —— store 不是 Flow
        render()
    }

    private fun render() {
        val playlists = viewModel.localPlaylists()
        adapter.submit(playlists)
        binding.localPlaylistEmpty.visibility =
            if (playlists.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
    }

    private fun showCreateDialog() {
        val input = buildInput("")
        AlertDialog.Builder(this)
            .setTitle(R.string.local_playlist_new)
            .setView(wrap(input))
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val title = input.text?.toString().orEmpty()
                val created = viewModel.createPlaylist(title)
                toast(getString(R.string.local_playlist_created, created.title))
                render()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showMenu(playlist: LocalPlaylist) {
        val options = arrayOf(
            getString(R.string.local_playlist_rename),
            getString(R.string.local_playlist_delete),
        )
        AlertDialog.Builder(this)
            .setTitle(playlist.title)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showRenameDialog(playlist)
                    else -> confirmDelete(playlist)
                }
            }
            .show()
    }

    private fun showRenameDialog(playlist: LocalPlaylist) {
        val input = buildInput(playlist.title)
        AlertDialog.Builder(this)
            .setTitle(R.string.local_playlist_rename)
            .setView(wrap(input))
            .setPositiveButton(android.R.string.ok) { _, _ ->
                viewModel.renamePlaylist(playlist.id, input.text?.toString().orEmpty())
                render()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmDelete(playlist: LocalPlaylist) {
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.local_playlist_delete_confirm, playlist.title))
            .setPositiveButton(R.string.local_playlist_delete) { _, _ ->
                viewModel.deletePlaylist(playlist.id)
                toast(R.string.local_playlist_deleted)
                render()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun buildInput(initial: String): EditText = EditText(this).apply {
        hint = getString(R.string.local_playlist_new_hint)
        setText(initial)
        setSelection(text.length)
        setSingleLine()
    }

    /** 直接 setView(EditText) 会贴到对话框边缘，包一层给左右留白。 */
    private fun wrap(input: EditText): FrameLayout = FrameLayout(this).apply {
        setPadding(dp(22f).toInt(), dp(8f).toInt(), dp(22f).toInt(), 0)
        addView(input)
    }

    companion object {
        fun start(context: Context) {
            context.startActivity(Intent(context, LocalPlaylistActivity::class.java))
        }
    }
}
