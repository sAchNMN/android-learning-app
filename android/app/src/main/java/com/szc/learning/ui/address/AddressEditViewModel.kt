package com.szc.learning.ui.address

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.szc.learning.R
import com.szc.learning.core.common.AppResult
import com.szc.learning.core.common.map
import com.szc.learning.core.network.takeout.AddressBody
import com.szc.learning.core.network.takeout.AddressDto
import com.szc.learning.core.network.takeout.AddressRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 表单快照。由 Activity 从输入框收集后交给 ViewModel，ViewModel 不碰 View。 */
data class AddressForm(
    val receiver: String,
    val phone: String,
    val province: String,
    val city: String,
    val district: String,
    val detail: String,
    val tag: String?,
    val isDefault: Boolean,
)

/**
 * 地址编辑状态。
 *
 * @param fieldErrorRes 本地校验失败，展示在对应输入框上（先于网络请求）
 * @param actionError 服务端校验失败，toast 展示
 * @param savedAddressId 大于 0 表示保存成功，Activity 据此回传结果并关闭页面
 */
data class AddressEditState(
    val loading: Boolean = false,
    val saving: Boolean = false,
    val address: AddressDto? = null,
    val loadError: String? = null,
    @param:StringRes val fieldErrorRes: Int? = null,
    val actionError: String? = null,
    val savedAddressId: Long = 0L,
)

/**
 * 地址新增 / 修改。
 *
 * [addressId] 为 0 表示新增。
 * 校验规则与服务端 TakeoutAddressService#validate 一字不差，两边都必须有。
 */
class AddressEditViewModel(
    private val repository: AddressRepository,
    private val addressId: Long,
) : ViewModel() {

    private val _state = MutableStateFlow(AddressEditState())
    val state: StateFlow<AddressEditState> = _state.asStateFlow()

    init {
        if (addressId > 0) {
            load()
        }
    }

    fun load() {
        if (addressId <= 0) return
        viewModelScope.launch {
            _state.update { it.copy(loading = true, loadError = null) }
            when (val result = repository.detail(addressId)) {
                is AppResult.Success -> _state.update {
                    it.copy(loading = false, address = result.data)
                }

                is AppResult.Failure -> _state.update {
                    it.copy(loading = false, loadError = result.message)
                }
            }
        }
    }

    fun save(form: AddressForm) {
        if (_state.value.saving) return

        val errorRes = validate(form)
        if (errorRes != null) {
            _state.update { it.copy(fieldErrorRes = errorRes) }
            return
        }

        viewModelScope.launch {
            _state.update { it.copy(saving = true, fieldErrorRes = null, actionError = null) }
            val body = AddressBody(
                addressId = addressId.takeIf { it > 0 },
                receiver = form.receiver.trim(),
                phone = form.phone.trim(),
                province = form.province.trim(),
                city = form.city.trim(),
                district = form.district.trim(),
                detail = form.detail.trim(),
                tag = form.tag?.trim()?.takeIf { it.isNotEmpty() },
                isDefault = if (form.isDefault) "1" else "0",
            )
            val result: AppResult<Long> = if (addressId > 0) {
                repository.update(body).map { addressId }
            } else {
                repository.add(body)
            }
            when (result) {
                is AppResult.Success -> _state.update {
                    it.copy(saving = false, savedAddressId = result.data)
                }

                is AppResult.Failure -> _state.update {
                    it.copy(saving = false, actionError = result.message)
                }
            }
        }
    }

    fun consumeActionError() {
        _state.update { it.copy(actionError = null) }
    }

    fun clearFieldError() {
        _state.update { it.copy(fieldErrorRes = null) }
    }

    private fun validate(form: AddressForm): Int? {
        if (form.receiver.trim().length !in 2..20) return R.string.address_error_receiver
        if (!PHONE.matches(form.phone.trim())) return R.string.address_error_phone
        val regionOk = listOf(form.province, form.city, form.district)
            .all { it.trim().length in 2..32 }
        if (!regionOk) return R.string.address_error_region
        if (form.detail.trim().length !in 5..128) return R.string.address_error_detail
        return null
    }

    companion object {
        private val PHONE = Regex("^1[3-9]\\d{9}$")

        fun factory(repository: AddressRepository, addressId: Long): ViewModelProvider.Factory =
            viewModelFactory {
                initializer { AddressEditViewModel(repository, addressId) }
            }
    }
}
