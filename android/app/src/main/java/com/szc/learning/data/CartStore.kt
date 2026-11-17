package com.szc.learning.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.szc.learning.core.network.takeout.Dish
import com.szc.learning.core.network.takeout.DishSpecOption
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException

private val Context.cartDataStore: DataStore<Preferences> by preferencesDataStore(name = "cart")

private val CART_KEY = stringPreferencesKey("carts")

/**
 * 购物车的一行。
 *
 * [key] 由 dishId + 排序后的 optionIds 组成：同一道菜选不同规格算两行，
 * 同规格重复点只加数量。
 */
data class CartLine(
    val dishId: Long,
    val dishName: String,
    val unitPrice: Double,
    val quantity: Int,
    val specText: String = "",
    val optionIds: List<Long> = emptyList(),
) {
    val key: String get() = keyOf(dishId, optionIds)

    val subtotal: Double get() = unitPrice * quantity

    companion object {
        fun keyOf(dishId: Long, optionIds: List<Long>): String =
            if (optionIds.isEmpty()) dishId.toString()
            else "$dishId:${optionIds.sorted().joinToString(",")}"
    }
}

/** 一家店的购物车。 */
data class ShopCart(
    val shopId: Long = 0,
    val shopName: String = "",
    val lines: List<CartLine> = emptyList(),
) {
    val itemCount: Int get() = lines.sumOf { it.quantity }

    val totalAmount: Double get() = lines.sumOf { it.subtotal }

    val isEmpty: Boolean get() = lines.isEmpty()
}

/**
 * 购物车（本地，不落服务端）。
 *
 * 设计理由见 docs/data-model.md 第 6 节：购物车是会话态数据，
 * 服务端只在结算预览与下单时按提交内容重算价格，不需要知道购物车。
 *
 * 内存用 StateFlow 供 UI 订阅，落盘用 DataStore + Gson，
 * 保证杀进程后重新进店还能看到之前选的菜。
 */
class CartStore(
    context: Context,
    private val scope: CoroutineScope,
) {

    private val dataStore: DataStore<Preferences> = context.applicationContext.cartDataStore

    private val gson = Gson()

    private val _carts = MutableStateFlow<Map<Long, ShopCart>>(emptyMap())

    /** shopId -> 该店铺购物车。 */
    val carts: StateFlow<Map<Long, ShopCart>> = _carts.asStateFlow()

    init {
        scope.launch {
            val restored = read()
            if (restored.isNotEmpty()) {
                _carts.value = restored
            }
        }
    }

    fun cartOf(shopId: Long): ShopCart = _carts.value[shopId] ?: ShopCart(shopId = shopId)

    /** 加入购物车。有规格的菜品由调用方保证 [options] 已选齐必选项。 */
    fun add(shopId: Long, shopName: String, dish: Dish, options: List<DishSpecOption>) {
        val unitPrice = dish.price + options.sumOf { it.priceDelta }
        val optionIds = options.map { it.optionId }
        val specText = options.joinToString("/") { it.optionName }
        val key = CartLine.keyOf(dish.dishId, optionIds)

        mutate(shopId, shopName) { lines ->
            if (lines.any { it.key == key }) {
                lines.map { if (it.key == key) it.copy(quantity = it.quantity + 1) else it }
            } else {
                lines + CartLine(
                    dishId = dish.dishId,
                    dishName = dish.dishName,
                    unitPrice = unitPrice,
                    quantity = 1,
                    specText = specText,
                    optionIds = optionIds,
                )
            }
        }
    }

    fun increase(shopId: Long, lineKey: String) {
        mutate(shopId) { lines ->
            lines.map { if (it.key == lineKey) it.copy(quantity = it.quantity + 1) else it }
        }
    }

    fun decrease(shopId: Long, lineKey: String) {
        mutate(shopId) { lines ->
            lines.mapNotNull {
                when {
                    it.key != lineKey -> it
                    it.quantity > 1 -> it.copy(quantity = it.quantity - 1)
                    else -> null
                }
            }
        }
    }

    /**
     * 按菜品减一份。
     *
     * 菜品行上的「−」不知道用户想减哪个规格，取该菜品的第一行。
     * 要精确减某个规格，得进购物车弹层点那一行。
     */
    fun decreaseByDish(shopId: Long, dishId: Long) {
        mutate(shopId) { lines ->
            val target = lines.firstOrNull { it.dishId == dishId } ?: return@mutate lines
            lines.mapNotNull {
                when {
                    it.key != target.key -> it
                    it.quantity > 1 -> it.copy(quantity = it.quantity - 1)
                    else -> null
                }
            }
        }
    }

    fun clear(shopId: Long) {
        _carts.update { it - shopId }
        persist()
    }

    /** 退出登录时清空所有店铺的购物车。 */
    fun clearAll() {
        _carts.value = emptyMap()
        persist()
    }

    /** 跨店加购前清空别的店，避免结算时混单。 */
    fun clearOthers(keepShopId: Long) {
        _carts.update { current -> current.filterKeys { it == keepShopId } }
        persist()
    }

    private fun mutate(
        shopId: Long,
        shopName: String = "",
        block: (List<CartLine>) -> List<CartLine>,
    ) {
        _carts.update { current ->
            val cart = current[shopId] ?: ShopCart(shopId = shopId, shopName = shopName)
            val lines = block(cart.lines)
            if (lines.isEmpty()) {
                current - shopId
            } else {
                val name = if (shopName.isNotBlank()) shopName else cart.shopName
                current + (shopId to cart.copy(shopName = name, lines = lines))
            }
        }
        persist()
    }

    /**
     * 每次变更都写盘。
     *
     * 不做 debounce：DataStore 的写是串行的，连点十次也就十个小文件写，
     * 但 debounce 会引入「退出前最后一次没保存」的坑，不值。
     */
    private fun persist() {
        scope.launch {
            dataStore.edit { prefs ->
                prefs[CART_KEY] = gson.toJson(_carts.value)
            }
        }
    }

    private suspend fun read(): Map<Long, ShopCart> {
        val json = dataStore.data
            .catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }
            .first()[CART_KEY]
            ?: return emptyMap()

        return runCatching {
            gson.fromJson<Map<Long, ShopCart>>(json, CART_TYPE)
        }.getOrNull().orEmpty()
    }

    private companion object {
        val CART_TYPE = object : TypeToken<Map<Long, ShopCart>>() {}.type
    }
}
