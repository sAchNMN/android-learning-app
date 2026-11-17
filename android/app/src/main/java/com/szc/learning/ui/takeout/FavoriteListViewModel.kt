package com.szc.learning.ui.takeout

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.szc.learning.R
import com.szc.learning.core.common.AppResult
import com.szc.learning.core.network.takeout.Shop
import com.szc.learning.core.network.takeout.ShopRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 收藏列表状态。
 *
 * [messageRes] 是一类性提示（取消收藏成功），由 Activity 消费后清空。
 */
data class FavoriteState(
    val shops: List<Shop> = emptyList(),
    val pageNum: Int = 1,
    val total: Long = 0,
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val endReached: Boolean = false,
    val loadError: String? = null,
    val moreError: Boolean = false,
    @param:StringRes val messageRes: Int? = null,
)

/** 我的收藏（L5）。取消收藏后本地移除，不整页重拉。 */
class FavoriteListViewModel(
    private val repository: ShopRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(FavoriteState())
    val state: StateFlow<FavoriteState> = _state.asStateFlow()

    private var pageJob: Job? = null
    private var moreJob: Job? = null

    init {
        loadFirstPage()
    }

    fun refresh() = loadFirstPage()

    fun loadFirstPage() {
        pageJob?.cancel()
        moreJob?.cancel()
        pageJob = viewModelScope.launch {
            _state.update {
                it.copy(loading = true, loadError = null, loadingMore = false, moreError = false)
            }
            when (val result = repository.favorites(1, ShopRepository.PAGE_SIZE)) {
                is AppResult.Success -> _state.update {
                    it.copy(
                        loading = false,
                        shops = result.data.rows,
                        pageNum = 1,
                        total = result.data.total,
                        endReached = result.data.rows.size >= result.data.total,
                    )
                }

                is AppResult.Failure -> _state.update {
                    it.copy(loading = false, loadError = result.message)
                }
            }
        }
    }

    fun loadMore() {
        val snapshot = _state.value
        if (snapshot.loading || snapshot.loadingMore || snapshot.endReached) return
        if (moreJob?.isActive == true) return

        moreJob = viewModelScope.launch {
            _state.update { it.copy(loadingMore = true, moreError = false) }
            val next = _state.value.pageNum + 1
            when (val result = repository.favorites(next, ShopRepository.PAGE_SIZE)) {
                is AppResult.Success -> _state.update {
                    val merged = it.shops + result.data.rows
                    it.copy(
                        loadingMore = false,
                        shops = merged,
                        pageNum = next,
                        total = result.data.total,
                        endReached = merged.size >= result.data.total,
                    )
                }

                is AppResult.Failure -> _state.update {
                    it.copy(loadingMore = false, moreError = true)
                }
            }
        }
    }

    /**
     * 取消收藏：本地移除比整页重拉更稳。
     *
     * 重拉时用户会看到列表突然跳动，而且如果正好在翻页，页码会错。
     */
    fun unfavorite(shop: Shop) {
        viewModelScope.launch {
            when (val result = repository.unfavorite(shop.shopId)) {
                is AppResult.Success -> _state.update {
                    it.copy(
                        shops = it.shops.filterNot { item -> item.shopId == shop.shopId },
                        total = (it.total - 1).coerceAtLeast(0),
                        messageRes = R.string.favorite_cancelled,
                    )
                }

                is AppResult.Failure -> Unit
            }
        }
    }

    fun consumeMessage() {
        _state.update { it.copy(messageRes = null) }
    }

    companion object {
        fun factory(repository: ShopRepository): ViewModelProvider.Factory = viewModelFactory {
            initializer { FavoriteListViewModel(repository) }
        }
    }
}
