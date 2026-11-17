package com.szc.learning.ui.address

import android.app.Activity
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
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.szc.learning.AppContainer
import com.szc.learning.LearningApp
import com.szc.learning.R
import com.szc.learning.core.common.toast
import com.szc.learning.core.network.takeout.AddressDto
import com.szc.learning.databinding.ActivityAddressListBinding
import kotlinx.coroutines.launch

/**
 * 收货地址列表（L3）。
 *
 * 两种模式：
 * - [MODE_SELECT] 结算页进来，点一条回传 addressId 与展示文案
 * - [MODE_MANAGE] 「我的」进来，点一条进编辑
 *
 * 从编辑页返回后靠 onResume 刷新，不需要 ActivityResult 回调。
 */
class AddressListActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAddressListBinding

    private val container: AppContainer get() = (application as LearningApp).container

    private val viewModel: AddressListViewModel by viewModels {
        AddressListViewModel.factory(container.addressRepository)
    }

    private val selectMode: Boolean by lazy { intent.getStringExtra(EXTRA_MODE) == MODE_SELECT }

    private val selectedId: Long by lazy { intent.getLongExtra(EXTRA_SELECTED_ID, 0L) }

    /**
     * 必须 by lazy：Activity 的属性初始化发生在 attach 之前，那时 [intent] 还是 null。
     * 在 onCreate 首次访问时才构造，参数才是有效的。
     */
    private val adapter: AddressAdapter by lazy {
        AddressAdapter(
            selectMode = selectMode,
            selectedId = selectedId,
            onClick = { address -> if (selectMode) select(address) },
            onEdit = { address -> openEdit(address.addressId) },
            onDelete = { address -> confirmDelete(address) },
            onSetDefault = { address -> viewModel.setDefault(address.addressId) },
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityAddressListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }

        binding.addressRecycler.layoutManager = LinearLayoutManager(this)
        binding.addressRecycler.adapter = adapter

        binding.addButton.setOnClickListener { openEdit(0L) }
        binding.retryButton.setOnClickListener { viewModel.load() }

        observe()
    }

    override fun onResume() {
        super.onResume()
        // 首次进入、编辑返回、选择返回都走这里，保证列表与默认标记是最新的
        viewModel.load()
    }

    private fun observe() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { state ->
                    binding.loadingBar.isVisible = state.loading
                    adapter.submitList(state.addresses)

                    val showEmpty = !state.loading && state.loadError == null && state.addresses.isEmpty()
                    binding.emptyGroup.isVisible = showEmpty

                    val showError = !state.loading && state.loadError != null
                    binding.errorGroup.isVisible = showError
                    if (showError) {
                        binding.errorText.text = state.loadError
                    }

                    state.actionError?.let {
                        toast(it)
                        viewModel.consumeActionMessage()
                    }
                    state.messageRes?.let {
                        toast(getString(it))
                        viewModel.consumeActionMessage()
                    }
                }
            }
        }
    }

    private fun select(address: AddressDto) {
        setResult(
            Activity.RESULT_OK,
            Intent().apply {
                putExtra(EXTRA_RESULT_ADDRESS_ID, address.addressId)
                putExtra(
                    EXTRA_RESULT_ADDRESS_TEXT,
                    "${address.receiver} ${address.maskedPhone}\n${address.fullAddress}",
                )
            },
        )
        finish()
    }

    private fun openEdit(addressId: Long) {
        startActivity(AddressEditActivity.intent(this, addressId))
    }

    private fun confirmDelete(address: AddressDto) {
        MaterialAlertDialogBuilder(this)
            .setMessage(R.string.address_delete_confirm)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.address_delete) { _, _ ->
                viewModel.delete(address.addressId)
            }
            .show()
    }

    companion object {
        private const val EXTRA_MODE = "extra_mode"
        private const val EXTRA_SELECTED_ID = "extra_selected_id"
        private const val MODE_SELECT = "select"
        private const val MODE_MANAGE = "manage"

        const val EXTRA_RESULT_ADDRESS_ID = "extra_result_address_id"
        const val EXTRA_RESULT_ADDRESS_TEXT = "extra_result_address_text"

        /** 管理模式：从「我的」进入。 */
        fun manageIntent(context: Context): Intent =
            Intent(context, AddressListActivity::class.java).putExtra(EXTRA_MODE, MODE_MANAGE)

        /** 选择模式：从结算页进入，[selectedId] 用于高亮当前选中项。 */
        fun selectIntent(context: Context, selectedId: Long = 0L): Intent =
            Intent(context, AddressListActivity::class.java)
                .putExtra(EXTRA_MODE, MODE_SELECT)
                .putExtra(EXTRA_SELECTED_ID, selectedId)
    }
}
