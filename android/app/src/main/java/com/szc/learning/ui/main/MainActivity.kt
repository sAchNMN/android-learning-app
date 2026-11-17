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
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.szc.learning.LearningApp
import com.szc.learning.R
import com.szc.learning.databinding.ActivityMainBinding
import com.szc.learning.music.ui.MusicViewModel
import com.szc.learning.music.ui.cloud.CloudFragment
import com.szc.learning.music.ui.home.HomeFragment
import com.szc.learning.music.ui.library.LibraryFragment
import com.szc.learning.music.ui.now.NowPlayingActivity
import com.szc.learning.music.ui.search.SearchFragment
import com.szc.learning.music.ui.widget.GlassTabBar
import kotlinx.coroutines.launch
import kotlin.math.max

/**
 * 主页面：四个 tab + 常驻迷你播放器。
 *
 * Fragment 用 show/hide 而不是 replace —— 切回首页时滚动位置还在，
 * 这对"边听边翻"的用法是必要的。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MusicViewModel by viewModels()

    private val tags = listOf(TAG_HOME, TAG_SEARCH, TAG_CLOUD, TAG_LIBRARY)
    private var currentTab = 0

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        if (grants.values.any { it }) viewModel.loadLocal()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        applyWindowInsets()
        setupTabBar()
        bindMiniPlayer()
        observePlayer()
        requestAudioPermission()

        val restored = savedInstanceState?.getInt(KEY_TAB, 0) ?: 0
        showTab(restored)
        binding.tabBar.select(restored)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_TAB, currentTab)
    }

    // ---------------------------------------------------------------- 布局

    /**
     * 边到边：状态栏给内容让位，导航栏/键盘给底部悬浮层让位。
     * 底部同时处理导航栏和输入法，是因为开启 decorFitsSystemWindows=false 之后
     * adjustResize 不再自动缩窗口，键盘得自己让。
     */
    private fun applyWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            val extra = (10 * resources.displayMetrics.density).toInt()
            binding.tabContainer.updatePadding(top = bars.top)
            binding.dock.updatePadding(bottom = max(bars.bottom, ime) + extra)
            insets
        }
    }

    private fun setupTabBar() {
        binding.tabBar.setTabs(
            listOf(
                GlassTabBar.Tab(R.drawable.ic_tab_home, getString(R.string.tab_home)),
                GlassTabBar.Tab(R.drawable.ic_tab_search, getString(R.string.tab_search)),
                GlassTabBar.Tab(R.drawable.ic_tab_cloud, getString(R.string.tab_cloud)),
                GlassTabBar.Tab(R.drawable.ic_tab_library, getString(R.string.tab_library)),
            ),
        )
        binding.tabBar.onTabSelected = { index -> showTab(index) }
    }

    /** 首次进入某 tab 时才创建 Fragment，之后只切显隐。 */
    private fun showTab(index: Int) {
        val target = index.coerceIn(0, tags.lastIndex)
        currentTab = target
        val targetTag = tags[target]
        val transaction = supportFragmentManager.beginTransaction()

        tags.forEach { tag ->
            val existing = supportFragmentManager.findFragmentByTag(tag)
            if (existing == null) {
                val created = createFragment(tag)
                transaction.add(R.id.tabContainer, created, tag)
                if (tag != targetTag) transaction.hide(created)
            } else if (tag == targetTag) {
                transaction.show(existing)
            } else {
                transaction.hide(existing)
            }
        }
        transaction.commitNow()
        binding.tabBar.select(target)
    }

    private fun createFragment(tag: String): Fragment = when (tag) {
        TAG_SEARCH -> SearchFragment()
        TAG_CLOUD -> CloudFragment()
        TAG_LIBRARY -> LibraryFragment()
        else -> HomeFragment()
    }

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

        private const val TAG_HOME = "tab_home"
        private const val TAG_SEARCH = "tab_search"
        private const val TAG_CLOUD = "tab_cloud"
        private const val TAG_LIBRARY = "tab_library"
    }
}
