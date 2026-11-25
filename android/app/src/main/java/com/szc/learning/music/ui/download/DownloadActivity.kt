package com.szc.learning.music.ui.download

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
import com.szc.learning.core.common.toast
import com.szc.learning.databinding.ActivityDownloadBinding
import com.szc.learning.music.data.download.OfflineTrack
import com.szc.learning.music.ui.MusicViewModel
import com.szc.learning.music.ui.OnlinePlayEvent
import com.szc.learning.ui.main.MainActivity
import com.szc.learning.ui.theme.ThemeManager
import kotlinx.coroutines.launch

/**
 * 离线下载管理页。
 *
 * 只做三件事：看已下载、删、批量把收藏拉下来。
 * 单曲下载没有独立入口 —— 用「下载收藏」一次性铺满，比让用户一首首点更符合真实用法：
 * 想离线听的人通常是「先把喜欢的存下来」，不是「精确挑三首」。
 *
 * 下载本身在 [com.szc.learning.music.data.download.DownloadManager] 里排队执行，
 * 页面关掉也不影响下载继续跑。
 */
class DownloadActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDownloadBinding
    private val viewModel: MusicViewModel by viewModels()

    private val adapter = DownloadAdapter(
        onClick = { entry -> playEntry(entry) },
        onDelete = { entry -> confirmDelete(entry) },
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyPalette(this)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityDownloadBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (android.os.Build.VERSION.SDK_INT >= 29) {
            window.isNavigationBarContrastEnforced = false
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.downloadRoot) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(top = bars.top, bottom = bars.bottom)
            insets
        }

        binding.downloadBack.setOnClickListener { finish() }
        binding.downloadFavorites.setOnClickListener { downloadFavorites() }
        binding.downloadList.layoutManager = LinearLayoutManager(this)
        binding.downloadList.adapter = adapter

        // 进度与完成状态从 tasks 流里来，store 不是 Flow，靠它触发重读
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.downloadTasks.collect { tasks ->
                    render(tasks)
                }
            }
        }
        render(viewModel.downloadTasks.value)
    }

    private fun render(tasks: Map<String, com.szc.learning.music.data.download.DownloadTask>) {
        val entries = viewModel.downloadEntries()
        adapter.submit(entries, tasks)
        binding.downloadEmpty.visibility = if (entries.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
        binding.downloadSummary.text = if (entries.isEmpty()) {
            getString(R.string.download_summary_empty)
        } else {
            getString(
                R.string.download_summary,
                entries.size,
                formatBytes(viewModel.downloadTotalBytes()),
            )
        }
    }

    /**
     * 把收藏里的在线曲目全部入队。
     *
     * 本地曲目已经在手机上，不需要"下载"，由 store 侧过滤掉；
     * 已经下过的也会被跳过，所以多点几次只是把新收藏补进来。
     */
    private fun downloadFavorites() {
        val tracks = viewModel.favoriteTracks().filter { it.uri.isNotBlank() || it.sourceKey.isNotBlank() }
        if (tracks.isEmpty()) {
            toast(R.string.download_nothing)
            return
        }
        viewModel.enqueueDownload(tracks)
        toast(getString(R.string.download_queued, tracks.size))
    }

    private fun playEntry(entry: OfflineTrack) {
        val track = viewModel.downloadTrack(entry.key)
        if (track == null) {
            // 索引有、文件没了（系统清理过）：顺手把脏数据摘掉，别让用户反复点一首放不了的歌
            viewModel.deleteDownload(entry.key)
            toast(R.string.download_nothing)
            render(viewModel.downloadTasks.value)
            return
        }
        viewModel.playTracks(listOf(track), 0) { event ->
            when (event) {
                is OnlinePlayEvent.Ready -> startActivity(MainActivity.playerIntent(this))
                is OnlinePlayEvent.Failed -> toast(event.message)
            }
        }
    }

    private fun confirmDelete(entry: OfflineTrack) {
        AlertDialog.Builder(this)
            .setMessage(R.string.download_delete_confirm)
            .setPositiveButton(R.string.download_delete) { _, _ ->
                viewModel.deleteDownload(entry.key)
                toast(R.string.download_deleted)
                render(viewModel.downloadTasks.value)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    companion object {
        fun start(context: Context) {
            context.startActivity(Intent(context, DownloadActivity::class.java))
        }
    }
}
