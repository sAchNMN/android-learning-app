package com.szc.learning.music.data.source

import android.os.SystemClock
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * 出站请求的节流与熔断。
 *
 * 客户端直连平台接口，最容易踩的一条线是**频率**：搜索结果页滚动、歌单连点、
 * 账号页反复进出，都会在几秒内对同一个域名连发十几个请求。平台的风控不看
 * 单个接口合不合理，只看单位时间内的请求数，超了就是 460（网易云「操作太频繁」）
 * 或 30020（酷狗「需要安全验证」），轻则这一批全失败，重则登录态被降权。
 *
 * 这里做三件事：
 * 1) **同域名串行 + 最小间隔**：把 `music.163.com` 的请求排成一队，间隔 [MIN_GAP_MS]。
 *    并发搜索三个平台时，不同域名各排各的，互不阻塞。
 * 2) **风控识别**：HTTP 403/429，或响应体里的风控码，记一次冷却。
 * 3) **冷却期内直接短路**：不发请求，返回 null。调用方按「拿不到」处理，
 *    而不会让一次风控滚成整页连续失败 —— 连续失败才是真正会被降权的模式。
 *
 * 有意为之的取舍：冷却时间（10 分钟）比平台实际要求的长。宁可少看一会儿数据，
 * 也不要把账号打进更长的黑名单。缓存层（[com.szc.learning.music.data.SourceAccountCache]）
 * 保证这段时间里用户看到的是上次的内容，不是空白。
 *
 * 等待用 `delay` 而不是 `Thread.sleep`：调用方都在协程里，页面切走 / 搜索作废时
 * 能立刻取消，不会把已经没人要的请求排进队列、继续占后面的节流名额。
 */
internal object SourceThrottle {

    /** 同一域名两次请求的最小间隔。450ms ≈ 每分钟 130 次，远低于人工操作会触发的量级。 */
    private const val MIN_GAP_MS = 450L

    /** 命中风控后的静默期。 */
    private const val COOLDOWN_MS = 10 * 60 * 1000L

    /** 只在短响应体里找风控码：正常数据动辄几十 KB，逐条正则匹配纯属浪费。 */
    private const val RISK_SCAN_LIMIT = 2_000

    private val RISK_PATTERN = Regex("\"code\"\\s*:\\s*-?460|\"err_code\"\\s*:\\s*30020")

    /** 每个域名一把锁：等锁就是排队，delay 期间可被协程取消。 */
    private val mutexes = ConcurrentHashMap<String, Mutex>()
    private val lastAt = ConcurrentHashMap<String, Long>()
    private val cooldownUntil = ConcurrentHashMap<String, Long>()

    /**
     * 取得一次请求许可。返回 false 表示该域名正在冷却，**不要发这个请求**。
     *
     * 会在协程里等待补足间隔；协程取消时立即返回（调用方随之取消，不发请求）。
     */
    suspend fun acquire(host: String): Boolean {
        if (host.isBlank()) return true
        return mutexFor(host).withLock {
            val now = SystemClock.elapsedRealtime()
            val until = cooldownUntil[host] ?: 0L
            if (until > now) return@withLock false
            val gap = (lastAt[host] ?: 0L) + MIN_GAP_MS - now
            if (gap > 0) delay(gap)
            lastAt[host] = SystemClock.elapsedRealtime()
            true
        }
    }

    /** 响应回来后判定是否踩了风控。 */
    fun inspect(host: String, httpCode: Int, body: String?) {
        if (host.isBlank()) return
        when {
            httpCode == 429 || httpCode == 403 -> markRisk(host)
            body != null && body.length <= RISK_SCAN_LIMIT && RISK_PATTERN.containsMatchIn(body) ->
                markRisk(host)
        }
    }

    /** 记一次冷却。重复命中不叠加，只从当前时刻重新计时。 */
    fun markRisk(host: String) {
        if (host.isBlank()) return
        cooldownUntil[host] = SystemClock.elapsedRealtime() + COOLDOWN_MS
    }

    /** 剩余冷却毫秒；0 表示正常。给 UI 说明「为什么没有数据」用。 */
    fun cooldownLeftMs(host: String): Long {
        val until = cooldownUntil[host] ?: return 0L
        return (until - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
    }

    fun isCoolingDown(host: String): Boolean = cooldownLeftMs(host) > 0

    fun hostOf(url: String): String = runCatching { url.toHttpUrlOrNull()?.host }.getOrNull().orEmpty()

    private fun mutexFor(host: String): Mutex = mutexes.getOrPut(host) { Mutex() }
}
