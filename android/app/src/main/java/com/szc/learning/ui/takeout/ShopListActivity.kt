package com.szc.learning.ui.takeout

import android.os.Bundle
import android.view.View
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
import com.google.android.material.chip.Chip
import com.szc.learning.AppContainer
import com.szc.learning.LearningApp
import com.szc.learning.R
import com.szc.learning.core.network.takeout.ShopCategory
import com.szc.learning.core.network.takeout.ShopRepository
import com.szc.learning.databinding.ActivityShopListBinding
import kotlinx.coroutines.launch

/**
 * 店铺列表（L1）：分类筛选 + 排序 + 分页。
 *
 * 分类 Chip 是动态生成的，排序 Chip 是静态的 —— 分类数量由后端决定，
 * 排序是固定的四个值，没必要也动态生成。
 */
class ShopListActivity : AppCompatActivity() {

    private lateinit var binding: ActivityShopListBinding

    private val container: AppContainer get() = (application as LearningApp).container

    private val viewModel: ShopListViewModel by viewModels {
        ShopListViewModel.factory(container.shopRepository)
    }

    private val adapter = ShopAdapter(
        onClick = { shop ->
            startActivity(ShopDetailActivity.intent(this, shop.shopId))
        },
    )

    /** 分类栏的渲染签名，避免每次状态变化都重建 Chip（会闪、会丢点击）。 */
    private var categorySignature: String = ""

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
        binding.shopRecycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (dy <= 0) return
                val layoutManager = recyclerView.layoutManager as? LinearLayoutManager ?: return
                // 提前 3 个位置预加载，滚到底时数据基本已经回来了
                if (layoutManager.findLastVisibleItemPosition() >= adapter.itemCount - 3) {
                    viewModel.loadMore()
                }
            }
        })

        binding.refreshLayout.setOnRefreshListener { viewModel.refresh() }
        binding.retryButton.setOnClickListener { viewModel.loadFirstPage() }
        binding.emptyActionButton.setOnClickListener { viewModel.selectCategory(0L) }
        binding.footerGroup.setOnClickListener { viewModel.loadMore() }
        binding.searchEntry.setOnClickListener {
            startActivity(SearchActivity.intent(this))
        }

        setupSortGroup()
        observe()
    }

    private fun setupSortGroup() {
        // 先 check 再挂监听，否则 check 会触发一次多余的请求
        binding.sortGroup.check(R.id.sortDefaultChip)
        binding.sortGroup.setOnCheckedStateChangeListener { _, checkedIds ->
            val sort = when (checkedIds.firstOrNull()) {
                R.id.sortRatingChip -> ShopRepository.SORT_RATING
                R.id.sortFastChip -> ShopRepository.SORT_FAST
                R.id.sortCheapChip -> ShopRepository.SORT_CHEAP
                else -> ShopRepository.SORT_DEFAULT
            }
            viewModel.selectSort(sort)
        }
    }

    private fun observe() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { state ->
                    renderCategories(state.categories, state.selectedCategoryId)
                    adapter.submitList(state.shops)

                    if (!state.loading) {
                        binding.refreshLayout.isRefreshing = false
                    }
                    binding.loadingBar.isVisible = state.loading && state.shops.isEmpty()

                    val showEmpty = !state.loading && state.loadError == null && state.shops.isEmpty()
                    binding.emptyGroup.isVisible = showEmpty
                    if (showEmpty) {
                        binding.emptyText.setText(
                            if (state.selectedCategoryId > 0) {
                                R.string.shop_list_empty_category
                            } else {
                                R.string.shop_list_empty
                            },
                        )
                        // 分类下没店铺时给个出口，别让用户只能返回
                        binding.emptyActionButton.isVisible = state.selectedCategoryId > 0
                    }

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
                }
            }
        }
    }

    /**
     * 分类栏重建。
     *
     * 只有分类集合或选中项变化时才重建，重建期间摘掉监听，
     * 避免程序设置 isChecked 反过来触发一次请求。
     */
    private fun renderCategories(categories: List<ShopCategory>, selectedId: Long) {
        val signature = categories.joinToString(",") { "${it.categoryId}:${it.categoryName}" } +
            "|" + selectedId
        if (signature == categorySignature) return
        categorySignature = signature

        binding.categoryGroup.setOnCheckedStateChangeListener(null)
        binding.categoryGroup.removeAllViews()

        addCategoryChip(0L, getString(R.string.shop_category_all), selectedId == 0L)
        categories.forEach { category ->
            addCategoryChip(
                category.categoryId,
                category.categoryName,
                category.categoryId == selectedId,
            )
        }

        binding.categoryGroup.setOnCheckedStateChangeListener { group, checkedIds ->
            val chipId = checkedIds.firstOrNull() ?: return@setOnCheckedStateChangeListener
            val chip = group.findViewById<Chip>(chipId) ?: return@setOnCheckedStateChangeListener
            (chip.tag as? Long)?.let(viewModel::selectCategory)
        }
    }

    private fun addCategoryChip(categoryId: Long, label: String, checked: Boolean) {
        val chip = layoutInflater
            .inflate(R.layout.chip_category, binding.categoryGroup, false) as Chip
        chip.id = View.generateViewId()
        chip.text = label
        chip.tag = categoryId
        binding.categoryGroup.addView(chip)
        chip.isChecked = checked
    }
}
