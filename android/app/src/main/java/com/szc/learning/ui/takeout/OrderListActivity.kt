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
import com.szc.learning.AppContainer
import com.szc.learning.LearningApp
import com.szc.learning.core.common.UiState
import com.szc.learning.core.common.toast
import com.szc.learning.databinding.ActivityOrderListBinding
import kotlinx.coroutines.launch

/** 我的订单页。待支付的可以点「去支付」。 */
class OrderListActivity : AppCompatActivity() {

    private lateinit var binding: ActivityOrderListBinding

    private val container: AppContainer get() = (application as LearningApp).container

    private val viewModel: OrderListViewModel by viewModels {
        OrderListViewModel.factory(container.takeoutRepository)
    }

    private val adapter = OrderAdapter(
        onClick = { order ->
            startActivity(OrderDetailActivity.intent(this, order.orderId))
        },
        onPay = { order ->
            startActivity(PayActivity.intent(this, order.orderId))
        },
    )

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

        observe()
    }

    override fun onResume() {
        super.onResume()
        viewModel.load()
    }

    private fun observe() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.state.collect { state ->
                        binding.loadingBar.isVisible = state is UiState.Loading
                        when (state) {
                            is UiState.Success -> {
                                val orders = state.data.orEmpty()
                                adapter.submitList(orders)
                                binding.emptyText.isVisible = orders.isEmpty()
                            }

                            is UiState.Error -> {
                                binding.emptyText.isVisible = true
                                toast(state.message)
                            }

                            else -> Unit
                        }
                    }
                }

                launch {
                    viewModel.message.collect { message ->
                        if (message != null) {
                            toast(message)
                            viewModel.consumeMessage()
                        }
                    }
                }
            }
        }
    }
}
