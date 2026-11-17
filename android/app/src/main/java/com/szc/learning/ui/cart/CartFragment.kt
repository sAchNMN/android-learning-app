package com.szc.learning.ui.cart

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
import com.szc.learning.data.ShopCart
import com.szc.learning.databinding.FragmentCartBinding
import com.szc.learning.ui.main.MainActivity
import com.szc.learning.ui.takeout.CheckoutActivity
import com.szc.learning.ui.takeout.formatMoney
import kotlinx.coroutines.launch

/** 购物车 tab：展示本地购物车，可加减数量、去结算。 */
class CartFragment : Fragment() {

    private var _binding: FragmentCartBinding? = null
    private val binding get() = _binding!!

    private val container: AppContainer
        get() = (requireActivity().application as LearningApp).container

    private lateinit var viewModel: CartViewModel

    /** 当前展示的店铺，加减行与去结算都要带上它。 */
    private var activeShopId: Long = 0

    private val adapter = CartAdapter(
        onIncrease = { lineKey -> viewModel.increase(activeShopId, lineKey) },
        onDecrease = { lineKey -> viewModel.decrease(activeShopId, lineKey) },
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
            if (activeShopId <= 0) return@setOnClickListener
            startActivity(CheckoutActivity.intent(requireContext(), activeShopId))
        }

        observe()
    }

    private fun observe() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.carts.collect { carts -> render(viewModel.activeCart(carts)) }
            }
        }
    }

    private fun render(cart: ShopCart?) {
        if (cart == null || cart.isEmpty) {
            activeShopId = 0
            adapter.submitList(emptyList())
            binding.emptyGroup.isVisible = true
            binding.cartRecycler.isVisible = false
            binding.bottomBar.isVisible = false
            binding.shopNameText.isVisible = false
            return
        }

        activeShopId = cart.shopId
        adapter.submitList(cart.lines)

        binding.emptyGroup.isVisible = false
        binding.cartRecycler.isVisible = true
        binding.bottomBar.isVisible = true
        binding.shopNameText.isVisible = true
        binding.shopNameText.text = cart.shopName

        binding.totalText.text = getString(R.string.cart_total, formatMoney(cart.totalAmount))
        binding.hintText.text = getString(R.string.cart_item_total, cart.itemCount)
        // 起送价由结算页 preview 校验，这里只要有菜就允许进结算
        binding.checkoutButton.isEnabled = cart.itemCount > 0
    }

    override fun onDestroyView() {
        binding.cartRecycler.adapter = null
        _binding = null
        super.onDestroyView()
    }
}
