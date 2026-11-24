package com.szc.learning.music.data

import com.szc.learning.core.common.music.MusicTrack
import com.szc.learning.core.common.music.TrackSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * 列表入库前的可播性验证。
 *
 * 为什么需要这一层：在线曲目在搜索结果里 `uri` 是空的，过去要点播时才发现这首
 * 是会员曲 / 已下架，用户看到的就是满屏「暂时换不到播放地址」。既然播不了，
 * 就别让它出现在列表里 —— 在这里先真换一次地址，换不到的直接剔除。
 *
 * 换到的地址会写进返回的曲目副本，`uri` 非空才能进播放队列（见 syncFeedQueue），
 * 所以这一步同时决定了刷歌流能不能滑得动。
 *
 * 三个必须守住的约束：
 * 1. **只验证在线且 uri 为空的曲目**。本地/云端曲目本来就有地址，在线曲目若已换到
 *    地址也不重复请求。
 * 2. **限并发 + 单曲超时**。20 首歌全打出去会压垮后端（它在换源时还要请求第三方平台），
 *    酷我 anti.s 接口也有频控。并发 [PARALLEL] 路，单曲最多等 [VERIFY_TIMEOUT_MS]。
 * 3. **结果只缓存在内存，绝不落盘**。CDN 地址是临时的，写进推荐缓存/收藏后下次启动
 *    读出来就是过期地址。落盘清理在各自的 Store 里做，这里只管内存。
 *
 * 失败也缓存（短 TTL）：同一首播不了的歌不该在推荐页、刷歌流里被反复验证。
 * 网络恢复靠短 TTL 自然过期 + 用户下拉刷新时 [clear]。
 */
class PlayableFilter(
    private val router: OnlineSourceRouter,
) {
    private data class Entry(val track: MusicTrack?, val at: Long)

    private val cache = mutableMapOf<String, Entry>()
    private val permits = Semaphore(PARALLEL)

    /**
     * 过滤列表，返回的曲目顺序与输入一致（并发返回顺序不代表展示顺序）。
     * 不可播的曲目被剔除。
     */
    suspend fun filter(tracks: List<MusicTrack>): List<MusicTrack> = withContext(Dispatchers.IO) {
        tracks.map { track ->
            async {
                when {
                    track.source != TrackSource.ONLINE || track.uri.isNotBlank() -> track
                    // 停用平台直接剔除，不发请求、不占并发槽位
                    DISABLED_SOURCES.contains(track.sourceKey) -> null
                    else -> resolve(track)
                }
            }
        }.awaitAll().filterNotNull()
    }

    private suspend fun resolve(track: MusicTrack): MusicTrack? {
        val now = System.currentTimeMillis()
        val cached = synchronized(cache) { cache[track.key] }
        if (cached != null && now - cached.at < ttlOf(cached.track)) return cached.track

        // 拿到许可后再查一次缓存：等待期间可能已经被另一首同 key 的请求填好了
        return permits.withPermit {
            synchronized(cache) { cache[track.key] }?.let { fresh ->
                if (System.currentTimeMillis() - fresh.at < ttlOf(fresh.track)) {
                    return@withPermit fresh.track
                }
            }
            val result = try {
                withTimeout(VERIFY_TIMEOUT_MS) {
                    @Suppress("DEPRECATION")
                    router.play(track).track
                }
            } catch (_: TimeoutCancellationException) {
                null
            } catch (_: Exception) {
                null
            }
            synchronized(cache) { cache[track.key] = Entry(result, System.currentTimeMillis()) }
            result
        }
    }

    /** 用户主动刷新时清缓存，让刚恢复网络的曲目有机会重新验证。 */
    fun clear() {
        synchronized(cache) { cache.clear() }
    }

    private fun ttlOf(track: MusicTrack?): Long =
        if (track == null) FAIL_TTL_MS else OK_TTL_MS

    private companion object {
        /** 全局停用的平台：酷我 anti.s 返回「下载 App」提示音，不是可播音频。 */
        val DISABLED_SOURCES = setOf(com.szc.learning.music.data.local.KuwoDirectSource.KEY)

        const val PARALLEL = 8
        const val VERIFY_TIMEOUT_MS = 8_000L
        const val OK_TTL_MS = 10 * 60 * 1_000L
        const val FAIL_TTL_MS = 30 * 1_000L
    }
}
