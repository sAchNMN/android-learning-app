package com.szc.learning.music.ui.mine

import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import androidx.appcompat.app.AlertDialog
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.szc.learning.R
import com.szc.learning.core.common.AppResult
import com.szc.learning.core.common.music.MusicTrack
import com.szc.learning.core.common.toast
import com.szc.learning.databinding.FragmentMineBinding
import com.szc.learning.music.ui.MusicViewModel
import com.szc.learning.music.ui.OnlinePlayEvent
import com.szc.learning.music.ui.common.CoverArt
import com.szc.learning.music.ui.common.showListenStats
import com.szc.learning.music.ui.glass.GlassDialog
import com.szc.learning.music.ui.motion.installSpringPress
import com.szc.learning.music.ui.source.SourceActivity
import com.szc.learning.music.ui.track.TrackCardAdapter
import com.szc.learning.music.ui.track.TrackListActivity
import com.szc.learning.music.ui.widget.dp
import com.szc.learning.ui.main.MainActivity
import com.szc.learning.ui.theme.ThemeDialog
import kotlinx.coroutines.launch

/**
 * 我的页：我喜欢 / 入口 / 最近播放。
 *
 * 每样东西只出现一次：本地、播放器在底栏，主题/材质/关于在标题栏，
 * 宫格里只放别处进不去的入口。
 *
 * 这里没有会员、积分、签到、免流、广告位 —— 参考产品上那一片运营入口
 * 对自用播放器没有意义，全部换成本地功能。数据也都是真的：
 * 听歌时长按秒累计，收藏落 SharedPreferences，最近播放走播放器回调。
 */
class MineFragment : Fragment(R.layout.fragment_mine) {

    private var _binding: FragmentMineBinding? = null
    private val binding get() = requireNotNull(_binding)

    private val viewModel: MusicViewModel by lazy {
        ViewModelProvider(requireActivity())[MusicViewModel::class.java]
    }

    private val entryAdapter = MineEntryAdapter(MineEntryAdapter.defaults()) { entry ->
        handleEntry(entry)
    }

    private val recentAdapter = TrackCardAdapter { track ->
        val recent = viewModel.recent.value
        val index = recent.indexOfFirst { it.key == track.key }
        viewModel.play(recent, index.coerceAtLeast(0))
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentMineBinding.bind(view)

        binding.mineEntries.apply {
            layoutManager = GridLayoutManager(requireContext(), MineEntryAdapter.defaults().size)
            adapter = entryAdapter
            setHasFixedSize(true)
        }
        binding.mineRecent.apply {
            layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
            adapter = recentAdapter
            setHasFixedSize(true)
        }

        binding.mineFavoritesCover.setImageDrawable(
            BitmapDrawable(resources, CoverArt.of("aurora:favorites", 200)),
        )

        binding.mineTheme.setOnClickListener {
            (activity as? MainActivity)?.let { ThemeDialog.show(it) }
        }
        binding.mineGlass.setOnClickListener {
            GlassDialog.show(requireContext())
        }
        binding.mineFavoritesCard.setOnClickListener { openFavorites() }
        binding.mineFavoritesPlay.setOnClickListener { playFavorites() }
        binding.mineRecentHeader.setOnClickListener {
            TrackListActivity.startRecent(requireContext())
        }
        binding.mineFavoritesPlay.installSpringPress(0.88f)
        binding.mineFavoritesCard.installSpringPress(0.985f)
        installInsets()

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.recent.collect { render() }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                // 只有手动点「同步」才走这里；自动同步是静默的，不会弹提示
                viewModel.syncNotice.collect { message -> context?.toast(message) }
            }
        }
        // 进「我的」页自动同步一次（5 分钟内不重复请求，见 ViewModel 的 TTL）
        viewModel.syncLibrary()
        render()
    }

    /** 内容铺到系统栏下面；静态标题和底部内容按 inset 让位。 */
    private fun installInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val top = bars.top + view.context.dp(6f).toInt()
            val bottom = view.context.dp(160f).toInt() + bars.bottom
            view.updatePadding(top = top, bottom = bottom)
            insets
        }
        ViewCompat.requestApplyInsets(binding.root)
    }

    private fun render() {
        val recent = viewModel.recent.value
        recentAdapter.submitList(recent.take(10))
        // 没有最近播放就整段不出现，不留空标题
        binding.mineRecentHeader.isVisible = recent.isNotEmpty()
        binding.mineRecent.isVisible = recent.isNotEmpty()
    }

    private fun handleEntry(entry: MineEntry) {
        when (entry.key) {
            MineEntryAdapter.KEY_STATS -> showListenStats(
                requireContext(),
                viewModel.listenMinutes.value,
                viewModel.favorites.value.size,
                viewModel.recent.value.size,
            )

            MineEntryAdapter.KEY_SOURCES -> SourceActivity.start(requireContext())

            MineEntryAdapter.KEY_IMPORT_SHARE -> showImportShareDialog()

            else -> Unit
        }
    }

    /**
     * 导入分享码。
     *
     * 这一项**故意不做离线兜底**：码存在服务端，没有后端就没有码可查。
     * 所以后端不在时要直接说「导入失败：网络连接失败」而不是假装成功。
     * 反过来，导入进来的歌单会立刻落到 [com.szc.learning.music.data.SharedPlaylistStore]，
     * 之后即使后端关掉也能在推荐页看到它。
     */
    private fun showImportShareDialog() {
        val density = resources.displayMetrics.density
        val padding = (20 * density).toInt()
        val input = EditText(requireContext()).apply {
            hint = getString(R.string.import_share_hint)
            // 大写 + 单行：分享码的字符集只有数字和大写字母，让键盘一开始就是对的
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            setSingleLine()
        }
        val container = FrameLayout(requireContext()).apply {
            setPadding(padding, padding / 2, padding, 0)
            addView(
                input,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }

        AlertDialog.Builder(requireContext())
            .setTitle(R.string.import_share_title)
            .setView(container)
            .setPositiveButton(R.string.import_share_action) { _, _ ->
                val code = input.text?.toString()?.trim().orEmpty()
                if (code.isBlank()) {
                    requireContext().toast(R.string.import_share_empty)
                    return@setPositiveButton
                }
                viewLifecycleOwner.lifecycleScope.launch {
                    when (val result = viewModel.importShare(code)) {
                        is AppResult.Success -> requireContext().toast(
                            getString(
                                R.string.import_share_done,
                                result.data.title,
                                result.data.tracks.size,
                            ),
                        )

                        is AppResult.Failure -> requireContext().toast(
                            getString(R.string.import_share_failed, result.message),
                        )
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun openFavorites() {
        TrackListActivity.startFavorites(requireContext())
    }

    private fun playFavorites() {
        val favorites = viewModel.favoriteTracks()
        if (favorites.isEmpty()) {
            requireContext().toast(R.string.mine_empty_favorites)
            return
        }
        // 收藏里可能是在线曲目，交给 playTracks 先换地址
        viewModel.playTracks(favorites, 0) { event ->
            if (event is OnlinePlayEvent.Failed) requireContext().toast(event.message)
        }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}