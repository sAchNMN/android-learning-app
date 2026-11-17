package com.szc.learning.ui.address

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.szc.learning.R
import com.szc.learning.core.common.AppResult
import com.szc.learning.core.network.takeout.AddressDto
import com.szc.learning.core.network.takeout.AddressRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 地址列表状态。
 *
 * 失败原因拆成两个字段，不能混：
 * - [loadError] 列表没拉下来 → 整页错误态 + 重试
 * - [actionError] 删除 / 设默认失败 → 只 toast，列表照常展示
 *
 * @param busyAddressId 正在删除/设默认的那条地址，用于防连点
 */
data class AddressListState(
    val loading: Boolean = false,
    val addresses: List<AddressDto> = emptyList(),
    val busyAddressId: Long = 0L,
    val loadError: String? = null,
    val actionError: String? = null,
    @param:StringRes val messageRes: Int? = null,
)

/**
 * 地址列表。
 *
 * 删除和设默认之后都整表重拉：删除默认地址会让另一条自动变成默认，
 * 局部刷新留下的状态是错的。地址最多 20 条，重拉成本可以忽略。
 */
class AddressListViewModel(
    private val repository: AddressRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(AddressListState())
    val state: StateFlow<AddressListState> = _state.asStateFlow()

    fun load() {
        viewModelScope.launch {
            // 已有数据时静默刷新，不闪 loading
            _state.update { it.copy(loading = it.addresses.isEmpty(), loadError = null) }
            when (val result = repository.list()) {
                is AppResult.Success -> _state.update {
                    it.copy(loading = false, addresses = result.data, loadError = null)
                }

                is AppResult.Failure -> _state.update {
                    it.copy(loading = false, loadError = result.message)
                }
            }
        }
    }

    fun delete(addressId: Long) {
        if (_state.value.busyAddressId != 0L) return
        viewModelScope.launch {
            _state.update { it.copy(busyAddressId = addressId) }
            when (val result = repository.delete(addressId)) {
                is AppResult.Success -> {
                    _state.update {
                        it.copy(busyAddressId = 0L, messageRes = R.string.address_delete_success)
                    }
                    load()
                }

                is AppResult.Failure -> _state.update {
                    it.copy(busyAddressId = 0L, actionError = result.message)
                }
            }
        }
    }

    fun setDefault(addressId: Long) {
        if (_state.value.busyAddressId != 0L) return
        viewModelScope.launch {
            _state.update { it.copy(busyAddressId = addressId) }
            when (val result = repository.setDefault(addressId)) {
                is AppResult.Success -> {
                    _state.update {
                        it.copy(busyAddressId = 0L, messageRes = R.string.address_set_default_success)
                    }
                    load()
                }

                is AppResult.Failure -> _state.update {
                    it.copy(busyAddressId = 0L, actionError = result.message)
                }
            }
        }
    }

    fun consumeActionMessage() {
        _state.update { it.copy(actionError = null, messageRes = null) }
    }

    companion object {
        fun factory(repository: AddressRepository): ViewModelProvider.Factory = viewModelFactory {
            initializer { AddressListViewModel(repository) }
        }
    }
}
