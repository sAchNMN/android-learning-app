package com.szc.learning.ui.takeout

import android.content.Context
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
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.szc.learning.AppContainer
import com.szc.learning.LearningApp
import com.szc.learning.R
import com.szc.learning.core.common.toast
import com.szc.learning.databinding.ActivityFavoriteListBinding
import kotlinx.coroutines.launch

/**
 * 我的收藏（L5）。
 *
 * 长按店铺卡片取消收藏 —— 列表里每张卡都放个删除按钮会让整页看起来很吵。
 */
class FavoriteListActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFavoriteListBinding

    private val container: AppContainer get() = (application as LearningApp).container

    private val viewModel: FavoriteListViewModel by viewModels {
        FavoriteListViewModel.factory(container.shopRepository)
    }

    private val adapter = ShopAdapter(
        onClick = { shop -> startActivity(ShopDetailActivity.intent(this, shop.shopId)) },
        onLongClick = { shop -> askUnfavorite(shop.shopName, onConfirm = { viewModel.unfavorite(shop) }) },
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityFavoriteListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }

        binding.shopRecycler.layoutManager = LinearLayoutManager(this)
        binding.shopRecycler.adapter = adapter
        binding.shopRecycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (dy <= 0) return
                val layoutManager = recyclerView.layoutManager as? LinearLayoutManager ?: return
                if (layoutManager.findLastVisibleItemPosition() >= adapter.itemCount - 3) {
                    viewModel.loadMore()
                }
            }
        })

        binding.refreshLayout.setOnRefreshListener { viewModel.refresh() }
        binding.retryButton.setOnClickListener { viewModel.loadFirstPage() }
        binding.footerGroup.setOnClickListener { viewModel.loadMore() }

        observe()
    }

    private fun observe() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { state ->
                    adapter.submitList(state.shops)

                    if (!state.loading) {
                        binding.refreshLayout.isRefreshing = false
                    }
                    binding.loadingBar.isVisible = state.loading && state.shops.isEmpty()

                    val showEmpty = !state.loading && state.loadError == null && state.shops.isEmpty()
                    binding.emptyText.isVisible = showEmpty

                    val showError = !state.loading && state.loadError != null && state.shops.isEmpty()
                    binding.errorGroup.isVisible = showError
                    if (showError) {
                        binding.errorText.text = state.loadError
                    }

                    when {
                        state.loadingMore -> {
                            binding.footerGroup.isVisible = true
                            binding.footerProgress.isVisible = true
                            binding.footerText.setText(R.string.list_loading_more)
                        }

                        state.moreError -> {
                            binding.footerGroup.isVisible = true
                            binding.footerProgress.isVisible = false
                            binding.footerText.setText(R.string.list_load_more_failed)
                        }

                        else -> binding.footerGroup.isVisible = false
                    }

                    state.messageRes?.let {
                        toast(it)
                        viewModel.consumeMessage()
                    }
                }
            }
        }
    }

    private fun askUnfavorite(shopName: String, onConfirm: () -> Unit) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.favorite_cancel_title)
            .setMessage(getString(R.string.favorite_cancel_msg, shopName))
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_confirm) { _, _ -> onConfirm() }
            .show()
    }

    companion object {
        fun intent(context: Context): Intent = Intent(context, FavoriteListActivity::class.java)
    }
}
