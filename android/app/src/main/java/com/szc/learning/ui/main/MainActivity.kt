package com.szc.learning.ui.main

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnPreDraw
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.szc.learning.R
import com.szc.learning.ui.theme.ThemeManager
import com.szc.learning.databinding.ActivityMainBinding
import com.szc.learning.music.ui.MusicViewModel
import com.szc.learning.music.ui.library.LibraryFragment
import com.szc.learning.music.ui.mine.MineFragment
import com.szc.learning.music.ui.common.showQueueSheet
import com.szc.learning.music.ui.now.NowPlayingActivity
import com.szc.learning.music.ui.player.PlayerFragment
import com.szc.learning.music.ui.recommend.RecommendFragment
import com.szc.learning.music.ui.widget.BottomNavBar
import kotlinx.coroutines.launch
import kotlin.math.max

/**
 * 主页面：四个 tab + 常驻迷你播放器。
 *
 * tab 顺序：播放 / 推荐 / 本地 / 我的。
 *
 * 首页已经拿掉：它原本只是把「每日推荐 + 歌单 + 好歌」堆在一起，
 * 而这些内容现在全部落在推荐页（并且是真·在线推荐，不再是本地洗牌），
 * 留一个首页只会让用户多点一次。默认落在推荐页，打开就有歌可听。
 *
 * Fragment 用 show/hide 而不是 replace —— 切回来时滚动位置还在。
 * Fragment 懒创建：播放器页一旦创建就会读当前曲目的歌词，不能让它开机就跟着启动。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MusicViewModel by viewModels()

    private val tags = listOf(TAG_PLAYER, TAG_RECOMMEND, TAG_LOCAL, TAG_MINE)
    private var currentTab = DEFAULT_TAB
    private var hasTrack = false

    /** 迷你条的进度比例要用时长算，状态里带上来的那个值会一直有效到下一次切歌。 */
    private var miniDurationMs = 0L

    private var insetTop = 0
    private var insetBottom = 0
    private var insetIme = 0

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        if (grants.values.any { it }) viewModel.loadLocal()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // 主题要在 super 之前套上：晚一步，窗口会先按清单里的默认主题创建
        ThemeManager.applyPalette(this)
        super.onCreate(savedInstanceState)
        // 放在 super 之后：AppCompat 这时才把亮暗写进 resources，
        // enableEdgeToEdge 读到的才是最终模式，状态栏图标不会反色
        enableEdgeToEdge()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 启动时把焦点压在根布局上，避免第一个 tab 被系统焦点高亮套一圈
        binding.root.apply {
            isFocusableInTouchMode = true
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                defaultFocusHighlightEnabled = false
            }
            requestFocus()
        }

        // 导航栏保持透明：不要系统给手势条下面加那层白色蒙版
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            window.isNavigationBarContrastEnforced = false
        }

        applyWindowInsets()
        setupTabBar()
        bindMiniPlayer()
        observePlayer()

        val restored = savedInstanceState?.getInt(KEY_TAB, DEFAULT_TAB) ?: DEFAULT_TAB
        showTab(restored)
        val animationsEnabled = android.provider.Settings.Global.getFloat(
            contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f,
        ) > 0f
        if (savedInstanceState == null && animationsEnabled) {
            val launch = LaunchAnimationView(this)
            binding.root.addView(launch, android.view.ViewGroup.LayoutParams(-1, -1))
            // 锚到第一帧再起动画：onCreate 到首帧在慢设备上可能差好几秒，
            // 提前 start() 会让动画的 startTime 过期，首帧一到就直接跳到终态（看起来像没播）。
            launch.doOnPreDraw {
                launchStartedAt = SystemClock.uptimeMillis()
                launch.reveal { if (!isFinishing && !isDestroyed) requestAudioPermission() }
            }
        } else {
            launchStartedAt = 0L
            requestAudioPermission()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_TAB, currentTab)
    }

    override fun onStart() {
        super.onStart()
        // 回到前台静默对齐一次：喜欢的增删、音源账号里歌单的变化都在后台完成，
        // 不给提示（TTL 在 ViewModel 里，5 分钟内不会重复请求）
        viewModel.syncLibrary()
    }

    override fun onResume() {
        super.onResume()
        // 从正在播放/搜索页回来时，主题/材质可能被改过，面板重画一次
        binding.tabBar.invalidateGlass()
    }

    // ---------------------------------------------------------------- 布局

    /**
     * 边到边：页面铺满整屏（内容能滚到通知栏和手势条下面），
     * 顶部标题由各页面自己按 inset 让位；底部 dock 整体抬起，永远压不到手势条。
     * 播放器页例外 —— 封面铺到状态栏下面，内容自己吃 inset。
     */
    private fun applyWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            insetTop = bars.top
            insetBottom = bars.bottom
            insetIme = ime
            layoutInsets()
            insets
        }
    }

    private fun layoutInsets() {
        // tabContainer 不再整体下移：这样内容才能入侵状态栏区域。
        // 顶部渐隐层负责状态栏图标的可读性，页面自己用 paddingTop 避开状态栏。
        val extra = (10 * resources.displayMetrics.density).toInt()
        binding.dock.updatePadding(bottom = max(insetBottom, insetIme) + extra)
    }

    private fun setupTabBar() {
        binding.tabBar.setItems(
            listOf(
                BottomNavBar.Item(R.drawable.ic_nav_player, getString(R.string.tab_player)),
                BottomNavBar.Item(R.drawable.ic_nav_discover, getString(R.string.tab_recommend)),
                BottomNavBar.Item(R.drawable.ic_nav_local, getString(R.string.tab_local)),
                BottomNavBar.Item(R.drawable.ic_nav_mine, getString(R.string.tab_mine)),
            ),
        )
        binding.tabBar.onSelected = { index -> showTab(index) }
        // 渐隐层跟着 dock 的高度走：有无播放行、手势条 inset 不同，dock 高度都不同
        binding.dock.addOnLayoutChangeListener { v, _, top, _, bottom, _, _, _, _ ->
            val dockHeight = bottom - top
            val lead = (36 * resources.displayMetrics.density).toInt()
            binding.bottomFade.glassHeightPx = dockHeight
            binding.bottomFade.updateLayoutParams { height = dockHeight + lead }
        }
    }

    /** 首次进入某 tab 时才创建 Fragment，之后只切显隐。 */
    private fun showTab(index: Int) {
        val target = index.coerceIn(0, tags.lastIndex)
        currentTab = target
        val targetTag = tags[target]
        val manager = supportFragmentManager
        val existing = manager.findFragmentByTag(targetTag)

        val transaction = manager.beginTransaction().setReorderingAllowed(true)
        if (existing == null) {
            transaction.add(R.id.tabContainer, createFragment(targetTag), targetTag)
        } else {
            transaction.show(existing)
        }
        tags.forEach { tag ->
            if (tag != targetTag) manager.findFragmentByTag(tag)?.let(transaction::hide)
        }
        transaction.commitNow()

        binding.tabBar.select(target)
        applyChrome()
    }

    private fun createFragment(tag: String): Fragment = when (tag) {
        TAG_RECOMMEND -> RecommendFragment()
        TAG_LOCAL -> LibraryFragment()
        TAG_MINE -> MineFragment()
        else -> PlayerFragment()
    }

    /** 切到播放器时整页变深色：状态栏/导航栏图标转白、底部条换成深色玻璃。 */
    private fun applyChrome() {
        val dark = isPlayerTab()
        binding.topFade.visibility =
            if (dark) android.view.View.GONE else android.view.View.VISIBLE
        binding.bottomFade.visibility =
            if (dark) android.view.View.GONE else android.view.View.VISIBLE
        binding.tabBar.player.visibility =
            if (!dark && hasTrack) android.view.View.VISIBLE else android.view.View.GONE

        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.isAppearanceLightStatusBars = !dark
        controller.isAppearanceLightNavigationBars = !dark
        layoutInsets()
        binding.tabBar.invalidateGlass()
    }

    private fun isPlayerTab(): Boolean = tags[currentTab] == TAG_PLAYER

    /** 供各页面跳 tab：我的页的"本地音乐""推荐"等。 */
    fun openTab(index: Int) {
        if (index == currentTab) return
        showTab(index)
    }

    fun openLocalTab() = openTab(INDEX_LOCAL)

    fun openRecommendTab() = openTab(INDEX_RECOMMEND)

    fun openPlayerTab() = openTab(INDEX_PLAYER)

    // ---------------------------------------------------------------- 播放

    private fun bindMiniPlayer() {
        binding.tabBar.player.onOpen = {
            startActivity(Intent(this, NowPlayingActivity::class.java))
        }
        binding.tabBar.player.onPlayToggle = { viewModel.toggle() }
        binding.tabBar.player.onNext = { viewModel.next() }
        // 左滑下一首、右滑上一首；「上一首」沿用 iOS 习惯（播过 3 秒先回本曲开头）
        binding.tabBar.player.onPrevious = { viewModel.previous() }
        binding.tabBar.player.onQueue = { showQueueSheet(this, viewModel) }
    }

    private fun observePlayer() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.playerState.collect { state ->
                    miniDurationMs = state.durationMs
                    binding.tabBar.player.bind(
                        state.track,
                        state.isPlaying,
                        fractionOf(viewModel.position.value, state.durationMs),
                    )
                    hasTrack = state.track != null
                    if (isPlayerTab()) {
                        binding.tabBar.player.visibility = android.view.View.GONE
                    }
                }
            }
        }
        // 进度单独一条流：只推进迷你条底部那条线，不重跑 bind
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.position.collect { position ->
                    binding.tabBar.player.bindProgress(fractionOf(position, miniDurationMs))
                }
            }
        }
    }

    private fun fractionOf(positionMs: Long, durationMs: Long): Float =
        if (durationMs <= 0L) 0f else (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)

    // ---------------------------------------------------------------- 权限

    /**
     * 音频权限：Android 13 起是 READ_MEDIA_AUDIO，之前是 READ_EXTERNAL_STORAGE。
     * 13 起通知也要单独申请，否则前台服务的播放通知不显示。
     */
    fun requestAudioPermission() {
        if (audioPermissionGranted()) {
            viewModel.loadLocal()
            return
        }
        val permissions = buildList {
            add(audioPermission())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        permissionLauncher.launch(permissions.toTypedArray())
    }

    fun audioPermissionGranted(): Boolean =
        ContextCompat.checkSelfPermission(this, audioPermission()) == PackageManager.PERMISSION_GRANTED

    private fun audioPermission(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

    companion object {
        private const val KEY_TAB = "aurora.tab"

        /** 打开先落在推荐页：播放器页在没歌可播时是空的，不适合当默认首屏。 */
        const val DEFAULT_TAB = 1

        const val INDEX_PLAYER = 0
        const val INDEX_RECOMMEND = 1
        const val INDEX_LOCAL = 2
        const val INDEX_MINE = 3

        private const val TAG_PLAYER = "tab_player"
        private const val TAG_RECOMMEND = "tab_recommend"
        private const val TAG_LOCAL = "tab_local"
        private const val TAG_MINE = "tab_mine"

        /** 与 [LaunchAnimationView.reveal] 里的 1200ms 一致，改一处要改两处。 */
        private const val LAUNCH_REVEAL_MS = 1200L

        /**
         * 启动层开始淡出的时间点（占整段动画的比例）。
         * [LaunchAnimationView] 用 `alpha = (1 - progress) / 0.18` 收尾，约在 82% 处开始淡出。
         */
        private const val LAUNCH_FADE_START = 0.82f

        /** 这次启动有没有播启动动画；0 表示没播（热启动 / 保存过状态）。 */
        @Volatile
        private var launchStartedAt = 0L

        /**
         * 首屏内容入场应当等待多久（毫秒）。
         *
         * 目的不是「等启动动画播完」，而是让内容恰好在启动层**开始淡出**时动起来：
         * 两个动画重叠一小段，淡出时露出来的就是已经在飞的内容，中间没有空档。
         * 数据比启动动画更晚到（网络慢）时返回 0，直接播。
         */
        fun entranceDelayMs(): Long {
            val started = launchStartedAt
            if (started <= 0L) return 0L
            val fadeAt = started + (LAUNCH_REVEAL_MS * LAUNCH_FADE_START).toLong()
            return (fadeAt - SystemClock.uptimeMillis()).coerceIn(0L, LAUNCH_REVEAL_MS)
        }
    }
}
