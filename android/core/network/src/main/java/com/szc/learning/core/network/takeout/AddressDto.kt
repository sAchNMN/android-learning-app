package com.szc.learning.core.network.takeout

import com.google.gson.annotations.SerializedName

/**
 * 收货地址。
 *
 * `isDefault` 后端是 char(1)（"1" 默认 / "0" 普通），这里保持字符串原样，
 * 用 [isDefaultAddress] 转布尔，不在 DTO 里改后端语义。
 */
data class AddressDto(
    @SerializedName("addressId") val addressId: Long = 0,
    @SerializedName("receiver") val receiver: String = "",
    @SerializedName("phone") val phone: String = "",
    @SerializedName("province") val province: String? = null,
    @SerializedName("city") val city: String? = null,
    @SerializedName("district") val district: String? = null,
    @SerializedName("detail") val detail: String = "",
    @SerializedName("fullAddress") val fullAddress: String = "",
    @SerializedName("tag") val tag: String? = null,
    @SerializedName("isDefault") val isDefault: String = "0",
) {
    val isDefaultAddress: Boolean get() = isDefault == "1"

    /** 手机号打码：138****8000。完整号码只在编辑页显示。 */
    val maskedPhone: String
        get() = if (phone.length == 11) "${phone.take(3)}****${phone.takeLast(4)}" else phone
}

/** 新增 / 修改地址的请求体。修改时必须带 addressId。 */
data class AddressBody(
    @SerializedName("addressId") val addressId: Long? = null,
    @SerializedName("receiver") val receiver: String,
    @SerializedName("phone") val phone: String,
    @SerializedName("province") val province: String,
    @SerializedName("city") val city: String,
    @SerializedName("district") val district: String,
    @SerializedName("detail") val detail: String,
    @SerializedName("tag") val tag: String? = null,
    @SerializedName("isDefault") val isDefault: String = "0",
)

/** 新增地址的返回：只有 id。 */
data class AddressIdResult(
    @SerializedName("addressId") val addressId: Long = 0,
)
