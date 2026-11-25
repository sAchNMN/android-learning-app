package com.szc.learning.music.data

import com.szc.learning.core.common.music.MusicTrack
import com.szc.learning.core.common.music.TrackSource
import com.szc.learning.core.network.music.OnlinePlayback
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap

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
 *
 * 批量：有平台级批量接口的音源（网易云）一次问一批，其余仍逐首并发。
 * 原先每首一次请求会被同域名 450ms 节流串行化，整张歌单要等二十多秒。
 */
class PlayableFilter(
    private val router: OnlineSourceRouter,
) {
    private data class Entry(val track: MusicTrack?, val at: Long)

    /** 待取址的曲目及其在输入列表中的位置（结果要按原顺序放回）。 */
    private data class Slot(val index: Int, val track: MusicTrack)

    private class Cached(val track: MusicTrack?)

    private val cache = mutableMapOf<String, Entry>()
    private val permits = Semaphore(PARALLEL)

    /**
     * 过滤列表，返回的曲目顺序与输入一致（并发返回顺序不代表展示顺序）。
     * 不可播的曲目被剔除。
     */
    suspend fun filter(tracks: List<MusicTrack>): List<MusicTrack> = withContext(Dispatchers.IO) {
        if (tracks.isEmpty()) return@withContext emptyList()

        val output = arrayOfNulls<MusicTrack>(tracks.size)
        val pending = ArrayList<Slot>()

        tracks.forEachIndexed { index, track ->
            when {
                track.source != TrackSource.ONLINE || track.uri.isNotBlank() -> output[index] = track
                // 停用平台直接剔除，不发请求、不占并发槽位
                DISABLED_SOURCES.contains(track.sourceKey) -> Unit
                else -> {
                    val cached = freshCached(track)
                    if (cached != null) output[index] = cached.track else pending += Slot(index, track)
                }
            }
        }

        if (pending.isNotEmpty()) {
            val resolved = resolveSlots(pending)
            for (slot in pending) output[slot.index] = resolved[slot.track.key]
        }
        output.filterNotNull()
    }

    /**
     * 按音源分组取址：有批量能力的平台整组一次问完，其余逐首并发。
     * 返回 `key = MusicTrack.key`，同 key 的重复项共用一次请求。
     */
    private suspend fun resolveSlots(slots: List<Slot>): Map<String, MusicTrack?> {
        val results = ConcurrentHashMap<String, MusicTrack?>()
        coroutineScope {
            slots.groupBy { it.track.sourceKey }
                .map { (sourceKey, group) ->
                    async {
                        val distinct = group.map { it.track }.distinctBy { it.key }
                        if (router.canBatch(sourceKey)) {
                            resolveBatch(distinct, results)
                        } else {
                            distinct.map { track ->
                                async { results[track.key] = resolve(track) }
                            }.awaitAll()
                        }
                    }
                }
                .awaitAll()
        }
        return results
    }

    /** 走路由器批量取址，结果逐首写回缓存；批量失败/超时的项退回逐首。 */
    private suspend fun resolveBatch(
        tracks: List<MusicTrack>,
        results: MutableMap<String, MusicTrack?>,
    ) {
        val batch = runBatch(tracks)
        val missing = ArrayList<MusicTrack>()
        for (track in tracks) {
            val playback = batch[track.key]
            if (playback == null) {
                missing += track
                continue
            }
            val resolved = playback.track
            put(track.key, resolved)
            results[track.key] = resolved
        }
        // 批量整体失败或漏项：退回逐首，行为与没有批量接口时一致
        for (track in missing) results[track.key] = resolve(track)
    }

    /**
     * 一次批量请求的超时预算。
     *
     * 主请求本身很快，但没有下发 url 的曲目仍要逐首探外链（受同域名 450ms 节流），
     * 所以按曲目数给预算；它只是「彻底卡死」的兜底，不是每首的等待时间。
     */
    private suspend fun runBatch(tracks: List<MusicTrack>): Map<String, OnlinePlayback> {
        if (tracks.isEmpty()) return emptyMap()
        val budget = VERIFY_TIMEOUT_MS + tracks.size * BATCH_STEP_MS
        return try {
            permits.withPermit {
                withTimeout(budget) { router.playBatch(tracks) }
            }
        } catch (_: TimeoutCancellationException) {
            emptyMap()
        } catch (e: CancellationException) {
            // 外部取消（搜索作废、页面离开）：原样抛出，别把取消当成「播不了」
            throw e
        } catch (_: Exception) {
            emptyMap()
        }
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
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            put(track.key, result)
            result
        }
    }

    /** 写缓存并按上限淘汰最早写入的条目：歌单翻久了不能只增不减。 */
    private fun put(key: String, track: MusicTrack?) = synchronized(cache) {
        cache[key] = Entry(track, System.currentTimeMillis())
        if (cache.size > CACHE_MAX) {
            val iterator = cache.entries.iterator()
            while (cache.size > CACHE_MAX && iterator.hasNext()) {
                iterator.next()
                iterator.remove()
            }
        }
    }

    /** 缓存命中且没过期时返回条目；未命中或已过期返回 null。 */
    private fun freshCached(track: MusicTrack): Cached? {
        val entry = synchronized(cache) { cache[track.key] } ?: return null
        if (System.currentTimeMillis() - entry.at >= ttlOf(entry.track)) return null
        return Cached(entry.track)
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

        /** 内存缓存的条目上限（成功 + 失败）。 */
        const val CACHE_MAX = 400

        /** 批量预算里每多一首曲目的余量：够一次被节流的外链探测。 */
        const val BATCH_STEP_MS = 600L
        const val OK_TTL_MS = 10 * 60 * 1_000L
        const val FAIL_TTL_MS = 30 * 1_000L
    }
}
