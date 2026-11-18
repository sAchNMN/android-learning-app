package com.szc.learning.music.ui.source

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
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
import com.szc.learning.core.network.music.MusicSourceInfo
import com.szc.learning.databinding.ActivitySourceBinding
import com.szc.learning.music.ui.MusicViewModel
import com.szc.learning.music.ui.common.sourceLabel
import com.szc.learning.ui.theme.ThemeManager
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * 音源管理页。
 *
 * 对应 Mineradio「登录账号 / 平台连接 / 音源优先级」那套入口，
 * 但这里不发二维码：B/S 结构下扫码绑定的是桌面端进程，
 * 移动端更实际的做法是把浏览器里的 Cookie 交给服务端。
 */
class SourceActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySourceBinding
    private val viewModel: MusicViewModel by viewModels()

    private val adapter = SourceAdapter(
        onToggle = { item, enabled -> viewModel.setSourceEnabled(item.key, enabled) },
        onMove = { item, offset -> viewModel.moveSource(item.key, offset) },
        onCookie = { item -> showCookieOptions(item) },
    )

    /** 登录页拿到 Cookie 后回传，真正落库仍走音源管理接口。 */
    private val cookieLoginLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        val data = result.data ?: return@registerForActivityResult
        val key = data.getStringExtra(CookieLoginActivity.EXTRA_KEY).orEmpty()
        val cookie = data.getStringExtra(CookieLoginActivity.EXTRA_COOKIE).orEmpty()
        if (key.isBlank() || cookie.isBlank()) return@registerForActivityResult
        viewModel.setSourceCookie(key, cookie)
        toast(getString(R.string.source_login_saved, sourceLabel(this, key)))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyPalette(this)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivitySourceBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (android.os.Build.VERSION.SDK_INT >= 29) {
            window.isNavigationBarContrastEnforced = false
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.sourceRoot) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(top = bars.top, bottom = bars.bottom)
            insets
        }

        binding.sourceList.layoutManager = LinearLayoutManager(this)
        binding.sourceList.adapter = adapter
        binding.sourceBack.setOnClickListener { finish() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(
                    viewModel.sources,
                    viewModel.sourcesLoading,
                    viewModel.sourceError,
                ) { _, _, _ -> Unit }.collect { render() }
            }
        }
        viewModel.loadSources()
    }

    private fun render() {
        val sources = viewModel.sources.value.sortedBy { it.priority }
        val loading = viewModel.sourcesLoading.value
        adapter.submitList(sources)
        binding.sourceProgress.visibility =
            if (loading && sources.isEmpty()) View.VISIBLE else View.GONE
        binding.sourceEmpty.visibility =
            if (!loading && sources.isEmpty()) View.VISIBLE else View.GONE
        val error = viewModel.sourceError.value
        binding.sourceError.visibility = if (error.isNullOrBlank()) View.GONE else View.VISIBLE
        binding.sourceError.text = error.orEmpty()
    }

    private fun showCookieOptions(item: MusicSourceInfo) {
        val actions = mutableListOf(
            getString(R.string.source_login_action),
            getString(R.string.source_cookie_manual),
        )
        if (item.hasCookie) {
            actions += getString(R.string.source_cookie_clear)
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.source_cookie_dialog_title, item.label))
            .setItems(actions.toTypedArray()) { _, which ->
                when (which) {
                    0 -> cookieLoginLauncher.launch(CookieLoginActivity.intent(this, item.key))
                    1 -> showManualCookieDialog(item)
                    else -> {
                        viewModel.setSourceCookie(item.key, "")
                        toast(getString(R.string.source_login_cookie_cleared, item.label))
                    }
                }
            }
            .show()
    }

    private fun showManualCookieDialog(item: MusicSourceInfo) {
        val density = resources.displayMetrics.density
        val padding = (20 * density).toInt()
        val input = EditText(this).apply {
            hint = getString(R.string.source_cookie_dialog_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 3
            maxLines = 6
        }
        val container = FrameLayout(this).apply {
            setPadding(padding, padding / 2, padding, 0)
            addView(
                input,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.source_cookie_dialog_title, item.label))
            .setView(container)
            .setMessage(R.string.source_cookie_help)
            .setPositiveButton(R.string.action_save) { _, _ ->
                viewModel.setSourceCookie(item.key, input.text?.toString().orEmpty())
                toast(R.string.source_saved)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    companion object {
        fun start(context: Context) {
            context.startActivity(Intent(context, SourceActivity::class.java))
        }
    }
}