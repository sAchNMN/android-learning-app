package com.szc.learning.ui.login

import android.content.Intent
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.szc.learning.AppContainer
import com.szc.learning.BuildConfig
import com.szc.learning.LearningApp
import com.szc.learning.R
import com.szc.learning.databinding.ActivityLoginBinding
import com.szc.learning.ui.home.HomeActivity
import kotlinx.coroutines.launch

/**
 * 登录页。
 *
 * 进入顺序：先看本地有没有 token，有就直奔首页；没有才加载验证码并等待用户输入。
 * 这样冷启动不会每次都让用户重新登录。
 */
class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding

    private val container: AppContainer get() = (application as LearningApp).container

    private val viewModel: LoginViewModel by viewModels {
        LoginViewModel.factory(container.authRepository)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        applyWindowInsets()
        bindActions()
        observeState()
        decideEntryPoint()
    }

    private fun applyWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }
    }

    private fun bindActions() {
        binding.loginButton.setOnClickListener { submit() }

        binding.captchaImage.setOnClickListener {
            viewModel.refreshCaptcha()
        }

        if (BuildConfig.DEBUG) {
            binding.serverHint.text = getString(R.string.login_server_hint, BuildConfig.BASE_URL)
        } else {
            binding.serverHint.isVisible = false
        }
    }

    private fun decideEntryPoint() {
        lifecycleScope.launch {
            val restored = container.sessionStore.restore()
            if (restored != null && container.authRepository.hasLocalToken()) {
                goHome()
            } else {
                viewModel.refreshCaptcha()
            }
        }
    }

    private fun submit() {
        binding.usernameLayout.error = null
        binding.passwordLayout.error = null

        val username = binding.usernameInput.text?.toString()?.trim().orEmpty()
        val password = binding.passwordInput.text?.toString().orEmpty()
        val captcha = binding.captchaInput.text?.toString()?.trim().orEmpty()

        if (username.isEmpty()) {
            binding.usernameLayout.error = getString(R.string.login_error_username_empty)
            return
        }
        if (password.isEmpty()) {
            binding.passwordLayout.error = getString(R.string.login_error_password_empty)
            return
        }
        if (viewModel.uiState.value.captchaEnabled && captcha.isEmpty()) {
            binding.errorText.text = getString(R.string.login_error_captcha_empty)
            binding.errorText.isVisible = true
            return
        }

        viewModel.login(username, password, captcha)
    }

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    binding.loginButton.isEnabled = !state.submitting
                    binding.loginButton.text = getString(
                        if (state.submitting) R.string.login_action_loading else R.string.login_action,
                    )

                    binding.captchaGroup.isVisible = state.captchaEnabled
                    state.captchaImage?.let(binding.captchaImage::setImageBitmap)

                    binding.errorText.isVisible = !state.errorMessage.isNullOrBlank()
                    binding.errorText.text = state.errorMessage

                    if (state.loginSucceeded != null) {
                        viewModel.consumeLoginSuccess()
                        goHome()
                    }
                }
            }
        }
    }

    private fun goHome() {
        val intent = Intent(this, HomeActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(intent)
        finish()
    }
}
