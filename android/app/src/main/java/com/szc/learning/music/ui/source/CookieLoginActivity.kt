package com.szc.learning.music.ui.source

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.szc.learning.R
import com.szc.learning.core.common.toast
import com.szc.learning.databinding.ActivityCookieLoginBinding
import com.szc.learning.ui.theme.ThemeManager

/**
 * 半自动登录：直接用平台网页登录，App 轮询 Cookie 罐里的登录票据。
 *
 * 和 Mineradio 在 Electron 里开 BrowserWindow 是同一思路：
 * 账号密码只在平台页面里输入，App 不碰；成功后只把鉴权 Cookie 交给自己的后端。
 * 这里不做二维码协议逆向，平台改登录流程也不影响。
 */
class CookieLoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCookieLoginBinding
    private var spec: SourceLoginSpec? = null
    private var saved = false

    private val handler = Handler(Looper.getMainLooper())
    private val pollRunnable = object : Runnable {
        override fun run() {
            val current = spec ?: return
            if (saved || isFinishing) return
            val cookies = SourceCookieReader.read(current)
            if (SourceCookieReader.hasLogin(current, cookies)) {
                saved = true
                CookieManager.getInstance().flush()
                setResult(
                    RESULT_OK,
                    Intent()
                        .putExtra(EXTRA_KEY, current.sourceKey)
                        .putExtra(EXTRA_COOKIE, SourceCookieReader.header(cookies)),
                )
                finish()
            } else {
                handler.postDelayed(this, POLL_INTERVAL_MS)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyPalette(this)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val resolved = SourceLoginSpecs.of(intent.getStringExtra(EXTRA_KEY).orEmpty())
        if (resolved == null) {
            toast(R.string.source_login_failed)
            finish()
            return
        }
        spec = resolved

        binding = ActivityCookieLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.cookieRoot) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(top = bars.top, bottom = bars.bottom)
            insets
        }

        binding.cookieTitle.text = getString(R.string.source_login_title, getString(resolved.labelRes))
        binding.cookieBack.setOnClickListener { finish() }
        binding.cookieClear.setOnClickListener { confirmClearWebLogin() }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.cookieWeb.canGoBack()) {
                    binding.cookieWeb.goBack()
                } else {
                    finish()
                }
            }
        })

        configureWebView(resolved)
        binding.cookieWeb.loadUrl(resolved.loginUrl)
        beginPolling()
    }

    private fun configureWebView(resolved: SourceLoginSpec) {
        val manager = CookieManager.getInstance()
        manager.setAcceptCookie(true)
        manager.setAcceptThirdPartyCookies(binding.cookieWeb, true)

        with(binding.cookieWeb.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            loadsImagesAutomatically = true
            useWideViewPort = true
            loadWithOverviewMode = true
            builtInZoomControls = false
            displayZoomControls = false
            setSupportZoom(true)
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            resolved.userAgent?.let { userAgentString = it }
            // 登录弹窗常用 window.open；关掉多窗口让它在当前 WebView 里打开
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = true
        }

        binding.cookieWeb.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                binding.cookieProgress.visibility =
                    if (newProgress >= 100) View.GONE else View.VISIBLE
            }
        }

        binding.cookieWeb.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                binding.cookieProgress.visibility = View.VISIBLE
                beginPolling()
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                binding.cookieProgress.visibility = View.GONE
                beginPolling()
            }

            override fun onReceivedError(
                view: WebView?,
                request: android.webkit.WebResourceRequest?,
                error: android.webkit.WebResourceError?,
            ) {
                super.onReceivedError(view, request, error)
                binding.cookieProgress.visibility = View.GONE
            }
        }
    }

    private fun beginPolling() {
        if (saved) return
        handler.removeCallbacks(pollRunnable)
        handler.post(pollRunnable)
    }

    private fun confirmClearWebLogin() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.source_login_clear_web)
            .setMessage(R.string.source_login_clear_confirm)
            .setPositiveButton(R.string.source_login_clear_web) { _, _ ->
                CookieManager.getInstance().removeAllCookies {
                    CookieManager.getInstance().flush()
                    saved = false
                    handler.removeCallbacks(pollRunnable)
                    binding.cookieWeb.reload()
                    toast(R.string.source_login_clear_done)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    override fun onDestroy() {
        handler.removeCallbacks(pollRunnable)
        if (::binding.isInitialized) {
            binding.cookieWeb.stopLoading()
            binding.cookieWeb.destroy()
        }
        super.onDestroy()
    }

    companion object {
        const val EXTRA_KEY = "source_key"
        const val EXTRA_COOKIE = "source_cookie"

        private const val POLL_INTERVAL_MS = 1_200L

        fun intent(context: Context, sourceKey: String): Intent =
            Intent(context, CookieLoginActivity::class.java).putExtra(EXTRA_KEY, sourceKey)
    }
}