package com.szc.learning.ui.home

import android.content.Intent
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
import com.szc.learning.databinding.FragmentHomeBinding
import com.szc.learning.ui.takeout.ShopAdapter
import com.szc.learning.ui.takeout.ShopDetailActivity
import com.szc.learning.ui.takeout.ShopListViewModel
import kotlinx.coroutines.launch

/** 首页 tab：附近店铺列表，点卡片进店铺详情。 */
class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    private val container: AppContainer
        get() = (requireActivity().application as LearningApp).container

    private lateinit var viewModel: ShopListViewModel

    private val adapter = ShopAdapter { shop ->
        startActivity(
            Intent(requireContext(), ShopDetailActivity::class.java)
                .putExtra(ShopDetailActivity.EXTRA_SHOP_ID, shop.shopId),
        )
    }

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
            ShopListViewModel.factory(container.takeoutRepository),
        )[ShopListViewModel::class.java]

        binding.shopRecycler.layoutManager = LinearLayoutManager(requireContext())
        binding.shopRecycler.adapter = adapter
        observe()
    }

    private fun observe() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
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
                            binding.emptyText.text = state.message
                        }

                        else -> Unit
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        binding.shopRecycler.adapter = null
        _binding = null
        super.onDestroyView()
    }
}
