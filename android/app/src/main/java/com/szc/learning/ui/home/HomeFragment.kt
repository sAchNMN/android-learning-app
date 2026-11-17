package com.szc.learning.ui.home

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.PopupMenu
import androidx.core.view.isVisible
import androidx.core.widget.doOnTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.chip.Chip
import com.szc.learning.AppContainer
import com.szc.learning.LearningApp
import com.szc.learning.R
import com.szc.learning.databinding.FragmentHomeBinding
import com.szc.learning.ui.takeout.LegacyShopListViewModel
import com.szc.learning.ui.takeout.ShopAdapter
import com.szc.learning.ui.takeout.ShopDetailActivity
import com.szc.learning.ui.takeout.ShopSort
import kotlinx.coroutines.launch

/**
 * 二级页：店铺列表（首页 tab）。
 *
 * 页面结构：搜索 → 分类筛选 → 排序 → 列表。
 * 三种空态要分清，混在一起会让用户以为后端挂了：
 * 请求失败 / 一家都没营业 / 筛不出来。
 */
class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    private val container: AppContainer
        get() = (requireActivity().application as LearningApp).container

    private lateinit var viewModel: LegacyShopListViewModel

    private val adapter = ShopAdapter(
        onClick = { shop ->
            startActivity(
                Intent(requireContext(), ShopDetailActivity::class.java)
                    .putExtra(ShopDetailActivity.EXTRA_SHOP_ID, shop.shopId),
            )
        },
    )

    /** 上次渲染的分类集合，用来判断要不要重建芯片，避免每次刷新都闪一下。 */
    private var renderedCategories: List<String>? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        parent: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, parent, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewModel = ViewModelProvider(
            this,
            LegacyShopListViewModel.legacyFactory(container.shopRepository),
        )[LegacyShopListViewModel::class.java]

        binding.shopRecycler.layoutManager = LinearLayoutManager(requireContext())
        binding.shopRecycler.adapter = adapter

        setupSearch()
        binding.sortButton.setOnClickListener { showSortMenu() }

        observe()
    }

    private fun setupSearch() {
        binding.searchInput.doOnTextChanged { text, _, _, _ ->
            viewModel.setKeyword(text?.toString().orEmpty())
        }
        binding.searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                binding.searchInput.clearFocus()
                true
            } else {
                false
            }
        }
    }

    private fun observe() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { state ->
                    renderCategories(state.categories, state.category)
                    renderSortLabel(state.sort)

                    binding.loadingBar.isVisible = state.loading

                    val visible = state.visible
                    adapter.submitList(visible)
                    binding.countText.text = getString(R.string.shop_list_subtitle, visible.size)
                    renderEmptyState(state, visible.isEmpty())
                }
            }
        }
    }

    private fun renderCategories(categories: List<String>, selected: String?) {
        if (renderedCategories != categories) {
            renderedCategories = categories
            binding.categoryGroup.removeAllViews()
            addCategoryChip(getString(R.string.shop_filter_all), null)
            categories.forEach { addCategoryChip(it, it) }
        }
        // 选中态每次都要对齐，因为可能来自「清除筛选」
        for (index in 0 until binding.categoryGroup.childCount) {
            val chip = binding.categoryGroup.getChildAt(index) as? Chip ?: continue
            chip.isChecked = chip.tag == selected
        }
    }

    private fun addCategoryChip(label: String, value: String?) {
        val chip = layoutInflater
            .inflate(R.layout.item_filter_chip, binding.categoryGroup, false) as Chip
        chip.text = label
        chip.tag = value
        chip.setOnClickListener { viewModel.setCategory(value) }
        binding.categoryGroup.addView(chip)
    }

    private fun renderSortLabel(sort: ShopSort) {
        binding.sortButton.setText(
            when (sort) {
                ShopSort.DEFAULT -> R.string.shop_sort_default
                ShopSort.RATING -> R.string.shop_sort_rating
                ShopSort.DELIVERY_TIME -> R.string.shop_sort_time
                ShopSort.MIN_AMOUNT -> R.string.shop_sort_min
            },
        )
    }

    private fun showSortMenu() {
        val popup = PopupMenu(requireContext(), binding.sortButton)
        ShopSort.entries.forEach { sort ->
            popup.menu.add(0, sort.ordinal, sort.ordinal, sort.labelRes())
        }
        popup.setOnMenuItemClickListener { item ->
            viewModel.setSort(ShopSort.entries[item.itemId])
            true
        }
        popup.show()
    }

    private fun ShopSort.labelRes(): Int = when (this) {
        ShopSort.DEFAULT -> R.string.shop_sort_default
        ShopSort.RATING -> R.string.shop_sort_rating
        ShopSort.DELIVERY_TIME -> R.string.shop_sort_time
        ShopSort.MIN_AMOUNT -> R.string.shop_sort_min
    }

    private fun renderEmptyState(state: LegacyShopListViewModel.UiState, empty: Boolean) {
        binding.emptyGroup.isVisible = empty && !state.loading
        if (!binding.emptyGroup.isVisible) return

        when {
            state.error != null -> {
                binding.emptyTitle.text = state.error
                binding.emptyHint.setText(R.string.shop_list_error_hint)
                binding.emptyAction.setText(R.string.action_retry)
                binding.emptyAction.setOnClickListener { viewModel.load() }
            }

            state.filtering -> {
                binding.emptyTitle.setText(R.string.shop_list_no_match)
                binding.emptyHint.setText(R.string.shop_list_no_match_hint)
                binding.emptyAction.setText(R.string.shop_filter_clear)
                binding.emptyAction.setOnClickListener {
                    binding.searchInput.setText("")
                    viewModel.clearFilters()
                }
            }

            else -> {
                binding.emptyTitle.setText(R.string.shop_list_empty)
                binding.emptyHint.setText(R.string.shop_list_empty_hint)
                binding.emptyAction.setText(R.string.action_retry)
                binding.emptyAction.setOnClickListener { viewModel.load() }
            }
        }
    }

    override fun onDestroyView() {
        binding.shopRecycler.adapter = null
        _binding = null
        super.onDestroyView()
    }
}
