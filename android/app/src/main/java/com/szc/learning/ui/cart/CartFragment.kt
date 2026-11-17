package com.szc.learning.ui.cart

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
import com.szc.learning.R
import com.szc.learning.cart.CartState
import com.szc.learning.databinding.FragmentCartBinding
import com.szc.learning.ui.main.MainActivity
import com.szc.learning.ui.takeout.ShopDetailActivity
import com.szc.learning.ui.takeout.formatMoney
import kotlinx.coroutines.launch

/** 购物车 tab：展示本地购物车，可加减数量、去结算。 */
class CartFragment : Fragment() {

    private var _binding: FragmentCartBinding? = null
    private val binding get() = _binding!!

    private val container: AppContainer
        get() = (requireActivity().application as LearningApp).container

    private lateinit var viewModel: CartViewModel

    private val adapter = CartAdapter(
        onIncrease = { dishId -> viewModel.increase(dishId) },
        onDecrease = { dishId -> viewModel.decrease(dishId) },
    )

    override fun onCreateView(
        inflater: LayoutInflater,
        parent: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentCartBinding.inflate(inflater, parent, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewModel = ViewModelProvider(
            this,
            CartViewModel.factory(container.cartStore),
        )[CartViewModel::class.java]

        binding.cartRecycler.layoutManager = LinearLayoutManager(requireContext())
        binding.cartRecycler.adapter = adapter

        binding.goShoppingButton.setOnClickListener {
            (activity as? MainActivity)?.selectTab(R.id.tab_home)
        }

        binding.checkoutButton.setOnClickListener {
            val cart = viewModel.state.value
            if (cart.shopId <= 0) return@setOnClickListener
            startActivity(
                Intent(requireContext(), ShopDetailActivity::class.java)
                    .putExtra(ShopDetailActivity.EXTRA_SHOP_ID, cart.shopId),
            )
        }

        observe()
    }

    private fun observe() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect(::render)
            }
        }
    }

    private fun render(cart: CartState) {
        adapter.submitList(cart.items)

        val isEmpty = cart.isEmpty
        binding.emptyGroup.isVisible = isEmpty
        binding.cartRecycler.isVisible = !isEmpty
        binding.bottomBar.isVisible = !isEmpty
        binding.shopNameText.isVisible = !isEmpty
        binding.shopNameText.text = cart.shopName

        binding.totalText.text = getString(R.string.cart_total, formatMoney(cart.totalAmount))
        binding.hintText.text = if (cart.canCheckout) {
            getString(R.string.cart_item_total, cart.itemCount)
        } else {
            getString(R.string.shop_min_hint, formatMoney(cart.shortOfMin))
        }
        binding.checkoutButton.isEnabled = cart.canCheckout
    }

    override fun onDestroyView() {
        binding.cartRecycler.adapter = null
        _binding = null
        super.onDestroyView()
    }
}
