package com.szc.learning.ui.takeout

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.szc.learning.AppContainer
import com.szc.learning.LearningApp
import com.szc.learning.R
import com.szc.learning.core.common.toast
import com.szc.learning.core.network.takeout.OrderPreview
import com.szc.learning.databinding.ActivityCheckoutBinding
import com.szc.learning.databinding.ItemCheckoutLineBinding
import com.szc.learning.ui.address.AddressListActivity
import kotlinx.coroutines.launch

/**
 * 结算确认（D2）。
 *
 * 金额一律展示 preview 给的值，**这里不做任何加减乘除**。
 * 提交时只把「买了什么、送到哪、什么时候送」告诉服务端，价格由它重算。
 */
class CheckoutActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCheckoutBinding

    private val container: AppContainer get() = (application as LearningApp).container

    private val shopId: Long by lazy { intent.getLongExtra(EXTRA_SHOP_ID, -1L) }

    private val viewModel: CheckoutViewModel by viewModels {
        CheckoutViewModel.factory(container.takeoutRepository, container.cartStore, shopId)
    }

    private var navigated = false

    private val addressLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        val addressId = result.data?.getLongExtra(
            AddressListActivity.EXTRA_RESULT_ADDRESS_ID,
            0L,
        ) ?: 0L
        // 换地址要重新预览：配送费与起送价都跟店铺绑定，客户端不能自己算
        if (addressId > 0) viewModel.loadPreview(addressId)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        if (shopId <= 0) {
            finish()
            return
        }

        binding = ActivityCheckoutBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }

        binding.addressCard.setOnClickListener {
            addressLauncher.launch(AddressListActivity.selectIntent(this, viewModel.state.value.addressId ?: 0L))
        }
        binding.expectCard.setOnClickListener { showExpectPicker() }
        binding.submitButton.setOnClickListener {
            viewModel.submit(binding.remarkInput.text?.toString().orEmpty())
        }
        binding.backButton.setOnClickListener { finish() }

        observe()
    }

    private fun observe() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { state ->
                    binding.loadingBar.isVisible = state.loading

                    val error = state.loadError ?: state.localErrorRes?.let { getString(it) }
                    val blocking = error != null && state.preview == null
                    binding.errorGroup.isVisible = blocking
                    if (blocking) {
                        binding.errorText.text = error
                    }

                    state.preview?.let { renderPreview(it, state) }

                    // 有数据时的错误只 toast，不挡页面
                    if (error != null && !blocking) {
                        toast(error)
                        viewModel.consumeMessages()
                    }
                    state.actionError?.let {
                        toast(it)
                        viewModel.consumeMessages()
                    }

                    if (state.createdOrder != null && !navigated) {
                        navigated = true
                        toast(R.string.checkout_success)
                        startActivity(
                            Intent(this@CheckoutActivity, OrderListActivity::class.java).apply {
                                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or
                                    Intent.FLAG_ACTIVITY_SINGLE_TOP
                            },
                        )
                        finish()
                    }
                }
            }
        }
    }

    private fun renderPreview(preview: OrderPreview, state: CheckoutState) {
        binding.addressReceiver.text = preview.defaultAddress?.let {
            "${it.receiver} ${it.maskedPhone}"
        } ?: getString(R.string.checkout_address_empty)
        binding.addressText.text = preview.defaultAddress?.fullAddress
            ?: getString(R.string.checkout_address_hint)

        binding.expectValue.text = state.expectLabel

        binding.itemContainer.removeAllViews()
        preview.items.forEach { item ->
            val row = ItemCheckoutLineBinding.inflate(
                LayoutInflater.from(this),
                binding.itemContainer,
                false,
            )
            row.lineName.text = item.dishName
            row.lineSpec.isVisible = !item.specText.isNullOrBlank()
            row.lineSpec.text = item.specText
            row.lineQty.text = getString(R.string.checkout_qty, item.quantity)
            row.lineSubtotal.text = getString(R.string.money_amount, formatMoney(item.subtotal))
            binding.itemContainer.addView(row.root)
        }

        binding.totalAmountText.text = getString(R.string.money_amount, formatMoney(preview.totalAmount))
        binding.feeText.text = getString(R.string.money_amount, formatMoney(preview.deliveryFee))
        binding.payAmountText.text = getString(R.string.money_amount, formatMoney(preview.payAmount))
        binding.bottomPayText.text = getString(R.string.checkout_pay_amount, formatMoney(preview.payAmount))

        val short = preview.shortOfMin > 0
        binding.bottomHintText.isVisible = short
        if (short) {
            binding.bottomHintText.text = getString(
                R.string.checkout_short_of_min,
                formatMoney(preview.shortOfMin),
            )
        }

        binding.submitButton.isEnabled = state.canSubmit
        binding.submitButton.setText(
            if (state.submitting) R.string.checkout_submitting else R.string.checkout_submit,
        )
    }

    private fun showExpectPicker() {
        val options = viewModel.state.value.preview?.expectOptions.orEmpty()
        if (options.isEmpty()) return

        val labels = options.map { it.label }.toTypedArray()
        val checked = options.indexOfFirst { it.value == viewModel.state.value.expectValue }
            .coerceAtLeast(0)

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.checkout_expect_title)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                viewModel.selectExpect(options[which].value)
                dialog.dismiss()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    companion object {
        private const val EXTRA_SHOP_ID = "extra_shop_id"

        fun intent(context: Context, shopId: Long): Intent =
            Intent(context, CheckoutActivity::class.java)
                .putExtra(EXTRA_SHOP_ID, shopId)
    }
}
