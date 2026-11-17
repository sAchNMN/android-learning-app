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
import com.szc.learning.AppContainer
import com.szc.learning.LearningApp
import com.szc.learning.core.common.UiState
import com.szc.learning.core.common.toast
import com.szc.learning.databinding.FragmentOrderBinding
import com.szc.learning.ui.takeout.OrderAdapter
import com.szc.learning.ui.takeout.OrderListViewModel
import kotlinx.coroutines.launch

/** 订单 tab：复用了原订单列表的 ViewModel 与适配器。 */
class OrderFragment : Fragment() {

    private var _binding: FragmentOrderBinding? = null
    private val binding get() = _binding!!

    private val container: AppContainer
        get() = (requireActivity().application as LearningApp).container

    private lateinit var viewModel: OrderListViewModel

    private val adapter = OrderAdapter { order -> viewModel.pay(order) }

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
        observe()
    }

    /** tab 用 show/hide 切换时不会走 onResume，这里在可见时刷新。 */
    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) viewModel.load()
    }

    private fun observe() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
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
                                binding.emptyText.text = state.message
                            }

                            else -> Unit
                        }
                    }
                }

                launch {
                    viewModel.message.collect { message ->
                        if (message != null) {
                            requireContext().toast(message)
                            viewModel.consumeMessage()
                        }
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
