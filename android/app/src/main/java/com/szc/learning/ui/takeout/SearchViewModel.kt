package com.szc.learning.ui.takeout

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
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
 * 搜索页状态。
 *
 * [searched] 与 [searching] 分开：前者决定显示「没找到」还是热搜词，
 * 后者只控制转圈。混在一起会出现「刚点搜索就显示没找到」。
 */
data class SearchState(
    val hotKeywords: List<String> = emptyList(),
    val keyword: String = "",
    val shops: List<Shop> = emptyList(),
    val pageNum: Int = 1,
    val total: Long = 0,
    val searching: Boolean = false,
    val searched: Boolean = false,
    val loadingMore: Boolean = false,
    val endReached: Boolean = false,
    val error: String? = null,
    val moreError: Boolean = false,
)

/** 店铺搜索（L2）。只搜店铺，菜品搜索接口服务端已支持但客户端暂未接入。 */
class SearchViewModel(
    private val repository: ShopRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(SearchState())
    val state: StateFlow<SearchState> = _state.asStateFlow()

    private var searchJob: Job? = null
    private var moreJob: Job? = null

    init {
        loadHotKeywords()
    }

    fun loadHotKeywords() {
        viewModelScope.launch {
            when (val result = repository.hotKeywords()) {
                is AppResult.Success -> _state.update { it.copy(hotKeywords = result.data) }
                // 热搜拉不到不影响搜索，静默失败
                is AppResult.Failure -> Unit
            }
        }
    }

    fun search(keyword: String) {
        val trimmed = keyword.trim()
        if (trimmed.isEmpty()) {
            _state.update { it.copy(error = null) }
            return
        }

        searchJob?.cancel()
        moreJob?.cancel()
        _state.update {
            it.copy(keyword = trimmed, searching = true, error = null, shops = emptyList())
        }

        searchJob = viewModelScope.launch {
            when (val result = repository.search(trimmed, 1, ShopRepository.PAGE_SIZE)) {
                is AppResult.Success -> _state.update {
                    it.copy(
                        searching = false,
                        searched = true,
                        shops = result.data.rows,
                        pageNum = 1,
                        total = result.data.total,
                        endReached = result.data.rows.size >= result.data.total,
                    )
                }

                is AppResult.Failure -> _state.update {
                    it.copy(searching = false, searched = true, error = result.message)
                }
            }
        }
    }

    fun loadMore() {
        val snapshot = _state.value
        if (snapshot.searching || snapshot.loadingMore || snapshot.endReached) return
        if (snapshot.keyword.isBlank() || moreJob?.isActive == true) return

        moreJob = viewModelScope.launch {
            _state.update { it.copy(loadingMore = true, moreError = false) }
            val next = _state.value.pageNum + 1
            when (val result = repository.search(snapshot.keyword, next, ShopRepository.PAGE_SIZE)) {
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

    /** 清空搜索，回到热搜词。 */
    fun reset() {
        searchJob?.cancel()
        _state.update { it.copy(searched = false, shops = emptyList(), keyword = "", error = null) }
    }

    companion object {
        fun factory(repository: ShopRepository): ViewModelProvider.Factory = viewModelFactory {
            initializer { SearchViewModel(repository) }
        }
    }
}
