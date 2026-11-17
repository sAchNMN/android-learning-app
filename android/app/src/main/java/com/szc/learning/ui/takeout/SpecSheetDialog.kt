package com.szc.learning.ui.takeout

import android.content.Context
import android.view.LayoutInflater
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.chip.Chip
import com.szc.learning.R
import com.szc.learning.core.network.takeout.Dish
import com.szc.learning.core.network.takeout.DishSpec
import com.szc.learning.core.network.takeout.DishSpecOption
import com.szc.learning.databinding.DialogSpecBinding
import com.szc.learning.databinding.ItemSpecGroupBinding

/**
 * 规格选择弹层。
 *
 * 规则与服务端下单校验一致：
 * - required=true 的组必须选中，否则「加入购物车」置灰
 * - multi=false 的组单选，multi=true 可多选
 * - 有 isDefault 的选项默认选中，减少用户点击
 *
 * 价格 = 菜品基础价 + 选中项 priceDelta 之和，仅用于展示；
 * 下单金额由服务端重算。
 */
class SpecSheetDialog(
    context: Context,
    private val dish: Dish,
    private val onConfirm: (List<DishSpecOption>) -> Unit,
) : BottomSheetDialog(context) {

    private val binding = DialogSpecBinding.inflate(LayoutInflater.from(context))

    private val optionsById: Map<Long, DishSpecOption> =
        dish.specs.flatMap { it.options }.associateBy { it.optionId }

    /** specId -> 已选 optionId 列表。 */
    private val selection = mutableMapOf<Long, List<Long>>()

    init {
        setContentView(binding.root)
        binding.dishName.text = dish.dishName

        dish.specs.forEach { spec -> addGroup(spec) }

        binding.addButton.setOnClickListener {
            if (!allRequiredChosen()) return@setOnClickListener
            onConfirm(chosenOptions())
            dismiss()
        }

        refresh()
    }

    private fun addGroup(spec: DishSpec) {
        val context = context
        val groupBinding = ItemSpecGroupBinding.inflate(
            LayoutInflater.from(context),
            binding.specContainer,
            false,
        )
        groupBinding.specName.text = spec.specName
        groupBinding.specHint.setText(hintOf(spec))
        groupBinding.optionGroup.isSingleSelection = !spec.multi

        spec.options.forEach { option ->
            val chip = LayoutInflater.from(context)
                .inflate(R.layout.chip_category, groupBinding.optionGroup, false) as Chip
            chip.text = if (option.priceDelta > 0) {
                context.getString(
                    R.string.spec_price_delta,
                    option.optionName,
                    formatMoney(option.priceDelta),
                )
            } else {
                option.optionName
            }
            chip.tag = option.optionId
            groupBinding.optionGroup.addView(chip)
            if (option.isDefault) {
                chip.isChecked = true
            }
        }

        // 默认选中项先记下来，用户不动也是合法组合
        selection[spec.specId] = groupBinding.optionGroup.checkedChipIds.mapNotNull { id ->
            groupBinding.optionGroup.findViewById<Chip>(id)?.tag as? Long
        }

        groupBinding.optionGroup.setOnCheckedStateChangeListener { group, checkedIds ->
            selection[spec.specId] = checkedIds.mapNotNull { id ->
                group.findViewById<Chip>(id)?.tag as? Long
            }
            refresh()
        }

        binding.specContainer.addView(groupBinding.root)
    }

    private fun hintOf(spec: DishSpec): Int = when {
        spec.required && spec.multi -> R.string.spec_hint_required_multi
        spec.required -> R.string.spec_hint_required
        spec.multi -> R.string.spec_hint_optional_multi
        else -> R.string.spec_hint_optional
    }

    private fun chosenOptions(): List<DishSpecOption> = dish.specs.flatMap { spec ->
        selection[spec.specId].orEmpty().mapNotNull { optionsById[it] }
    }

    private fun allRequiredChosen(): Boolean = dish.specs
        .filter { it.required }
        .all { selection[it.specId].orEmpty().isNotEmpty() }

    private fun refresh() {
        val chosen = chosenOptions()
        val price = dish.price + chosen.sumOf { it.priceDelta }

        binding.addButton.isEnabled = allRequiredChosen()
        binding.addButton.text = context.getString(R.string.spec_add_to_cart, formatMoney(price))

        val text = chosen.joinToString("/") { it.optionName }
        binding.selectedText.text =
            if (text.isBlank()) "" else context.getString(R.string.spec_selected, text)
    }
}
