package com.szc.learning.ui.takeout

import android.content.Context
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
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.szc.learning.AppContainer
import com.szc.learning.LearningApp
import com.szc.learning.R
import com.szc.learning.core.common.toast
import com.szc.learning.databinding.ActivityPayBinding
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * 收银台（D3）。
 *
 * 支付接口幂等：重复点击第二次会拿到「订单状态已变更」，
 * 客户端不自己拦重复支付，交给服务端的 CAS。
 */
class PayActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPayBinding

    private val container: AppContainer get() = (application as LearningApp).container

    private val orderId: Long by lazy { intent.getLongExtra(EXTRA_ORDER_ID, -1L) }

    private val viewModel: PayViewModel by viewModels {
        PayViewModel.factory(container.takeoutRepository, orderId)
    }

    /** 成功弹窗只弹一次，否则每次状态变化都会再弹一遍。 */
    private var successShown = false

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        if (orderId <= 0) {
            finish()
            return
        }

        binding = ActivityPayBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }

        binding.payButton.setOnClickListener { viewModel.pay() }

        observe()
    }

    private fun observe() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { state ->
                    val order = state.order
                    binding.payAmountText.text = order?.let {
                        getString(R.string.money_amount, formatMoney(it.payAmount))
                    }.orEmpty()

                    binding.payButton.setText(
                        if (state.paying) R.string.checkout_submitting
                        else R.string.pay_confirm,
                    )
                    binding.payButton.isEnabled = state.canPay

                    binding.countdownText.isVisible = true
                    binding.countdownText.text = when {
                        state.loadError != null -> state.loadError
                        order == null -> getString(R.string.checkout_submitting)
                        state.expired -> getString(R.string.pay_expired)
                        order.status != "0" -> getString(R.string.pay_status_invalid)
                        else -> getString(R.string.pay_remaining, formatCountdown(state.remainingSeconds))
                    }

                    state.actionError?.let {
                        toast(it)
                        viewModel.consumeError()
                        // 状态冲突后重新拉一次，页面显示真实状态
                        viewModel.load()
                    }

                    if (state.paid && !successShown) {
                        successShown = true
                        showSuccess()
                    }
                }
            }
        }
    }

    private fun showSuccess() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.pay_success_title)
            .setCancelable(false)
            .setPositiveButton(R.string.pay_view_order) { _, _ ->
                startActivity(OrderDetailActivity.intent(this, orderId))
                finish()
            }
            .show()
    }

    private fun formatCountdown(totalSeconds: Long): String {
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
    }

    companion object {
        private const val EXTRA_ORDER_ID = "extra_order_id"

        fun intent(context: Context, orderId: Long): Intent =
            Intent(context, PayActivity::class.java).putExtra(EXTRA_ORDER_ID, orderId)
    }
}
