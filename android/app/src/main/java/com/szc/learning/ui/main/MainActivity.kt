package com.szc.learning.ui.main

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.szc.learning.R
import com.szc.learning.ui.theme.ThemeManager
import com.szc.learning.databinding.ActivityMainBinding
import com.szc.learning.music.ui.MusicViewModel
import com.szc.learning.music.ui.home.HomeFragment
import com.szc.learning.music.ui.library.LibraryFragment
import com.szc.learning.music.ui.mine.MineFragment
import com.szc.learning.music.ui.now.NowPlayingActivity
import com.szc.learning.music.ui.player.PlayerFragment
import com.szc.learning.music.ui.recommend.RecommendFragment
import com.szc.learning.music.ui.widget.BottomNavBar
import kotlinx.coroutines.launch
import kotlin.math.max

/**
 * 主页面：五个 tab + 常驻迷你播放器。
 *
 * 五个 tab 的顺序刻意对齐参考产品：首页 / 播放 / 推荐 / 本地 / 我的。
 * 第二个 tab 从「MV 短视频流」改成了真正的播放器：大封面、内嵌歌词、上下滑切歌。
 *
 * Fragment 用 show/hide 而不是 replace —— 切回首页时滚动位置还在，
 * 这对"边听边翻"的用法是必要的。Fragment 也是懒创建的：
 * 播放器页一旦创建就会读当前曲目的歌词，不能让它开机就跟着启动。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MusicViewModel by viewModels()

    private val tags = listOf(TAG_HOME, TAG_PLAYER, TAG_RECOMMEND, TAG_LOCAL, TAG_MINE)
    private var currentTab = 0
    private var hasTrack = false

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
        wireGlass()
        bindMiniPlayer()
        observePlayer()

        val restored = savedInstanceState?.getInt(KEY_TAB, 0) ?: 0
        showTab(restored)
        val animationsEnabled = android.provider.Settings.Global.getFloat(
            contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f,
        ) > 0f
        if (savedInstanceState == null && animationsEnabled) {
            val launch = LaunchAnimationView(this)
            binding.root.addView(launch, android.view.ViewGroup.LayoutParams(-1, -1))
            launch.reveal { if (!isFinishing && !isDestroyed) requestAudioPermission() }
        } else {
            requestAudioPermission()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_TAB, currentTab)
    }

    override fun onResume() {
        super.onResume()
        // 从正在播放/搜索页回来时，列表内容可能已经变了，玻璃重新采样一次
        binding.tabBar.invalidateGlass()
        binding.miniPlayer.refreshGlass()
    }

    /**
     * 玻璃的模糊采样源统一指向页面容器 [ActivityMainBinding.tabContainer]。
     *
     * 不能指向窗口或 dock 自己：PixelCopy 式的整窗截图会把玻璃自身也截进去，
     * 每采一次就糊一层，滚两下就变成一块不透明白板。tabContainer 在 dock 下面，
     * 正好是"播放栏身后的内容"。
     */
    private fun wireGlass() {
        binding.miniPlayer.backdropSource = binding.tabContainer
        binding.tabBar.backdropSource = binding.tabContainer
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
                BottomNavBar.Item(R.drawable.ic_nav_home, getString(R.string.tab_home)),
                BottomNavBar.Item(R.drawable.ic_nav_player, getString(R.string.tab_mv)),
                BottomNavBar.Item(R.drawable.ic_nav_discover, getString(R.string.tab_recommend)),
                BottomNavBar.Item(R.drawable.ic_nav_local, getString(R.string.tab_local)),
                BottomNavBar.Item(R.drawable.ic_nav_mine, getString(R.string.tab_mine)),
            ),
        )
        binding.tabBar.onSelected = { index -> showTab(index) }
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
        TAG_PLAYER -> PlayerFragment()
        TAG_RECOMMEND -> RecommendFragment()
        TAG_LOCAL -> LibraryFragment()
        TAG_MINE -> MineFragment()
        else -> HomeFragment()
    }

    /** 切到播放器时整页变深色：状态栏/导航栏图标转白、底部条换成深色玻璃。 */
    private fun applyChrome() {
        val dark = isPlayerTab()
        binding.tabBar.setDarkMode(dark)
        binding.topFade.visibility =
            if (dark) android.view.View.GONE else android.view.View.VISIBLE
        binding.miniPlayer.visibility =
            if (!dark && hasTrack) android.view.View.VISIBLE else android.view.View.GONE

        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.isAppearanceLightStatusBars = !dark
        controller.isAppearanceLightNavigationBars = !dark
        layoutInsets()
        binding.tabBar.invalidateGlass()
        binding.miniPlayer.refreshGlass()
    }

    private fun isPlayerTab(): Boolean = tags[currentTab] == TAG_PLAYER

    /** 供各页面跳 tab：首页的"视频"、我的页的"本地音乐"等。 */
    fun openTab(index: Int) {
        if (index == currentTab) return
        showTab(index)
    }

    fun openLocalTab() = openTab(INDEX_LOCAL)

    fun openRecommendTab() = openTab(INDEX_RECOMMEND)

    // ---------------------------------------------------------------- 播放

    private fun bindMiniPlayer() {
        binding.miniPlayer.onOpen = {
            startActivity(Intent(this, NowPlayingActivity::class.java))
        }
        binding.miniPlayer.onPlayToggle = { viewModel.toggle() }
        binding.miniPlayer.onNext = { viewModel.next() }
    }

    private fun observePlayer() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.playerState.collect { state ->
                    binding.miniPlayer.bind(state.track, state.isPlaying, state.progress)
                    hasTrack = state.track != null
                    if (isPlayerTab()) {
                        binding.miniPlayer.visibility = android.view.View.GONE
                    }
                }
            }
        }
    }

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

        const val INDEX_HOME = 0
        const val INDEX_PLAYER = 1
        const val INDEX_RECOMMEND = 2
        const val INDEX_LOCAL = 3
        const val INDEX_MINE = 4

        private const val TAG_HOME = "tab_home"
        private const val TAG_PLAYER = "tab_player"
        private const val TAG_RECOMMEND = "tab_recommend"
        private const val TAG_LOCAL = "tab_local"
        private const val TAG_MINE = "tab_mine"
    }
}
