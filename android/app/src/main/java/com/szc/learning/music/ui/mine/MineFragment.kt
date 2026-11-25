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
import com.szc.learning.music.data.source.SourceAccount
import com.szc.learning.music.ui.MusicViewModel
import com.szc.learning.music.ui.OnlinePlayEvent
import com.szc.learning.music.ui.account.AccountActivity
import com.szc.learning.music.ui.account.openSourcePlaylist
import com.szc.learning.music.ui.account.toPlaylistCard
import com.szc.learning.music.ui.common.CoverArt
import com.szc.learning.music.ui.common.bindArt
import com.szc.learning.music.ui.common.showListenStats
import com.szc.learning.music.ui.common.sourceLabel
import com.szc.learning.music.ui.glass.GlassDialog
import com.szc.learning.music.ui.motion.installSpringPress
import com.szc.learning.music.ui.room.RoomActivity
import com.szc.learning.music.ui.source.SourceActivity
import com.szc.learning.music.ui.track.PlaylistRailAdapter
import com.szc.learning.music.ui.track.TrackCardAdapter
import com.szc.learning.music.ui.track.TrackListActivity
import com.szc.learning.music.ui.widget.dp
import com.szc.learning.ui.main.MainActivity
import com.szc.learning.ui.theme.ThemeDialog
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * 我的页：音乐账号 / 我喜欢 / 入口 / 最近播放。
 *
 * 顶部账号卡取音源顺序里第一个已登录的平台，下面接它的歌单 ——
 * 更细的资料（等级、明细、听歌排行）都在点进去的账号页，
 * 这一页只放日常会顺手点的那一层。
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

    private val recentAdapter = TrackCardAdapter(
        onClick = { track ->
            val recent = viewModel.recent.value
            val index = recent.indexOfFirst { it.key == track.key }
            viewModel.play(recent, index.coerceAtLeast(0))
        },
    )

    /** 账号歌单：卡片只有标题和封面，点进详情页才拉曲目，所以不挂播放三角。 */
    private val playlistAdapter = PlaylistRailAdapter(
        onClick = { playlist -> requireContext().openSourcePlaylist(playlist) },
        showPlayBadge = false,
    )

    /**
     * 「我的」页顶部要显示哪个账号。
     *
     * 规则就是音源管理页的顺序：第一个**已登录且启用**的平台。
     * 不另设「显示哪个账号」的偏好 —— 用户调顺序时，这里跟着变，
     * 两处配置不一致比少一个开关更让人困惑。
     */
    private var accountKey: String? = null

    /** 缓存一份本页自己渲染的账号，切 tab 回来不会闪空白。 */
    private var account: SourceAccount? = null

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
        binding.minePlaylists.apply {
            layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
            adapter = playlistAdapter
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
        // 歌单标题能进账号页——排行也收在那里
        binding.minePlaylistHeader.setOnClickListener { openAccount() }
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
                // 音源顺序/登录态变了要重新挑账号，账号状态回来了要重画
                launch { viewModel.sources.collect { renderAccount() } }
                launch { viewModel.accountState.collect { onAccountState() } }
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
        // 顶部账号卡要靠音源配置挑平台，先确保它已经有内容（纯本机读取）
        viewModel.loadSources()
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

    override fun onResume() {
        super.onResume()
        // 音源管理是独立 Activity，它改的是**自己那个 ViewModel** 和本机配置，
        // 主界面的 _sources 副本不会自己更新 —— 从那边调完顺序回来必须重读一次，
        // 否则顶部账号卡还停在上一个平台。
        viewModel.loadSources()
    }

    private fun render() {
        val recent = viewModel.recent.value
        recentAdapter.submitList(recent.take(10))
        // 没有最近播放就整段不出现，不留空标题
        binding.mineRecentHeader.isVisible = recent.isNotEmpty()
        binding.mineRecent.isVisible = recent.isNotEmpty()
    }

    /*
     * ========================================================================
     * 顶部的音乐账号
     * ========================================================================
     * 三个决定：
     * 1) 取音源顺序里第一个「已登录 + 已启用」的平台，用户调顺序这里就跟着变；
     * 2) 只渲染 [accountKey] 对应的账号 —— 账号页可能正在看另一个平台，
     *    共享的 accountState 被它改写过，直接拿来用会把酷狗的资料挂在网易云那一行；
     * 3) 拿不到就整块不出现，不放「加载失败」占位：这一块是锦上添花，不是主内容。
     */
    private fun renderAccount() {
        val target = viewModel.sources.value
            .firstOrNull { it.enabled && it.hasCookie }
            ?.key
            .orEmpty()

        if (target.isBlank()) {
            accountKey = null
            account = null
            hideAccount()
            return
        }

        if (target != accountKey) {
            // 换人了：先清掉上一个人的头像、昵称和歌单再等新数据
            accountKey = target
            account = null
            hideAccount()
            viewModel.loadAccount(target)
            return
        }

        val current = account
        if (current == null) {
            if (!viewModel.accountState.value.loading) viewModel.loadAccount(target)
            return
        }
        bindAccount(target, current)
    }

    private fun onAccountState() {
        val state = viewModel.accountState.value
        // 与本页无关的账号（比如用户刚从账号页看了另一个平台）直接忽略
        if (state.sourceKey != accountKey) return
        val data = state.account
        if (data != null) {
            account = data
            bindAccount(state.sourceKey, data)
        } else if (state.failed) {
            hideAccount()
        }
    }

    /** 没有账号时账号相关的两块一起收起来，不留空标题。 */
    private fun hideAccount() {
        binding.mineAccountCard.isVisible = false
        binding.minePlaylistHeader.isVisible = false
        binding.minePlaylists.isVisible = false
    }

    private fun bindAccount(key: String, account: SourceAccount) {
        val name = account.nickname.ifBlank {
            getString(R.string.account_user_id, account.userId)
        }
        binding.mineAccountName.text = name
        binding.mineAccountSub.text = getString(
            R.string.mine_account_sub,
            sourceLabel(requireContext(), key),
            account.userId,
        )

        val vipRes = when (account.vipType) {
            11 -> R.string.account_vip_black
            10 -> R.string.account_vip_package
            else -> 0
        }
        binding.mineAccountVip.isVisible = vipRes != 0
        if (vipRes != 0) binding.mineAccountVip.setText(vipRes)

        // 伪造一条曲目交给 bindArt，复用账号页同一套头像占位与圆角
        val avatarTrack = MusicTrack(
            id = "mine-account:$key",
            title = name,
            artist = "",
            album = "",
            durationMs = 0,
            uri = "",
            artUri = account.avatarUrl.ifBlank { null },
        )
        binding.mineAccountAvatar.bindArt(avatarTrack, sizePx = 160)

        val stats = if (account.statsFromPlatform) buildStats(account)
        else getString(R.string.mine_account_no_stats)
        binding.mineAccountStats.isVisible = stats.isNotBlank()
        binding.mineAccountStats.text = stats

        binding.mineAccountCard.isVisible = true
        binding.mineAccountCard.setOnClickListener { openAccount() }

        bindPlaylists(account)
    }

    /*
     * ========================================================================
     * 账号的歌单
     * ========================================================================
     * 只在账号卡显示时才出现，数据跟着 [accountKey] 走。
     * 平台不给就整段隐藏：酷狗没有歌单接口，硬留一个空标题只会误导。
     *
     * 听歌排行故意不放在这里 —— 它是「翻账号」时才关心的东西，
     * 留在账号页即可，否则「我的」会变成账号页的第二份副本。
     */
    private fun bindPlaylists(account: SourceAccount) {
        val cards = account.playlists.map { it.toPlaylistCard() }
        playlistAdapter.submitList(cards)
        binding.minePlaylistHeader.isVisible = cards.isNotEmpty()
        binding.minePlaylists.isVisible = cards.isNotEmpty()
    }

    private fun openAccount() {
        val key = accountKey ?: return
        AccountActivity.start(requireContext(), key)
    }

    /** 「等级 8 · 累计听歌 4,719 · 关注 15 · 粉丝 10」。为 0 的项不硬凑。 */
    private fun buildStats(account: SourceAccount): String {
        val parts = mutableListOf<String>()
        if (account.level > 0) {
            parts += getString(R.string.account_stat_level) + " " + account.level
        }
        if (account.listenSongs > 0) {
            parts += getString(R.string.account_stat_listen) + " " + formatCount(account.listenSongs)
        }
        // 关注/粉丝 0 也是有效信息：说明这个号确实没关注过谁
        parts += getString(R.string.account_stat_follows) + " " + account.follows
        parts += getString(R.string.account_stat_fans) + " " + account.followeds
        return parts.joinToString(" · ")
    }

    private fun formatCount(value: Long): String =
        String.format(Locale.getDefault(), "%,d", value)

    private fun handleEntry(entry: MineEntry) {
        when (entry.key) {
            MineEntryAdapter.KEY_TOGETHER ->
                startActivity(android.content.Intent(requireContext(), RoomActivity::class.java))

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