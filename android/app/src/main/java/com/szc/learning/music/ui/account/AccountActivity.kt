package com.szc.learning.music.ui.account

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.szc.learning.R
import com.szc.learning.core.common.music.MusicTrack
import com.szc.learning.core.common.toast
import com.szc.learning.databinding.ActivityAccountBinding
import com.szc.learning.music.data.source.SourceAccount
import com.szc.learning.music.ui.MusicViewModel
import com.szc.learning.music.ui.OnlinePlayEvent
import com.szc.learning.ui.main.MainActivity
import com.szc.learning.music.ui.common.bindArt
import com.szc.learning.music.ui.common.sourceLabel
import com.szc.learning.music.ui.track.PlaylistRailAdapter
import com.szc.learning.music.ui.track.TrackCardAdapter
import com.szc.learning.ui.theme.ThemeManager
import com.szc.learning.ui.theme.clearWindowBackground
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 音乐账号页。
 *
 * 只做一件事：把登录后能读到的资料如实摆出来，读不到的部分整块隐藏。
 * 不做签到、不做运营位、不猜数据 —— 平台没给的字段不显示，比显示一个假值诚实。
 *
 * 数据侧的节流和缓存都在 [MusicViewModel.loadAccount] 之后，
 * 这个页面怎么刷都不会把请求打满。
 */
class AccountActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAccountBinding
    private val viewModel: MusicViewModel by viewModels()

    private val playlistAdapter = PlaylistRailAdapter(
        onClick = { playlist -> openSourcePlaylist(playlist) },
        // 点卡片是进详情页，不是直接开播，别挂播放三角
        showPlayBadge = false,
    )

    private val recentAdapter = TrackCardAdapter(
        onClick = { track -> playRecent(track) },
        extraOf = { track ->
            // 排行没有次数就只是一列歌，加上「听了 105 次」才有信息量
            viewModel.accountState.value.account?.recentPlayCounts?.get(track.key)
                ?.let { getString(R.string.account_play_count, formatCount(it)) }
        },
    )

    private var sourceKey: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyPalette(this)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityAccountBinding.inflate(layoutInflater)
        setContentView(binding.root)
        clearWindowBackground()

        sourceKey = intent.getStringExtra(EXTRA_KEY).orEmpty()
        if (sourceKey.isBlank()) {
            // 没有音源 key 就没有账号可显示，直接退出比留一页空白体面
            finish()
            return
        }

        binding.accountPlaylists.layoutManager =
            LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.accountPlaylists.adapter = playlistAdapter

        binding.accountRecent.layoutManager =
            LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.accountRecent.adapter = recentAdapter

        binding.accountBack.setOnClickListener { finish() }
        binding.accountRefresh.setOnClickListener {
            viewModel.loadAccount(sourceKey, force = true)
        }

        installInsets()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.accountState.collect { state -> render(state) }
            }
        }
        viewModel.loadAccount(sourceKey)
    }

    /** 内容铺到系统栏下面，上下留出安全区。 */
    private fun installInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.accountRoot) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(top = bars.top, bottom = bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(binding.accountRoot)
    }

    /*
     * ========================================================================
     * 步骤1：按状态渲染
     * ========================================================================
     * 要点：
     * 1) 没有数据且有请求在跑 → 转圈
     * 2) 没有数据且请求已失败 → 提示登录态可能失效
     * 3) 有数据 → 正常渲染，失败也照常显示（用的是缓存）
     */
    private fun render(state: AccountUiState) {
        val account = state.account
        binding.accountProgress.isVisible = state.loading && account == null
        binding.accountError.isVisible = state.failed && account == null
        binding.accountContent.isVisible = account != null
        if (account == null) {
            // 切平台或首次加载时清掉上一份账号的说明文字，避免张冠李戴
            binding.accountNote.text = null
            return
        }

        renderProfile(account)
        renderStats(account)
        renderFacts(account)
        renderRecent(account)
        renderPlaylists(account)
        renderNote(account)
    }

    /*
     * ========================================================================
     * 步骤2：头部资料（头像 / 昵称 / 会员 / userid）
     * ========================================================================
     * 头像走 Coil，和列表封面同一条链路；没有头像时落回按 userid 生成的渐变块，
     * 不留白方块。
     */
    private fun renderProfile(account: SourceAccount) {
        val nickname = account.nickname.ifBlank {
            getString(R.string.account_user_id, account.userId)
        }
        binding.accountNickname.text = nickname
        binding.accountUserId.text = getString(R.string.account_user_id, account.userId)
        binding.accountSignature.isVisible = account.signature.isNotBlank()
        binding.accountSignature.text = account.signature

        // 2.1 头像：伪装成一条曲目交给 bindArt，复用同一套占位与圆角逻辑
        val avatarTrack = MusicTrack(
            id = "account:${account.sourceKey}:${account.userId}",
            title = nickname,
            artist = "",
            album = "",
            durationMs = 0,
            uri = "",
            artUri = account.avatarUrl.ifBlank { null },
        )
        binding.accountAvatar.bindArt(avatarTrack, sizePx = 160)

        // 2.2 会员徽章：0 普通用户不挂徽章，避免每页都顶一个没信息量的标签
        val vipRes = when (account.vipType) {
            11 -> R.string.account_vip_black
            10 -> R.string.account_vip_package
            else -> 0
        }
        binding.accountVip.isVisible = vipRes != 0
        if (vipRes != 0) binding.accountVip.setText(vipRes)
    }

    /*
     * ========================================================================
     * 步骤3：四格指标
     * ========================================================================
     * 只在平台确实给得出资料时显示整块。酷狗读不到这些，整块就不出现，
     * 而不是摆四个 0。
     */
    private fun renderStats(account: SourceAccount) {
        val visible = account.statsFromPlatform
        binding.accountStats.isVisible = visible
        if (!visible) return

        bindStat(binding.accountStatLevelBox, binding.accountStatLevel, account.level.takeIf { it > 0 }?.toString())
        bindStat(binding.accountStatListenBox, binding.accountStatListen, account.listenSongs.takeIf { it > 0 }?.let(::formatCount))
        // 关注/粉丝是有效信息，0 也要显示 —— 那说明这个号确实没关注过谁
        bindStat(binding.accountStatFollowsBox, binding.accountStatFollows, formatCount(account.follows))
        bindStat(binding.accountStatFansBox, binding.accountStatFans, formatCount(account.followeds))
    }

    private fun bindStat(box: View, value: TextView, text: String?) {
        val shown = !text.isNullOrBlank()
        box.isVisible = shown
        if (shown) value.text = text
    }

    /*
     * ========================================================================
     * 步骤4：明细行
     * ========================================================================
     * 每行独立判断：时间戳为 0（平台没给）就整行隐藏，不留「--」。
     */
    private fun renderFacts(account: SourceAccount) {
        binding.accountFacts.isVisible = account.statsFromPlatform
        if (!account.statsFromPlatform) return

        // 4.1 会员类型：普通用户也要写清楚，这是一条有效信息
        binding.accountRowVip.isVisible = true
        binding.accountFactVip.setText(
            when (account.vipType) {
                11 -> R.string.account_vip_black
                10 -> R.string.account_vip_package
                else -> R.string.account_vip_none
            },
        )

        /*
         * 4.2 歌单数量 / 收藏歌单
         *
         * 平台的 playlistCount 只算「我创建的」，而下面的横滑轨道把收藏进来的
         * 也摆在一起。只写一个 3、下面却有 6 张卡，用户会当成 bug —— 所以拆两行，
         * 数字和轨道对得上。
         */
        val own = account.playlists.count { it.own }
        val subscribed = account.playlists.size - own
        bindFact(
            binding.accountRowPlaylists,
            binding.accountFactPlaylists,
            (if (account.playlists.isNotEmpty()) own else account.playlistCount)
                .takeIf { it > 0 }?.let(::formatCount),
        )
        bindFact(
            binding.accountRowSubscribed,
            binding.accountFactSubscribed,
            subscribed.takeIf { it > 0 }?.let(::formatCount),
        )

        // 4.3 性别 / 生日：平台给了才显示，没填（0）整行不出现
        val genderRes = when (account.gender) {
            1 -> R.string.account_gender_male
            2 -> R.string.account_gender_female
            else -> 0
        }
        bindFact(
            binding.accountRowGender,
            binding.accountFactGender,
            genderRes.takeIf { it != 0 }?.let { getString(it) },
        )
        bindFact(binding.accountRowBirthday, binding.accountFactBirthday, formatDate(account.birthdayMs))

        bindFact(binding.accountRowCreated, binding.accountFactCreated, formatDate(account.createTimeMs))
        bindFact(binding.accountRowLastLogin, binding.accountFactLastLogin, formatDate(account.lastLoginMs))
    }

    private fun bindFact(row: View, value: TextView, text: String?) {
        val shown = !text.isNullOrBlank()
        row.isVisible = shown
        if (shown) value.text = text
    }

    /*
     * ========================================================================
     * 步骤5：最近常听
     * ========================================================================
     * 平台侧的听歌排行（网易云 weekData）。曲目是可以直接播的 ——
     * 点击时按「本账号的排行」整列起播，和本机的最近播放置换是两回事：
     * 这一列来自账号，换手机也还是它。
     */
    private fun renderRecent(account: SourceAccount) {
        val tracks = account.recentTracks
        recentAdapter.submitList(tracks)
        binding.accountRecentHeader.isVisible = tracks.isNotEmpty()
        binding.accountRecent.isVisible = tracks.isNotEmpty()
        // 近一周没听歌时数据层退到了全量排行，标题必须跟着改，不然是撒谎
        binding.accountRecentHeader.setText(
            if (account.recentWeekly) R.string.account_recent else R.string.account_recent_all,
        )
    }

    private fun playRecent(track: MusicTrack) {
        val tracks = viewModel.accountState.value.account?.recentTracks.orEmpty()
        if (tracks.isEmpty()) return
        val index = tracks.indexOfFirst { it.key == track.key }.coerceAtLeast(0)
        viewModel.playTracks(tracks, index) { event ->
            when (event) {
                // 账号页没有迷你播放条，起播后必须跳过去，否则用户只听到声音、屏幕没反应
                is OnlinePlayEvent.Ready -> startActivity(MainActivity.playerIntent(this))
                is OnlinePlayEvent.Failed -> toast(event.message)
            }
        }
    }

    /*
     * ========================================================================
     * 步骤6：我的歌单
     * ========================================================================
     * 卡片只有标题、封面和总数，曲目点进去才拉 —— 平台歌单动辄几百首，
     * 一进页面全下回来既慢又容易被风控。
     */
    private fun renderPlaylists(account: SourceAccount) {
        val cards = account.playlists.map { it.toPlaylistCard() }
        playlistAdapter.submitList(cards)
        // 酷狗连歌单接口都没有，整块不出现；网易云即使 0 个歌单也保留标题给空状态
        binding.accountPlaylistHeader.isVisible = cards.isNotEmpty() || account.statsFromPlatform
        binding.accountPlaylists.isVisible = cards.isNotEmpty()
        binding.accountEmpty.isVisible = account.statsFromPlatform && cards.isEmpty()
    }

    /*
     * ========================================================================
     * 步骤7：底部说明
     * ========================================================================
     * 有资料 → 写数据时间；没资料（酷狗）→ 说明为什么只能看到登录标识。
     */
    private fun renderNote(account: SourceAccount) {
        binding.accountNote.text = when {
            account.statsFromPlatform -> account.fetchedAt.takeIf { it > 0 }?.let {
                getString(
                    R.string.account_cached_at,
                    SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(it)),
                )
            }.orEmpty()
            // 有昵称没统计 = 酷狗：只从 Cookie 解出了头像和昵称
            account.hasProfile -> getString(R.string.account_partial_profile)
            else -> getString(R.string.account_no_profile)
        }
    }

    private fun formatCount(value: Long): String =
        String.format(Locale.getDefault(), "%,d", value)

    private fun formatCount(value: Int): String = formatCount(value.toLong())

    private fun formatDate(timeMs: Long): String? =
        if (timeMs <= 0) null else SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(timeMs))

    override fun onDestroy() {
        binding.accountPlaylists.adapter = null
        binding.accountRecent.adapter = null
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_KEY = "account.source_key"

        fun start(context: Context, sourceKey: String) {
            context.startActivity(
                Intent(context, AccountActivity::class.java).putExtra(EXTRA_KEY, sourceKey),
            )
        }
    }
}
