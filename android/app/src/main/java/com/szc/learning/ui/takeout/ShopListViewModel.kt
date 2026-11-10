package com.szc.learning.ui.takeout

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.szc.learning.core.common.AppResult
import com.szc.learning.core.common.UiState
import com.szc.learning.core.network.takeout.Shop
import com.szc.learning.core.network.takeout.TakeoutRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 店铺列表。 */
class ShopListViewModel(
    private val repository: TakeoutRepository,
) : ViewModel() {

    private val _state = MutableStateFlow<UiState<List<Shop>>>(UiState.Loading)
    val state: StateFlow<UiState<List<Shop>>> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _state.value = UiState.Loading
            when (val result = repository.shops()) {
                is AppResult.Success -> _state.value = UiState.Success(result.data)
                is AppResult.Failure -> _state.value = UiState.Error(result.message, result.code)
            }
        }
    }

    companion object {
        fun factory(repository: TakeoutRepository): ViewModelProvider.Factory = viewModelFactory {
            initializer { ShopListViewModel(repository) }
        }
    }
}
