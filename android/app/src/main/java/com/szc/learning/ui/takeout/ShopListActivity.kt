package com.szc.learning.ui.takeout

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
import androidx.recyclerview.widget.LinearLayoutManager
import com.szc.learning.AppContainer
import com.szc.learning.LearningApp
import com.szc.learning.core.common.UiState
import com.szc.learning.core.common.toast
import com.szc.learning.databinding.ActivityShopListBinding
import kotlinx.coroutines.launch

/** 店铺列表页。点一家进详情。 */
class ShopListActivity : AppCompatActivity() {

    private lateinit var binding: ActivityShopListBinding

    private val container: AppContainer get() = (application as LearningApp).container

    private val viewModel: ShopListViewModel by viewModels {
        ShopListViewModel.factory(container.takeoutRepository)
    }

    private val adapter = ShopAdapter { shop ->
        startActivity(
            Intent(this, ShopDetailActivity::class.java)
                .putExtra(ShopDetailActivity.EXTRA_SHOP_ID, shop.shopId),
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityShopListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }

        binding.shopRecycler.layoutManager = LinearLayoutManager(this)
        binding.shopRecycler.adapter = adapter

        observe()
    }

    private fun observe() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { state ->
                    binding.loadingBar.isVisible = state is UiState.Loading

                    when (state) {
                        is UiState.Success -> {
                            val shops = state.data.orEmpty()
                            adapter.submitList(shops)
                            binding.emptyText.isVisible = shops.isEmpty()
                        }

                        is UiState.Error -> {
                            binding.emptyText.isVisible = true
                            toast(state.message)
                        }

                        else -> Unit
                    }
                }
            }
        }
    }
}
