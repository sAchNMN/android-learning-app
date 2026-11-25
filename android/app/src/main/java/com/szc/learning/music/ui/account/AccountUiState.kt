package com.szc.learning.music.ui.account

import com.szc.learning.music.data.source.SourceAccount

/**
 * 音乐账号页的状态。
 *
 * [account] 允许和 [sourceKey] 不同步：换了平台但新数据还没到时，
 * 页面会先把旧账号清掉，避免把网易云的昵称显示在酷狗那一行。
 *
 * [failed] 只在**没有任何数据可显示**时用来提示。请求失败但有缓存时，
 * 页面照常显示旧数据，不打扰用户 —— 「12 分钟前的昵称」不是错误。
 */
data class AccountUiState(
    val sourceKey: String = "",
    val account: SourceAccount? = null,
    val loading: Boolean = false,
    val failed: Boolean = false,
)
