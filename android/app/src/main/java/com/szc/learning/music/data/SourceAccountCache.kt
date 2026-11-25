package com.szc.learning.music.data

import android.content.Context
import com.google.gson.Gson
import com.szc.learning.music.data.source.SourceAccount

/**
 * 账号资料的本地缓存。
 *
 * 存在的理由只有一个：**别把账号页做成抓取器**。
 * 用户切一次 tab、退一次页面、杀一次进程，都可能让账号页重新拉一遍
 * 昵称/等级/歌单 —— 三次请求起步。平台的频控不看你的心情，只看请求数。
 *
 * 两层：
 * - 内存：同一次会话里进出页面零开销；
 * - SharedPreferences：冷启动不必重新打平台。
 *
 * TTL 10 分钟。超时后仍保留旧值（[getStale]），网络失败时用它撑住页面 ——
 * 「显示 12 分钟前的数据」远好过「一片空白 + 一个错误」。
 *
 * key 里带版本号：以后 [SourceAccount] 加字段，直接升 [VERSION]，
 * 旧缓存自然失效，不需要写迁移，也不会出现「缺字段导致 null 崩」。
 */
class SourceAccountCache(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val gson = Gson()
    private val memory = HashMap<String, SourceAccount>()

    /** TTL 内的缓存；过期返回 null。 */
    @Synchronized
    fun get(sourceKey: String): SourceAccount? {
        val cached = getStale(sourceKey) ?: return null
        val age = System.currentTimeMillis() - cached.fetchedAt
        return if (age in 0..TTL_MS) cached else null
    }

    /** 忽略 TTL 的缓存。只给「请求失败时兜底」用。 */
    @Synchronized
    fun getStale(sourceKey: String): SourceAccount? {
        memory[sourceKey]?.let { return it }
        val raw = prefs.getString(keyOf(sourceKey), null) ?: return null
        val parsed = runCatching { gson.fromJson(raw, SourceAccount::class.java) }.getOrNull() ?: return null
        memory[sourceKey] = parsed
        return parsed
    }

    @Synchronized
    fun put(account: SourceAccount) {
        memory[account.sourceKey] = account
        runCatching {
            prefs.edit().putString(keyOf(account.sourceKey), gson.toJson(account)).apply()
        }
    }

    /** 退出登录时清掉，避免下次登录读到上一个人的资料。 */
    @Synchronized
    fun clear(sourceKey: String) {
        memory.remove(sourceKey)
        prefs.edit().remove(keyOf(sourceKey)).apply()
    }

    private fun keyOf(sourceKey: String): String = "account_v${VERSION}_$sourceKey"

    private companion object {
        const val PREFS = "aurora_source_account"
        /** 加字段就 +1：旧缓存直接失效，不用写迁移，也不会出现「缺字段导致 null 崩」。 */
        const val VERSION = 4
        const val TTL_MS = 10 * 60 * 1000L
    }
}
