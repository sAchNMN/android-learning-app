package com.szc.learning.music.ui.search

import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.LinearLayoutManager
import com.szc.learning.R
import com.szc.learning.core.common.music.MusicTrack
import com.szc.learning.core.common.toast
import com.szc.learning.databinding.ActivitySearchBinding
import com.szc.learning.music.ui.MusicViewModel
import com.szc.learning.music.ui.OnlinePlayEvent
import com.szc.learning.ui.theme.ThemeManager
import com.szc.learning.music.ui.track.SectionHeaderAdapter
import com.szc.learning.music.ui.track.TrackAdapter
import com.szc.learning.ui.main.MainActivity
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * 搜索页。
 *
 * 三路结果同屏：
 * - 在线：服务端聚合网易云 / 酷狗，行尾带平台胶囊；点歌时先换地址再播
 * - 云端：本站曲库
 * - 本地：MediaStore
 *
 * 在线结果和「我的音乐」用分区标题隔开，点击各自维护自己的 index ——
 * 混成一个 list 之后，收藏/播放很容易点到隔壁分区的歌。
 */
class SearchActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySearchBinding
    private val viewModel: MusicViewModel by viewModels()

    private var localResults: List<MusicTrack> = emptyList()

    private val onlineHeader = SectionHeaderAdapter()
    private val localHeader = SectionHeaderAdapter()

    private val onlineAdapter = TrackAdapter { index ->
        val track = viewModel.online.value.getOrNull(index) ?: return@TrackAdapter
        hideKeyboard()
        viewModel.playOnline(track) { event ->
            when (event) {
                is OnlinePlayEvent.Ready -> {
                    event.notice?.let { toast(it) }
                    startActivity(MainActivity.playerIntent(this))
                }

                is OnlinePlayEvent.Failed -> toast(event.message)
            }
        }
    }

    private val localAdapter = TrackAdapter { index ->
        if (index in localResults.indices) {
            hideKeyboard()
            viewModel.play(localResults, index)
            startActivity(MainActivity.playerIntent(this))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // 主题要在 super 之前套上：晚一步，窗口会先按清单里的默认主题创建
        ThemeManager.applyPalette(this)
        super.onCreate(savedInstanceState)
        // 放在 super 之后：AppCompat 这时才把亮暗写进 resources，
        // enableEdgeToEdge 读到的才是最终模式，状态栏图标不会反色
        enableEdgeToEdge()
        binding = ActivitySearchBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 导航栏保持透明：不要系统给手势条下面加那层白色蒙版
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            window.isNavigationBarContrastEnforced = false
        }

        ViewCompat.setOnApplyWindowInsetsListener(binding.searchRoot) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(top = bars.top, bottom = bars.bottom)
            insets
        }

        binding.searchList.layoutManager = LinearLayoutManager(this)
        binding.searchList.adapter = ConcatAdapter(
            onlineHeader,
            onlineAdapter,
            localHeader,
            localAdapter,
        )
        binding.searchBack.setOnClickListener { finish() }
        binding.searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                hideKeyboard()
                true
            } else {
                false
            }
        }
        binding.searchInput.doAfterTextChanged {
            viewModel.searchOnline(it?.toString().orEmpty())
            render()
        }

        binding.searchInput.requestFocus()
        WindowCompat.getInsetsController(window, window.decorView)
            .show(WindowInsetsCompat.Type.ime())

        // 本地曲库可能还没加载；加载完自动重算结果
        viewModel.loadLocal()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(
                    viewModel.local,
                    viewModel.online,
                    viewModel.onlineLoading,
                ) { _, _, _ -> Unit }
                    .combine(viewModel.onlineError) { _, _ -> Unit }
                    .collect { render() }
            }
        }
        render()
    }

    private fun render() {
        val query = binding.searchInput.text?.toString().orEmpty().trim()
        localResults = viewModel.search(query)
        val online = viewModel.online.value
        val loading = viewModel.onlineLoading.value
        val onlineError = viewModel.onlineError.value
        val attempted = query.length >= 2

        onlineHeader.setTitle(
            when {
                loading -> getString(R.string.search_section_online_loading)
                online.isNotEmpty() -> getString(R.string.search_section_online)
                else -> null
            },
        )
        localHeader.setTitle(
            if (localResults.isNotEmpty()) getString(R.string.search_section_mine) else null,
        )
        onlineAdapter.submitList(online)
        localAdapter.submitList(localResults)

        val hasContent = online.isNotEmpty() || localResults.isNotEmpty()
        val placeholder = when {
            // 空输入时只留一个搜索图标，输入框本身已经说明了这是干什么的
            query.isEmpty() -> ""
            attempted && online.isEmpty() && localResults.isEmpty() && loading -> null
            attempted && onlineError != null && localResults.isEmpty() ->
                getString(R.string.search_online_failed, onlineError)
            !hasContent -> getString(R.string.search_empty)
            else -> null
        }
        binding.searchPlaceholder.visibility =
            if (placeholder == null) View.GONE else View.VISIBLE
        binding.searchPlaceholderText.text = placeholder.orEmpty()
        binding.searchPlaceholderText.visibility =
            if (placeholder.isNullOrEmpty()) View.GONE else View.VISIBLE
        binding.searchList.visibility =
            if (placeholder == null || loading) View.VISIBLE else View.INVISIBLE
    }

    private fun hideKeyboard() {
        WindowCompat.getInsetsController(window, window.decorView)
            .hide(WindowInsetsCompat.Type.ime())
    }
}
