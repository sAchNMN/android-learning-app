package com.szc.learning.ui.home

import android.content.Intent
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.szc.learning.AppContainer
import com.szc.learning.LearningApp
import com.szc.learning.R
import com.szc.learning.databinding.ActivityHomeBinding
import com.szc.learning.ui.address.AddressListActivity
import com.szc.learning.ui.login.LoginActivity
import com.szc.learning.ui.takeout.OrderListActivity
import com.szc.learning.ui.takeout.ShopListActivity
import kotlinx.coroutines.launch

/**
 * 首页：展示当前登录用户，提供退出登录。
 *
 * 目前没有业务功能，只作为登录成功后的落地页。
 * 具体业务按需拆成 feature 模块再接进来，首页只做入口聚合。
 */
class HomeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHomeBinding

    private val container: AppContainer get() = (application as LearningApp).container

    private val viewModel: HomeViewModel by viewModels {
        HomeViewModel.factory(container.authRepository, container.sessionStore)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        applyWindowInsets()
        bindActions()
        observeState()
    }

    private fun applyWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }
    }

    private fun bindActions() {
        binding.logoutButton.setOnClickListener {
            AlertDialog.Builder(this)
                .setMessage(R.string.home_logout_confirm)
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.action_confirm) { _, _ -> viewModel.logout() }
                .show()
        }

        binding.shopEntryCard.setOnClickListener {
            startActivity(Intent(this, ShopListActivity::class.java))
        }

        binding.orderEntryCard.setOnClickListener {
            startActivity(Intent(this, OrderListActivity::class.java))
        }

        binding.addressEntryCard.setOnClickListener {
            startActivity(AddressListActivity.manageIntent(this))
        }
    }

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    val displayName = state.nickname.ifBlank { "未登录" }
                    binding.nicknameText.text = displayName
                    binding.avatarText.text = displayName.take(1)

                    binding.accountText.isVisible = state.account.isNotBlank()
                    binding.accountText.text = getString(R.string.home_account, state.account)

                    binding.logoutButton.isEnabled = !state.loggingOut

                    if (state.sessionExpired) {
                        goLogin(message = getString(R.string.home_session_expired))
                        return@collect
                    }

                    if (state.loggedOut) {
                        viewModel.consumeLoggedOut()
                        goLogin()
                    }
                }
            }
        }
    }

    private fun goLogin(message: String? = null) {
        startActivity(
            Intent(this, LoginActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            },
        )
        if (message != null) {
            android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show()
        }
        finish()
    }
}
