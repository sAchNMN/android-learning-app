package com.szc.learning.ui.takeout

import android.content.Intent
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
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.szc.learning.AppContainer
import com.szc.learning.LearningApp
import com.szc.learning.R
import com.szc.learning.core.common.toast
import com.szc.learning.databinding.ActivityShopDetailBinding
import com.szc.learning.databinding.DialogCheckoutBinding
import kotlinx.coroutines.launch

/** 店铺详情：菜品列表 + 购物车 + 结算。 */
class ShopDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityShopDetailBinding

    private val container: AppContainer get() = (application as LearningApp).container

    private val shopId: Long by lazy { intent.getLongExtra(EXTRA_SHOP_ID, -1L) }

    private val viewModel: ShopDetailViewModel by viewModels {
        ShopDetailViewModel.factory(container.takeoutRepository, shopId)
    }

    private val adapter = DishAdapter { dish -> viewModel.addDish(dish) }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        if (shopId <= 0) {
            finish()
            return
        }

        binding = ActivityShopDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }

        binding.dishRecycler.layoutManager = LinearLayoutManager(this)
        binding.dishRecycler.adapter = adapter

        binding.checkoutButton.setOnClickListener { showCheckoutDialog() }

        observe()
    }

    private fun observe() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { state ->
                    binding.loadingBar.isVisible = state.loading

                    state.detail?.shop?.let { shop ->
                        binding.shopName.text = shop.shopName
                        binding.shopMeta.text = shop.description
                            ?: "起送 ¥${formatMoney(shop.minAmount)} · 配送 ¥${formatMoney(shop.deliveryFee)}"
                    }

                    state.detail?.let { detail ->
                        if (adapter.itemCount == 0) adapter.submit(detail.dishes)
                    }

                    adapter.quantities = state.cart

                    binding.totalPrice.text = "合计 ¥${formatMoney(state.totalAmount)}"
                    binding.cartSummary.text = when {
                        state.itemCount == 0 -> "购物车是空的"
                        state.shortOfMin > 0 -> getString(R.string.shop_min_hint, formatMoney(state.shortOfMin))
                        else -> "共 ${state.itemCount} 件 · 另加配送费"
                    }
                    binding.checkoutButton.isEnabled = state.canCheckout && !state.submitting

                    state.errorMessage?.let {
                        toast(it)
                        viewModel.clearError()
                    }

                    state.createdOrder?.let { order ->
                        viewModel.consumeCreatedOrder()
                        toast(getString(R.string.checkout_success) + "：${order.orderNo}")
                        startActivity(Intent(this@ShopDetailActivity, OrderListActivity::class.java))
                        finish()
                    }
                }
            }
        }
    }

    private fun showCheckoutDialog() {
        val dialogBinding = DialogCheckoutBinding.inflate(layoutInflater)
        // 演示用的默认收货信息，改起来比每次手输快
        dialogBinding.receiverInput.setText("张三")
        dialogBinding.phoneInput.setText("13800138000")
        dialogBinding.addressInput.setText("郑州市金水区文化路 1 号")

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.checkout_title)
            .setView(dialogBinding.root)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.checkout_confirm) { _, _ ->
                viewModel.checkout(
                    receiver = dialogBinding.receiverInput.text?.toString()?.trim().orEmpty(),
                    phone = dialogBinding.phoneInput.text?.toString()?.trim().orEmpty(),
                    address = dialogBinding.addressInput.text?.toString()?.trim().orEmpty(),
                )
            }
            .show()
    }

    companion object {
        const val EXTRA_SHOP_ID = "shopId"
    }
}
