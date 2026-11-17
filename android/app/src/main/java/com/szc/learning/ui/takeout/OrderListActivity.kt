package com.szc.learning.ui.takeout

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
import com.google.android.material.tabs.TabLayout
import com.szc.learning.AppContainer
import com.szc.learning.LearningApp
import com.szc.learning.R
import com.szc.learning.core.network.takeout.TakeoutRepository
import com.szc.learning.databinding.ActivityOrderListBinding
import kotlinx.coroutines.launch

/**
 * 我的订单（L4）：分类 Tab + 分页 + 下拉刷新。
 *
 * Tab 位置到 tab 值的映射写死在 [tabs] 里，顺序必须与布局里的 TabItem 一致。
 */
class OrderListActivity : AppCompatActivity() {

    private lateinit var binding: ActivityOrderListBinding

    private val container: AppContainer get() = (application as LearningApp).container

    private val viewModel: OrderListViewModel by viewModels {
        OrderListViewModel.factory(container.takeoutRepository)
    }

    private val adapter = OrderAdapter(
        onClick = { order -> startActivity(OrderDetailActivity.intent(this, order.orderId)) },
        onPay = { order -> startActivity(PayActivity.intent(this, order.orderId)) },
    )

    /** 与 activity_order_list.xml 里 TabItem 的顺序一一对应。 */
    private val tabs = listOf(
        TakeoutRepository.TAB_ALL,
        TakeoutRepository.TAB_UNPAID,
        TakeoutRepository.TAB_ONGOING,
        TakeoutRepository.TAB_DONE,
    )

    /** 首次 onResume 不刷新：ViewModel 的 init 已经拉过一次。 */
    private var firstResume = true

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityOrderListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }

        binding.orderRecycler.layoutManager = LinearLayoutManager(this)
        binding.orderRecycler.adapter = adapter
        binding.orderRecycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (dy <= 0) return
                val layoutManager = recyclerView.layoutManager as? LinearLayoutManager ?: return
                if (layoutManager.findLastVisibleItemPosition() >= adapter.itemCount - 3) {
                    viewModel.loadMore()
                }
            }
        })

        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                viewModel.selectTab(tabs.getOrElse(tab.position) { TakeoutRepository.TAB_ALL })
            }

            override fun onTabUnselected(tab: TabLayout.Tab) = Unit

            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })

        binding.refreshLayout.setOnRefreshListener { viewModel.refresh() }
        binding.retryButton.setOnClickListener { viewModel.loadFirstPage() }
        binding.footerGroup.setOnClickListener { viewModel.loadMore() }

        observe()
    }

    override fun onResume() {
        super.onResume()
        if (firstResume) {
            firstResume = false
            return
        }
        // 从详情/收银台回来时订单状态可能已经变了
        viewModel.refresh()
    }

    private fun observe() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { state ->
                    adapter.submitList(state.orders)

                    if (!state.loading) {
                        binding.refreshLayout.isRefreshing = false
                    }
                    binding.loadingBar.isVisible = state.loading && state.orders.isEmpty()

                    val showEmpty = !state.loading && state.loadError == null && state.orders.isEmpty()
                    binding.emptyGroup.isVisible = showEmpty
                    if (showEmpty) {
                        binding.emptyText.setText(
                            if (state.tab == TakeoutRepository.TAB_ALL) {
                                R.string.order_empty
                            } else {
                                R.string.order_empty_tab
                            },
                        )
                    }

                    val showError = !state.loading && state.loadError != null && state.orders.isEmpty()
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
}
