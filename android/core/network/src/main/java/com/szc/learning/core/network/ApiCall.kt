package com.szc.learning.core.network

import com.szc.learning.core.common.AppResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 统一的接口调用封装。
 *
 * 所有 Repository 都走这两个函数，保证「网络异常 / 业务失败 / 成功」的翻译只有一份实现。
 * 原来的写法是每个 Repository 各自复制一遍 try-catch，加第二个业务模块时就开始抄了。
 */

/**
 * 读接口：`data` 必须存在。
 *
 * `data` 为空视为服务端异常而不是成功，避免把 null 当成空列表传给 UI。
 */
suspend fun <T : Any> callData(block: suspend () -> AjaxResult<T>): AppResult<T> =
    withContext(Dispatchers.IO) {
        try {
            val response = block()
            val data = response.data
            when {
                !response.isSuccess -> AppResult.Failure(
                    response.msg.ifBlank { "请求失败" },
                    response.code,
                )
                data == null -> AppResult.Failure("服务端返回数据为空", AppResult.CODE_PARSE)
                else -> AppResult.Success(data)
            }
        } catch (throwable: Throwable) {
            throwable.toAppFailure()
        }
    }

/**
 * 写接口：只看 `code`，不要求 `data`。
 *
 * 若依的 `AjaxResult.success("删除成功")` 只有 code + msg，没有 data。
 */
suspend fun callUnit(block: suspend () -> AjaxResult<*>): AppResult<Unit> =
    withContext(Dispatchers.IO) {
        try {
            val response = block()
            if (response.isSuccess) {
                AppResult.Success(Unit)
            } else {
                AppResult.Failure(response.msg.ifBlank { "操作失败" }, response.code)
            }
        } catch (throwable: Throwable) {
            throwable.toAppFailure()
        }
    }
