package com.szc.learning.ui.order

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.szc.learning.AppContainer
import com.szc.learning.LearningApp
import com.szc.learning.R
import com.szc.learning.databinding.FragmentOrderBinding
import com.szc.learning.ui.takeout.OrderAdapter
import com.szc.learning.ui.takeout.OrderDetailActivity
import com.szc.learning.ui.takeout.OrderListViewModel
import com.szc.learning.ui.takeout.PayActivity
import kotlinx.coroutines.launch

/** 订单 tab：P2 分页列表，点卡片进详情，点「去支付」进收银台。 */
class OrderFragment : Fragment() {

    private var _binding: FragmentOrderBinding? = null
    private val binding get() = _binding!!

    private val container: AppContainer
        get() = (requireActivity().application as LearningApp).container

    private lateinit var viewModel: OrderListViewModel

    private val adapter = OrderAdapter(
        onClick = { order ->
            startActivity(OrderDetailActivity.intent(requireContext(), order.orderId))
        },
        onPay = { order ->
            startActivity(PayActivity.intent(requireContext(), order.orderId))
        },
    )

    override fun onCreateView(
        inflater: LayoutInflater,
        parent: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentOrderBinding.inflate(inflater, parent, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewModel = ViewModelProvider(
            this,
            OrderListViewModel.factory(container.takeoutRepository),
        )[OrderListViewModel::class.java]

        binding.orderRecycler.layoutManager = LinearLayoutManager(requireContext())
        binding.orderRecycler.adapter = adapter
        binding.orderRecycler.addOnScrollListener(
            object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    if (dy <= 0) return
                    val layoutManager = recyclerView.layoutManager as? LinearLayoutManager ?: return
                    if (layoutManager.findLastVisibleItemPosition() >= adapter.itemCount - 2) {
                        viewModel.loadMore()
                    }
                }
            },
        )
        observe()
    }

    /** tab 用 show/hide 切换时不会走 onResume，这里在可见时刷新。 */
    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) viewModel.refresh()
    }

    private fun observe() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { state ->
                    binding.loadingBar.isVisible = state.loading
                    adapter.submitList(state.orders)

                    val empty = state.orders.isEmpty() && !state.loading
                    binding.emptyText.isVisible = empty
                    if (empty) {
                        binding.emptyText.text = state.loadError ?: getString(R.string.order_empty)
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        binding.orderRecycler.adapter = null
        _binding = null
        super.onDestroyView()
    }
}
