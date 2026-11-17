package com.szc.learning.ui.takeout

import android.content.Intent
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.szc.learning.AppContainer
import com.szc.learning.LearningApp
import com.szc.learning.R
import com.szc.learning.core.common.toast
import com.szc.learning.databinding.ActivityShopDetailBinding
import com.szc.learning.databinding.DialogCheckoutBinding
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * 三级页：店铺详情。
 *
 * 店铺信息卡 + 按分类分组的菜单 + 底部购物车栏。
 * 购物车明细放在底表里，加菜之后不用退出去看总数对不对。
 */
class ShopDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityShopDetailBinding

    private val container: AppContainer get() = (application as LearningApp).container

    private val shopId: Long by lazy { intent.getLongExtra(EXTRA_SHOP_ID, -1L) }

    private val viewModel: ShopDetailViewModel by viewModels {
        ShopDetailViewModel.factory(container.takeoutRepository, shopId)
    }

    private val headerAdapter = ShopHeaderAdapter()

    private val dishAdapter = DishRowAdapter(
        onAdd = { viewModel.addDish(it) },
        onRemove = { viewModel.removeDish(it) },
    )

    private var cartSheet: CartSheetDialog? = null

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

        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.contentRecycler.layoutManager = LinearLayoutManager(this)
        binding.contentRecycler.adapter = ConcatAdapter(headerAdapter, dishAdapter)

        binding.checkoutButton.setOnClickListener { showCheckoutDialog() }
        binding.cartSummaryGroup.setOnClickListener { openCartSheet() }
        binding.retryButton.setOnClickListener { viewModel.load() }

        observe()
    }

    private fun observe() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { render(it) }
            }
        }
    }

    private fun render(state: ShopDetailViewModel.UiState) {
        binding.loadingBar.isVisible = state.loading

        val hasError = state.loadError != null
        binding.errorGroup.isVisible = hasError && !state.loading
        binding.errorText.text = state.loadError.orEmpty()

        val noDishes = !state.loading && !hasError &&
            state.detail != null && state.detail.dishes.isEmpty()
        binding.dishEmptyText.isVisible = noDishes

        state.detail?.let { detail ->
            headerAdapter.submit(detail.shop)
            dishAdapter.submitList(state.rows)
        }

        binding.totalPrice.text = getString(R.string.money_format, formatMoney(state.totalAmount))
        binding.cartSummary.text = when {
            state.itemCount == 0 -> getString(R.string.cart_empty)
            state.shortOfMin > 0 -> getString(R.string.shop_min_hint, formatMoney(state.shortOfMin))
            else -> getString(R.string.cart_summary, state.itemCount)
        }
        binding.checkoutButton.isEnabled = state.canCheckout && !state.submitting

        renderMinProgress(state)

        // 底表开着时跟着状态刷新，否则删到 0 件它还停在那儿显示旧内容
        cartSheet?.takeIf { it.isShowing }?.submit(state.lines)

        state.actionError?.let {
            toast(it)
            viewModel.clearActionError()
        }

        state.createdOrder?.let { order ->
            viewModel.consumeCreatedOrder()
            toast(
                getString(
                    R.string.checkout_success_order,
                    getString(R.string.checkout_success),
                    order.orderNo,
                ),
            )
            startActivity(Intent(this@ShopDetailActivity, OrderListActivity::class.java))
            finish()
        }
    }

    /** 起送价进度条：只在购物车非空时显示，买东西的进度感比一行灰字直观。 */
    private fun renderMinProgress(state: ShopDetailViewModel.UiState) {
        val minCents = (state.minAmount * 100).roundToInt()
        val show = minCents > 0 && state.itemCount > 0
        binding.minProgress.isVisible = show
        if (!show) return

        binding.minProgress.max = minCents
        binding.minProgress.setProgressCompat(
            (state.totalAmount * 100).roundToInt().coerceAtMost(minCents),
            true,
        )
    }

    private fun openCartSheet() {
        if (viewModel.state.value.itemCount == 0) {
            toast(R.string.cart_empty)
            return
        }
        if (cartSheet?.isShowing == true) return

        val sheet = CartSheetDialog(
            context = this,
            onAdd = { viewModel.addDish(it) },
            onRemove = { viewModel.removeDish(it) },
            onClear = { confirmClearCart() },
        )
        sheet.setOnDismissListener { cartSheet = null }
        sheet.submit(viewModel.state.value.lines)
        cartSheet = sheet
        sheet.show()
    }

    private fun confirmClearCart() {
        MaterialAlertDialogBuilder(this)
            .setMessage(R.string.cart_clear_confirm)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_confirm) { _, _ -> viewModel.clearCart() }
            .show()
    }

    private fun showCheckoutDialog() {
        val dialogBinding = DialogCheckoutBinding.inflate(layoutInflater)
        // 演示用的默认收货信息，改起来比每次手输快
        dialogBinding.receiverInput.setText("张三")
        dialogBinding.phoneInput.setText("13800138000")
        dialogBinding.addressInput.setText("郑州市金水区文化路 1 号")

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.checkout_title)
            .setView(dialogBinding.root)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.checkout_confirm, null)
            .create()

        // 手动接管确定按钮：校验不通过时对话框不能关，否则用户得重填一遍
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val receiver = dialogBinding.receiverInput.text?.toString()?.trim().orEmpty()
                val phone = dialogBinding.phoneInput.text?.toString()?.trim().orEmpty()
                val address = dialogBinding.addressInput.text?.toString()?.trim().orEmpty()

                dialogBinding.receiverLayout.error = null
                dialogBinding.phoneLayout.error = null
                dialogBinding.addressLayout.error = null

                when {
                    receiver.isEmpty() -> dialogBinding.receiverLayout.error =
                        getString(R.string.checkout_error_receiver)

                    phone.length != 11 -> dialogBinding.phoneLayout.error =
                        getString(R.string.checkout_error_phone)

                    address.isEmpty() -> dialogBinding.addressLayout.error =
                        getString(R.string.checkout_error_address)

                    else -> {
                        dialog.dismiss()
                        viewModel.checkout(receiver, phone, address)
                    }
                }
            }
        }
        dialog.show()
    }

    companion object {
        const val EXTRA_SHOP_ID = "shopId"
    }
}
