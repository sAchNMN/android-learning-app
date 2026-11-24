package com.szc.learning.ui.login

import android.os.Build
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.szc.learning.LearningApp
import com.szc.learning.R
import com.szc.learning.core.common.toast
import com.szc.learning.databinding.ActivityLoginBinding
import com.szc.learning.music.data.LoginStore
import com.szc.learning.music.ui.motion.installSpringPress
import com.szc.learning.music.ui.motion.springEnter
import com.szc.learning.music.ui.source.CookieLoginActivity
import com.szc.learning.ui.main.MainActivity
import com.szc.learning.ui.theme.ThemeManager

/**
 * 首次启动的登录页：三个在线平台 + 仅本地播放。
 *
 * 在线平台沿用 [CookieLoginActivity] 的半自动网页登录：账号密码只在平台页面里输入，
 * App 只拿鉴权 Cookie 存到本机音源配置里，不经过任何第三方服务器。
 * 「仅本地播放」直接进入主界面，并把默认 tab 落到本地音乐。
 */
class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private val container by lazy { (application as LearningApp).container }

    /** 防止连点导致同时打开两个网页登录页。 */
    private var navigating = false

    private val cookieLoginLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        navigating = false
        if (result.resultCode != RESULT_OK) {
            toast(R.string.login_cancelled)
            return@registerForActivityResult
        }
        val data = result.data ?: return@registerForActivityResult
        val key = data.getStringExtra(CookieLoginActivity.EXTRA_KEY).orEmpty()
        val cookie = data.getStringExtra(CookieLoginActivity.EXTRA_COOKIE).orEmpty()
        if (key.isBlank() || cookie.isBlank()) {
            toast(R.string.login_cancelled)
            return@registerForActivityResult
        }
        container.sourceConfigStore.setCookie(key, cookie)
        container.sourceConfigStore.setEnabled(key, true)
        finishLogin(key)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyPalette(this)
        super.onCreate(savedInstanceState)

        // 直接启动到这里（例如从最近任务恢复）且已经登录过：不再重复展示
        if (container.loginStore.isComplete) {
            startActivity(MainActivity.intent(this))
            finish()
            return
        }

        enableEdgeToEdge()
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (Build.VERSION.SDK_INT >= 29) {
            window.isNavigationBarContrastEnforced = false
        }
        // 登录页固定深色，系统栏图标一律用白色
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.loginRoot) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(top = bars.top, bottom = bars.bottom)
            insets
        }

        bindOptions()
        playEntrance()
    }

    private fun bindOptions() {
        binding.loginNetease.setOnClickListener { openPlatform(LoginStore.MODE_NETEASE) }
        binding.loginKugou.setOnClickListener { openPlatform(LoginStore.MODE_KUGOU) }
        binding.loginQq.setOnClickListener { openPlatform(LoginStore.MODE_QQ) }
        binding.loginLocal.setOnClickListener {
            if (navigating) return@setOnClickListener
            navigating = true
            finishLogin(LoginStore.MODE_LOCAL)
        }

        listOf(binding.loginNetease, binding.loginKugou, binding.loginQq).forEach {
            it.installSpringPress(0.98f)
        }
        binding.loginLocal.installSpringPress(0.98f)
    }

    private fun openPlatform(key: String) {
        if (navigating) return
        navigating = true
        cookieLoginLauncher.launch(CookieLoginActivity.intent(this, key))
    }

    private fun finishLogin(mode: String) {
        container.loginStore.complete(mode)
        // 选了「仅本地播放」就直接落在本地页，不让用户进来还要自己切一次
        val tab = if (mode == LoginStore.MODE_LOCAL) MainActivity.INDEX_LOCAL else MainActivity.DEFAULT_TAB
        startActivity(MainActivity.intent(this, tab))
        finish()
    }

    /** 品牌区 → 平台卡片 → 本地入口，依次飞入。 */
    private fun playEntrance() {
        binding.loginHeader.springEnter(delayMs = 0L, distanceDp = 28f)
        binding.loginPlatforms.springEnter(delayMs = 80L, distanceDp = 40f)
        binding.loginLocal.springEnter(delayMs = 140L, distanceDp = 40f)
    }
}
