package com.szc.learning.ui.takeout

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
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
import com.szc.learning.core.network.takeout.Order
import com.szc.learning.core.network.takeout.OrderDetail
import com.szc.learning.databinding.ActivityOrderDetailBinding
import com.szc.learning.databinding.ItemCheckoutLineBinding
import com.szc.learning.databinding.ItemTimelineBinding
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 订单详情（D4）。
 *
 * 进行中的订单每 15 秒静默刷新一次：服务端会在查询时惰性推进状态
 * （超时取消 / 模拟接单），客户端不自己推断。
 */
class OrderDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityOrderDetailBinding

    private val container: AppContainer get() = (application as LearningApp).container

    private val orderId: Long by lazy { intent.getLongExtra(EXTRA_ORDER_ID, -1L) }

    private val viewModel: OrderDetailViewModel by viewModels {
        OrderDetailViewModel.factory(container.takeoutRepository, orderId)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        if (orderId <= 0) {
            finish()
            return
        }

        binding = ActivityOrderDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }

        binding.retryButton.setOnClickListener { viewModel.load() }

        observe()
        startPolling()
    }

    private fun observe() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { state ->
                    binding.loadingBar.isVisible = state.loading

                    val blocking = state.loadError != null && state.detail == null
                    binding.errorGroup.isVisible = blocking
                    if (blocking) {
                        binding.errorText.text = state.loadError
                    }

                    state.detail?.let { render(it, state.busy) }

                    state.actionError?.let {
                        toast(it)
                        viewModel.consumeError()
                    }
                }
            }
        }
    }

    /** 只在进行中轮询，已完成/已取消的订单没必要反复请求。 */
    private fun startPolling() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (isActive) {
                    delay(POLL_INTERVAL_MS)
                    val status = viewModel.state.value.detail?.order?.status
                    if (status == "0" || status == "1" || status == "2") {
                        viewModel.load(silent = true)
                    }
                }
            }
        }
    }

    private fun render(detail: OrderDetail, busy: Boolean) {
        val order = detail.order ?: return

        binding.statusTitle.text = getString(statusTitleOf(order.status))
        binding.statusHint.isVisible = true
        binding.statusHint.text = statusHintOf(order)

        renderTimeline(detail)

        binding.receiverText.text = "${order.receiver.orEmpty()} ${order.phone.orEmpty()}"
        binding.addressDetailText.text = order.address.orEmpty()

        binding.itemContainer.removeAllViews()
        detail.items.forEach { item ->
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

        binding.totalAmountText.text = getString(R.string.money_amount, formatMoney(order.totalAmount))
        binding.feeText.text = getString(R.string.money_amount, formatMoney(order.deliveryFee))
        binding.payAmountText.text = getString(R.string.money_amount, formatMoney(order.payAmount))

        binding.orderNoText.text = getString(R.string.order_no_label, order.orderNo)
        binding.createTimeText.text = getString(
            R.string.order_create_time_label,
            order.createTime.orEmpty(),
        )

        binding.remarkText.isVisible = !order.remark.isNullOrBlank()
        binding.remarkText.text = getString(R.string.order_remark_label, order.remark.orEmpty())

        binding.cancelReasonText.isVisible = !order.cancelReason.isNullOrBlank()
        binding.cancelReasonText.text = getString(
            R.string.order_cancel_reason,
            order.cancelReason.orEmpty(),
        )

        renderActions(order, busy)
    }

    private fun renderTimeline(detail: OrderDetail) {
        binding.timelineContainer.removeAllViews()
        val nodes = detail.timeline
        nodes.forEachIndexed { index, node ->
            val row = ItemTimelineBinding.inflate(
                LayoutInflater.from(this),
                binding.timelineContainer,
                false,
            )
            row.label.text = node.label
            row.time.text = node.time
            // 最后一个节点是当前状态，用实心点
            val isCurrent = index == nodes.lastIndex
            row.dot.setBackgroundResource(
                if (isCurrent) R.drawable.bg_dot_solid else R.drawable.bg_dot_hollow,
            )
            binding.timelineContainer.addView(row.root)
        }
    }

    private fun renderActions(order: Order, busy: Boolean) {
        binding.actionBar.isVisible = true
        binding.secondaryButton.isVisible = false
        binding.primaryButton.isVisible = false
        binding.secondaryButton.isEnabled = !busy
        binding.primaryButton.isEnabled = !busy

        when (order.status) {
            "0" -> {
                binding.secondaryButton.isVisible = true
                binding.secondaryButton.setText(R.string.order_action_cancel)
                binding.secondaryButton.setOnClickListener { askCancelReason() }

                binding.primaryButton.isVisible = true
                binding.primaryButton.setText(R.string.order_action_pay)
                binding.primaryButton.setOnClickListener {
                    startActivity(PayActivity.intent(this, orderId))
                }
            }

            "1" -> {
                binding.secondaryButton.isVisible = true
                binding.secondaryButton.setText(R.string.order_action_cancel)
                binding.secondaryButton.setOnClickListener { askCancelReason() }
            }

            "2" -> {
                binding.primaryButton.isVisible = true
                binding.primaryButton.setText(R.string.order_action_confirm)
                binding.primaryButton.setOnClickListener { askConfirmReceive() }
            }

            else -> {
                binding.primaryButton.isVisible = true
                binding.primaryButton.setText(R.string.order_action_reorder)
                binding.primaryButton.setOnClickListener {
                    startActivity(
                        Intent(this, ShopDetailActivity::class.java)
                            .putExtra(ShopDetailActivity.EXTRA_SHOP_ID, order.shopId),
                    )
                }
            }
        }
    }

    private fun statusTitleOf(status: String): Int = when (status) {
        "0" -> R.string.order_status_unpaid
        "1" -> R.string.order_status_paid
        "2" -> R.string.order_status_delivering
        "3" -> R.string.order_status_done
        "4" -> R.string.order_status_cancelled
        else -> R.string.order_status_unpaid
    }

    private fun statusHintOf(order: Order): String = when (order.status) {
        "0" -> getString(R.string.order_unpaid_hint)
        "2" -> order.expectTime?.let { getString(R.string.order_expect_delivery, it) }.orEmpty()
        "4" -> order.cancelReason.orEmpty()
        else -> ""
    }

    private fun askCancelReason() {
        val reasons = arrayOf(
            getString(R.string.order_cancel_reason_1),
            getString(R.string.order_cancel_reason_2),
            getString(R.string.order_cancel_reason_3),
            getString(R.string.order_cancel_reason_4),
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.order_cancel_title)
            .setItems(reasons) { _, which -> viewModel.cancel(reasons[which]) }
            .setNegativeButton(R.string.action_back, null)
            .show()
    }

    private fun askConfirmReceive() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.order_confirm_receive_title)
            .setMessage(R.string.order_confirm_receive_msg)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_confirm) { _, _ -> viewModel.confirm() }
            .show()
    }

    companion object {
        private const val POLL_INTERVAL_MS = 15_000L
        private const val EXTRA_ORDER_ID = "extra_order_id"

        fun intent(context: Context, orderId: Long): Intent =
            Intent(context, OrderDetailActivity::class.java)
                .putExtra(EXTRA_ORDER_ID, orderId)
    }
}
