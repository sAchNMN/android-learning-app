package com.szc.learning.ui.home

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.chip.Chip
import com.szc.learning.AppContainer
import com.szc.learning.LearningApp
import com.szc.learning.R
import com.szc.learning.core.network.takeout.Shop
import com.szc.learning.core.network.takeout.ShopCategory
import com.szc.learning.databinding.ActivityHomeBinding
import com.szc.learning.databinding.ItemShopBinding
import com.szc.learning.ui.login.LoginActivity
import com.szc.learning.ui.mine.MineActivity
import com.szc.learning.ui.takeout.SearchActivity
import com.szc.learning.ui.takeout.ShopDetailActivity
import com.szc.learning.ui.takeout.ShopListActivity
import com.szc.learning.ui.takeout.bindShop
import kotlinx.coroutines.launch
import java.util.Calendar

/**
 * 首页（T1）：定位、搜索入口、分类金刚区、推荐店铺。
 *
 * 会话失效与登出逻辑仍在这里处理：MineActivity 只是入口集合，
 * 首页才是登录后的落地页，冷启动时最先知道 token 过期。
 */
class HomeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHomeBinding

    private val container: AppContainer get() = (application as LearningApp).container

    private val viewModel: HomeViewModel by viewModels {
        HomeViewModel.factory(
            container.authRepository,
            container.sessionStore,
            container.shopRepository,
        )
    }

    private var categorySignature: String = ""

    private var recommendSignature: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }

        bindActions()
        observeState()
    }

    private fun bindActions() {
        binding.searchBar.setOnClickListener { startActivity(SearchActivity.intent(this)) }
        binding.mineButton.setOnClickListener { startActivity(Intent(this, MineActivity::class.java)) }
        binding.allShopsButton.setOnClickListener { startActivity(ShopListActivity.intent(this)) }
    }

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    binding.greetingText.text = greetingText(state.nickname)
                    binding.homeLoadingBar.isVisible = state.homeLoading

                    renderCategories(state.categories)
                    renderRecommend(state.shops)

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

    /** 「早上好，张三」。昵称为空时退回通用问候，不显示「早上好，」。 */
    private fun greetingText(nickname: String): String {
        if (nickname.isBlank()) {
            return getString(R.string.home_greeting_guest)
        }
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val template = when {
            hour < 11 -> R.string.home_greeting_morning
            hour < 14 -> R.string.home_greeting_noon
            hour < 18 -> R.string.home_greeting_afternoon
            else -> R.string.home_greeting_evening
        }
        return getString(template, nickname)
    }

    private fun renderCategories(categories: List<ShopCategory>) {
        val signature = categories.joinToString(",") { "${it.categoryId}:${it.categoryName}" }
        if (signature == categorySignature) return
        categorySignature = signature

        binding.categoryGroup.removeAllViews()
        categories.forEach { category ->
            val chip = LayoutInflater.from(this)
                .inflate(R.layout.chip_category, binding.categoryGroup, false) as Chip
            chip.text = category.categoryName
            chip.isCheckable = false
            chip.setOnClickListener {
                startActivity(ShopListActivity.intent(this, category.categoryId))
            }
            binding.categoryGroup.addView(chip)
        }
        binding.categoryGroup.isVisible = categories.isNotEmpty()
    }

    private fun renderRecommend(shops: List<Shop>) {
        val signature = shops.joinToString(",") { "${it.shopId}:${it.monthSales}:${it.rating}" }
        if (signature == recommendSignature) return
        recommendSignature = signature

        binding.recommendContainer.removeAllViews()
        shops.forEach { shop ->
            val item = ItemShopBinding.inflate(
                LayoutInflater.from(this),
                binding.recommendContainer,
                false,
            )
            item.bindShop(shop) {
                startActivity(ShopDetailActivity.intent(this, shop.shopId))
            }
            binding.recommendContainer.addView(item.root)
        }
        binding.recommendContainer.isVisible = shops.isNotEmpty()
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
