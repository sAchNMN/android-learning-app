package com.szc.learning.ui.takeout

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
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
import com.szc.learning.core.common.toast
import com.szc.learning.databinding.ActivitySearchBinding
import kotlinx.coroutines.launch

/**
 * 搜索（L2）。
 *
 * 只搜店铺。热搜词点一下直接搜，省一次输入。
 */
class SearchActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySearchBinding

    private val container: AppContainer get() = (application as LearningApp).container

    private val viewModel: SearchViewModel by viewModels {
        SearchViewModel.factory(container.shopRepository)
    }

    private val adapter = ShopAdapter(
        onClick = { shop -> startActivity(ShopDetailActivity.intent(this, shop.shopId)) },
    )

    /** 热搜词的渲染签名，避免每次状态变化重建 Chip。 */
    private var hotSignature: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivitySearchBinding.inflate(layoutInflater)
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

        binding.searchButton.setOnClickListener { doSearch() }
        binding.searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                doSearch()
                true
            } else {
                false
            }
        }
        binding.footerGroup.setOnClickListener { viewModel.loadMore() }

        observe()
    }

    private fun doSearch() {
        val keyword = binding.searchInput.text?.toString().orEmpty()
        if (keyword.isBlank()) {
            toast(R.string.search_input_empty)
            return
        }
        hideKeyboard()
        viewModel.search(keyword)
    }

    private fun hideKeyboard() {
        val manager = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        manager.hideSoftInputFromWindow(binding.searchInput.windowToken, 0)
    }

    private fun observe() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { state ->
                    renderHotKeywords(state.hotKeywords)

                    adapter.submitList(state.shops)

                    binding.loadingBar.isVisible = state.searching
                    binding.hotGroup.isVisible = !state.searched && state.hotKeywords.isNotEmpty()
                    binding.shopRecycler.isVisible = state.searched && state.shops.isNotEmpty()
                    binding.emptyText.isVisible = state.searched &&
                        !state.searching &&
                        state.shops.isEmpty() &&
                        state.error == null

                    state.error?.let { toast(it) }

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

    private fun renderHotKeywords(keywords: List<String>) {
        val signature = keywords.joinToString(",")
        if (signature == hotSignature) return
        hotSignature = signature

        binding.hotChipGroup.removeAllViews()
        keywords.forEach { keyword ->
            val chip = LayoutInflater.from(this)
                .inflate(R.layout.chip_category, binding.hotChipGroup, false) as Chip
            chip.text = keyword
            chip.isCheckable = false
            chip.setOnClickListener {
                binding.searchInput.setText(keyword)
                binding.searchInput.setSelection(keyword.length)
                hideKeyboard()
                viewModel.search(keyword)
            }
            binding.hotChipGroup.addView(chip)
        }
    }

    companion object {
        fun intent(context: Context): Intent = Intent(context, SearchActivity::class.java)
    }
}
