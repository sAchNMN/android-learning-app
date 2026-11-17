package com.szc.learning.ui.mine

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
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.szc.learning.AppContainer
import com.szc.learning.LearningApp
import com.szc.learning.R
import com.szc.learning.core.network.takeout.TakeoutRepository
import com.szc.learning.databinding.ActivityMineBinding
import com.szc.learning.ui.address.AddressListActivity
import com.szc.learning.ui.home.HomeViewModel
import com.szc.learning.ui.login.LoginActivity
import com.szc.learning.ui.takeout.FavoriteListActivity
import com.szc.learning.ui.takeout.OrderListActivity
import kotlinx.coroutines.launch

/**
 * 我的（T3）：用户信息 + 订单入口 + 常用功能 + 退出登录。
 *
 * 复用 [HomeViewModel]：会话读取与登出逻辑与首页完全一样，
 * 再写一个只读 sessionStore 的 VM 是重复代码。
 */
class MineActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMineBinding

    private val container: AppContainer get() = (application as LearningApp).container

    private val viewModel: HomeViewModel by viewModels {
        HomeViewModel.factory(
            container.authRepository,
            container.sessionStore,
            container.shopRepository,
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityMineBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }

        bindActions()
        observe()
    }

    private fun bindActions() {
        binding.orderUnpaid.setOnClickListener {
            startActivity(OrderListActivity.intent(this, TakeoutRepository.TAB_UNPAID))
        }
        binding.orderOngoing.setOnClickListener {
            startActivity(OrderListActivity.intent(this, TakeoutRepository.TAB_ONGOING))
        }
        binding.orderDone.setOnClickListener {
            startActivity(OrderListActivity.intent(this, TakeoutRepository.TAB_DONE))
        }
        binding.orderAll.setOnClickListener {
            startActivity(OrderListActivity.intent(this, TakeoutRepository.TAB_ALL))
        }
        binding.allOrdersButton.setOnClickListener {
            startActivity(OrderListActivity.intent(this, TakeoutRepository.TAB_ALL))
        }

        binding.favoriteEntry.setOnClickListener {
            startActivity(FavoriteListActivity.intent(this))
        }
        binding.addressEntry.setOnClickListener {
            startActivity(AddressListActivity.manageIntent(this))
        }

        binding.logoutButton.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setMessage(R.string.home_logout_confirm)
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.action_confirm) { _, _ -> viewModel.logout() }
                .show()
        }
    }

    private fun observe() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    val displayName = state.nickname.ifBlank { getString(R.string.mine_title) }
                    binding.nicknameText.text = displayName
                    binding.avatarText.text = displayName.take(1)

                    binding.accountText.isVisible = state.account.isNotBlank()
                    binding.accountText.text = getString(R.string.home_account, state.account)

                    binding.logoutButton.isEnabled = !state.loggingOut

                    if (state.sessionExpired) {
                        goLogin()
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

    private fun goLogin() {
        startActivity(
            Intent(this, LoginActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            },
        )
        finish()
    }
}
