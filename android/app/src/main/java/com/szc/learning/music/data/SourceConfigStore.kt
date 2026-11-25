package com.szc.learning.music.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.szc.learning.core.network.music.MusicSourceInfo
import com.szc.learning.music.data.local.KuwoDirectSource
import com.szc.learning.music.ui.common.sourceLabel

/**
 * 在线音源的开关、优先级、Cookie —— **全部存在本机**。
 *
 * 为什么要从后端搬走：
 * 1. 后端关掉时音源管理页不能变成一片空白，否则「App 脱离后端可用」就是假话；
 * 2. Cookie 是用户自己在手机上登录平台后拿到的凭证，没有理由经过一台第三方服务器。
 *    放本机反而缩小了泄露面 —— 这一条比第一条更重要。
 *
 * 与后端 `/app/music/sources` 的关系：
 * 后端那套配置只作为**首次初始化**的参考（[seedFromRemote]），之后以本机为准，
 * 不做双向同步。理由是两边同时改会产生冲突，而音源配置这种「人对自己的手机」的
 * 设置没有跨设备一致性的需求。
 *
 * 用 SharedPreferences 而不是 DataStore：数据和 [FavoritesStore] 同级（几 KB），
 * 且调用点都在主线程同步读，上 DataStore 会把 4 个调用点全改成 suspend，收益为零。
 */
class SourceConfigStore(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val gson = Gson()
    private val type = object : TypeToken<List<SourceConfig>>() {}.type

    private val items: MutableList<SourceConfig> by lazy { load() }

    /** 按优先级升序；同优先级保持写入顺序，避免列表每次刷新跳动。 */
    @Synchronized
    fun all(): List<SourceConfig> = items.sortedBy { it.priority }

    @Synchronized
    fun enabledKeys(): List<String> = all().filter { it.enabled }.map { it.key }

    @Synchronized
    fun cookieOf(key: String): String = items.firstOrNull { it.key == key }?.cookie.orEmpty()

    /**
     * 把本机登录态打包给后端：只带已启用且有 Cookie 的平台。
     *
     * 后端拿它**只用于本次请求**，不落库 —— 凭证始终留在用户自己手机上。
     * 没登录时返回 null，连查询参数都不带，省得后端白解析一遍。
     */
    @Synchronized
    fun cookiesJson(): String? {
        val map = enabledKeys().associateWith { cookieOf(it) }
            .filterValues { it.isNotBlank() }
        return if (map.isEmpty()) null else gson.toJson(map)
    }

    @Synchronized
    fun setEnabled(key: String, enabled: Boolean): Boolean {
        // 酷我 anti.s 失效（播放出来是「下载 App」的提示音），全链路停用。
        // 就算用户在音源页手动打开也不能生效，否则又是一屏播不了的歌。
        if (key == KuwoDirectSource.KEY) return false
        return update(key) { it.copy(enabled = enabled) }
    }

    @Synchronized
    fun setCookie(key: String, cookie: String): Boolean =
        update(key) { it.copy(cookie = cookie.trim()) }

    @Synchronized
    fun setPriority(key: String, priority: Int): Boolean =
        update(key) { it.copy(priority = priority) }

    /**
     * 上移 / 下移。交换两项的 priority 而不是整体重排 ——
     * 和原来后端 `PUT /sources/{key}` 的语义保持一致，少一次「本地理想顺序 vs 库里数字打架」的问题。
     */
    @Synchronized
    fun move(key: String, offset: Int): Boolean {
        val current = all()
        val index = current.indexOfFirst { it.key == key }
        if (index < 0) return false
        val target = index + offset
        if (target !in current.indices) return false
        val first = current[index]
        val second = current[target]
        items.replaceAll {
            when (it.key) {
                first.key -> it.copy(priority = second.priority)
                second.key -> it.copy(priority = first.priority)
                else -> it
            }
        }
        persist()
        return true
    }

    /** 给 UI 用的展示模型，字段与后端 `/sources` 对齐，页面不用改。 */
    fun infos(): List<MusicSourceInfo> = all().map {
        MusicSourceInfo(
            key = it.key,
            label = sourceLabel(appContext, it.key),
            enabled = it.enabled,
            priority = it.priority,
            hasCookie = it.cookie.isNotBlank(),
        )
    }

    /**
     * 首次启动时用后端的配置做初始值。只补本机还没有的键，
     * **不覆盖**已有项，也不把 Cookie 写进来（后端本来就不回传 Cookie）。
     */
    @Synchronized
    fun seedFromRemote(remote: List<MusicSourceInfo>): Boolean {
        if (remote.isEmpty()) return false
        val existing = items.map { it.key }.toSet()
        // 后端的初始配置还带着酷我/QQ，本版本不支持，不能跟着它把死入口搬回来
        val missing = remote.filter { it.key in SUPPORTED_KEYS && it.key !in existing }
        if (missing.isEmpty()) return false
        items += missing.map {
            SourceConfig(
                key = it.key,
                enabled = it.enabled,
                priority = it.priority,
                cookie = "",
            )
        }
        persist()
        return true
    }

    private fun update(key: String, transform: (SourceConfig) -> SourceConfig): Boolean {
        val index = items.indexOfFirst { it.key == key }
        if (index < 0) return false
        items[index] = transform(items[index])
        persist()
        return true
    }

    /**
     * 只保留网易云和酷狗。
     *
     * 酷我 anti.s 失效（播出来是「请下载酷我 App」的提示音）；
     * QQ 匿名态取址成功率接近零，且网页登录态实测也救不回来。
     * 两个都从配置里彻底剔除，否则老用户的存档会一直把它们带回来。
     */
    private fun load(): MutableList<SourceConfig> {
        val stored = runCatching {
            val json = prefs.getString(KEY_CONFIG, null)
            if (json.isNullOrBlank()) null else gson.fromJson<List<SourceConfig>>(json, type)
        }.getOrNull()
        if (!stored.isNullOrEmpty()) {
            // 1.1 一次性迁移：剔除已下线的平台，酷我若被老版本存成开启也强制关掉
            val migrated = stored
                .filter { it.key in SUPPORTED_KEYS }
                .map { if (it.key == KuwoDirectSource.KEY) it.copy(enabled = false) else it }
            if (migrated.size != stored.size || migrated != stored) {
                // 不能直接调 persist()：它读 items，而 items 正在初始化（by lazy 递归）。
                prefs.edit().putString(KEY_CONFIG, gson.toJson(migrated)).apply()
            }
            if (migrated.isNotEmpty()) return migrated.toMutableList()
        }
        return defaults().toMutableList()
    }

    private fun persist() {
        prefs.edit().putString(KEY_CONFIG, gson.toJson(items)).apply()
    }

    private companion object {
        const val PREFS = "aurora_sources"
        const val KEY_CONFIG = "config_json"

        /** 当前版本支持的音源。其余 key 一律视为历史数据，读配置时剔除。 */
        val SUPPORTED_KEYS = setOf("netease", "kugou")

        /** 默认两个平台开启，顺序即优先级。 */
        fun defaults(): List<SourceConfig> = listOf(
            SourceConfig("netease", enabled = true, priority = 10),
            SourceConfig("kugou", enabled = true, priority = 20),
        )
    }
}

/** 单个平台的本地配置。[cookie] 只在 [SourceConfigStore] 内部流转，不进日志、不上报。 */
data class SourceConfig(
    val key: String,
    val enabled: Boolean,
    val priority: Int,
    val cookie: String = "",
)
