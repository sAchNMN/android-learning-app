package com.szc.learning.ui.takeout

import android.content.Context
import android.view.LayoutInflater
import androidx.core.view.isVisible
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.szc.learning.R
import com.szc.learning.data.ShopCart
import com.szc.learning.databinding.ItemCartLineBinding
import com.szc.learning.databinding.SheetCartBinding

/**
 * 购物车弹层：逐行增减、清空。
 *
 * 购物车行数很少（同一家店的菜品组合），用 LinearLayout 动态添加，
 * 不上 RecyclerView —— 省一个 Adapter，且没有嵌套滚动问题。
 */
class CartSheetDialog(
    context: Context,
    cart: ShopCart,
    private val onIncrease: (String) -> Unit,
    private val onDecrease: (String) -> Unit,
    private val onClear: () -> Unit,
) : BottomSheetDialog(context) {

    private val binding = SheetCartBinding.inflate(LayoutInflater.from(context))

    init {
        setContentView(binding.root)
        binding.clearButton.setOnClickListener { confirmClear() }
        render(cart)
    }

    /** 购物车变化时由 Activity 调用，避免弹层与底部栏数据不一致。 */
    fun render(cart: ShopCart) {
        binding.lineContainer.removeAllViews()

        if (cart.isEmpty) {
            dismiss()
            return
        }

        cart.lines.forEach { line ->
            val row = ItemCartLineBinding.inflate(
                LayoutInflater.from(context),
                binding.lineContainer,
                false,
            )
            row.lineName.text = line.dishName
            row.lineSpec.isVisible = line.specText.isNotBlank()
            row.lineSpec.text = line.specText
            row.lineSubtotal.text = context.getString(R.string.money_amount, formatMoney(line.subtotal))
            row.qtyText.text = line.quantity.toString()
            row.plusButton.setOnClickListener { onIncrease(line.key) }
            row.minusButton.setOnClickListener { onDecrease(line.key) }
            binding.lineContainer.addView(row.root)
        }
    }

    private fun confirmClear() {
        MaterialAlertDialogBuilder(context)
            .setMessage(R.string.cart_clear_confirm)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_confirm) { _, _ ->
                onClear()
                dismiss()
            }
            .show()
    }
}
