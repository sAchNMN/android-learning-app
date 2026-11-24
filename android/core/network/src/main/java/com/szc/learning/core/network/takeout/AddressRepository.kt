package com.szc.learning.core.network.takeout

import com.szc.learning.core.common.AppResult
import com.szc.learning.core.common.map
import com.szc.learning.core.network.callData
import com.szc.learning.core.network.callUnit

/**
 * 收货地址仓库。
 *
 * 只做协议翻译，不做业务判断：地址数量上限、默认地址唯一性都由服务端保证。
 */
class AddressRepository(
    private val api: AddressApi,
) {

    suspend fun list(): AppResult<List<AddressDto>> = callData { api.list() }

    suspend fun detail(addressId: Long): AppResult<AddressDto> = callData { api.detail(addressId) }

    /** 返回新地址的 id，调用方拿它回传给结算页。 */
    suspend fun add(body: AddressBody): AppResult<Long> =
        callData { api.add(body) }.map { it.addressId }

    suspend fun update(body: AddressBody): AppResult<Unit> = callUnit { api.update(body) }

    suspend fun delete(addressId: Long): AppResult<Unit> = callUnit { api.delete(addressId) }

    suspend fun setDefault(addressId: Long): AppResult<Unit> = callUnit { api.setDefault(addressId) }
}
