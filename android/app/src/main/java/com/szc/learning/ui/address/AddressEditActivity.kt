package com.szc.learning.ui.address

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.szc.learning.AppContainer
import com.szc.learning.LearningApp
import com.szc.learning.R
import com.szc.learning.core.common.toast
import com.szc.learning.core.network.takeout.AddressDto
import com.szc.learning.databinding.ActivityAddressEditBinding
import kotlinx.coroutines.launch

/**
 * 收货地址新增 / 编辑（D5）。
 *
 * [addressId] 为 0 表示新增。
 * 保存成功回传 [EXTRA_RESULT_ADDRESS_ID]，上层据此刷新。
 */
class AddressEditActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAddressEditBinding

    private val container: AppContainer get() = (application as LearningApp).container

    private val addressId: Long by lazy { intent.getLongExtra(EXTRA_ADDRESS_ID, 0L) }

    private val viewModel: AddressEditViewModel by viewModels {
        AddressEditViewModel.factory(container.addressRepository, addressId)
    }

    /** 进入页面时的表单快照，用来判断返回时要不要提示「放弃修改」。 */
    private var initialSnapshot: String? = null

    /** 编辑模式回填只做一次，否则每次状态变化都会覆盖用户正在输入的内容。 */
    private var filled = false

    private var resultDelivered = false

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityAddressEditBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }

        if (addressId > 0) {
            binding.titleText.setText(R.string.address_edit_title)
        } else {
            initialSnapshot = snapshot()
        }

        binding.saveButton.setOnClickListener { submit() }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                confirmDiscard()
            }
        })

        observe()
    }

    private fun observe() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { state ->
                    binding.saveButton.isEnabled = !state.saving && !state.loading
                    binding.saveButton.setText(
                        if (state.saving) R.string.address_saving else R.string.address_save,
                    )

                    if (state.address != null && !filled) {
                        fill(state.address)
                        filled = true
                    }

                    state.fieldErrorRes?.let {
                        showFieldError(it)
                        viewModel.clearFieldError()
                    }

                    state.actionError?.let {
                        toast(it)
                        viewModel.consumeActionError()
                    }

                    state.loadError?.let {
                        toast(it)
                        finish()
                        return@collect
                    }

                    if (state.savedAddressId > 0 && !resultDelivered) {
                        resultDelivered = true
                        setResult(
                            Activity.RESULT_OK,
                            Intent().putExtra(EXTRA_RESULT_ADDRESS_ID, state.savedAddressId),
                        )
                        finish()
                    }
                }
            }
        }
    }

    private fun submit() {
        viewModel.save(
            AddressForm(
                receiver = binding.receiverInput.text?.toString().orEmpty(),
                phone = binding.phoneInput.text?.toString().orEmpty(),
                province = binding.provinceInput.text?.toString().orEmpty(),
                city = binding.cityInput.text?.toString().orEmpty(),
                district = binding.districtInput.text?.toString().orEmpty(),
                detail = binding.detailInput.text?.toString().orEmpty(),
                tag = selectedTag(),
                isDefault = binding.defaultSwitch.isChecked,
            ),
        )
    }

    private fun showFieldError(@StringRes res: Int) {
        val message = getString(res)
        // 清掉旧的错误，避免两个输入框同时红
        binding.receiverLayout.error = null
        binding.phoneLayout.error = null
        binding.detailLayout.error = null

        when (res) {
            R.string.address_error_receiver -> {
                binding.receiverLayout.error = message
                binding.receiverInput.requestFocus()
            }

            R.string.address_error_phone -> {
                binding.phoneLayout.error = message
                binding.phoneInput.requestFocus()
            }

            R.string.address_error_detail -> {
                binding.detailLayout.error = message
                binding.detailInput.requestFocus()
            }

            // 省市区三个框没有单独的错误位，直接提示
            else -> toast(message)
        }
    }

    private fun fill(address: AddressDto) {
        binding.receiverInput.setText(address.receiver)
        binding.phoneInput.setText(address.phone)
        binding.provinceInput.setText(address.province.orEmpty())
        binding.cityInput.setText(address.city.orEmpty())
        binding.districtInput.setText(address.district.orEmpty())
        binding.detailInput.setText(address.detail)
        binding.defaultSwitch.isChecked = address.isDefaultAddress

        when (address.tag) {
            getString(R.string.address_tag_home) -> binding.tagHomeChip.isChecked = true
            getString(R.string.address_tag_company) -> binding.tagCompanyChip.isChecked = true
            getString(R.string.address_tag_school) -> binding.tagSchoolChip.isChecked = true
        }

        initialSnapshot = snapshot()
    }

    private fun selectedTag(): String? = when (binding.tagGroup.checkedChipId) {
        R.id.tagHomeChip -> getString(R.string.address_tag_home)
        R.id.tagCompanyChip -> getString(R.string.address_tag_company)
        R.id.tagSchoolChip -> getString(R.string.address_tag_school)
        else -> null
    }

    private fun snapshot(): String = listOf(
        binding.receiverInput.text?.toString().orEmpty(),
        binding.phoneInput.text?.toString().orEmpty(),
        binding.provinceInput.text?.toString().orEmpty(),
        binding.cityInput.text?.toString().orEmpty(),
        binding.districtInput.text?.toString().orEmpty(),
        binding.detailInput.text?.toString().orEmpty(),
        selectedTag().orEmpty(),
        binding.defaultSwitch.isChecked.toString(),
    ).joinToString("|")

    private fun confirmDiscard() {
        val changed = initialSnapshot != null && snapshot() != initialSnapshot
        if (!changed) {
            finish()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setMessage(R.string.address_discard_confirm)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_confirm) { _, _ -> finish() }
            .show()
    }

    companion object {
        private const val EXTRA_ADDRESS_ID = "extra_address_id"

        const val EXTRA_RESULT_ADDRESS_ID = "extra_result_address_id"

        /** [addressId] 传 0 表示新增。 */
        fun intent(context: Context, addressId: Long = 0L): Intent =
            Intent(context, AddressEditActivity::class.java)
                .putExtra(EXTRA_ADDRESS_ID, addressId)
    }
}
