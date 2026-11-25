package com.szc.learning.music.data

import com.szc.learning.core.common.music.MusicTrack
import com.szc.learning.core.network.music.RecommendResult
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import java.time.LocalDate
import kotlin.random.Random

/**
 * 在线推荐引擎，**客户端自己算口味**。
 *
 * 原来是后端 `/app/music/recommend` 做的：按口味歌手聚合搜索 → 补平台推荐流
 * → 去重 → 排除已喜欢 → 按天洗牌。这套逻辑没有任何一步非要服务器不可，
 * 搬过来之后「换一批」不用等一次 15 秒的后端超时。
 *
 * 三级取数，与后端同口径：
 * 1. 有口味歌手 → 每个歌手搜 [PER_ARTIST] 首（并行）
 * 2. 不够 [limit] → 平台新歌推荐补齐
 * 3. 仍然为空 → 调用方回退 [LocalRecommendEngine]（本机曲库）
 *
 * 不排除「已收藏」这件事只在 [LocalRecommendEngine] 成立（那边本机曲库小，
 * 排掉红心会没歌可放）；在线推荐必须排除，否则首页天天推同一批。
 */
class ClientRecommendEngine(
    private val router: OnlineSourceRouter,
    private val favorites: FavoritesStore,
) {

    /**
     * @param artists 口味歌手，来自收藏与播放历史；为空时纯走平台推荐流
     * @param seed    洗牌种子，null 表示按天固定（同一天多次进入结果一致）
     */
    suspend fun recommend(
        artists: List<String>,
        seed: Long?,
        limit: Int = DEFAULT_LIMIT,
    ): RecommendResult {
        val seeds = artists.filter { it.isNotBlank() }.distinct().take(MAX_ARTISTS)
        val collected = mutableListOf<MusicTrack>()

        // 1.1 按口味歌手并行搜
        if (seeds.isNotEmpty()) {
            coroutineScope {
                seeds.map { artist ->
                    async { router.search(artist, PER_ARTIST).tracks }
                }.awaitAll().forEach { collected += it }
            }
        }

        // 1.2 不够就用平台推荐流补齐
        if (collected.size < limit) {
            collected += router.dailyNewSongs(limit)
        }

        // 1.3 去重 → 排除已收藏 → 洗牌
        val liked = favorites.keys()
        val fresh = TrackDedupe.distinct(collected)
            .filterNot { it.key in liked }
            .shuffled(Random(seed ?: dailySeed()))

        return RecommendResult(
            tracks = fresh.take(limit),
            basedOn = seeds,
            reason = if (seeds.isEmpty()) REASON_DISCOVER else REASON_ARTISTS,
        )
    }

    /**
     * 按天固定的种子。
     *
     * 用天数而不是毫秒：同一天里反复进推荐页该是同一批歌，
     * 否则用户会觉得"怎么每次都在换"。点「换一批」才传显式 seed。
     */
    private fun dailySeed(): Long = LocalDate.now().toEpochDay()

    private companion object {
        const val PER_ARTIST = 8
        const val MAX_ARTISTS = 3
        const val DEFAULT_LIMIT = 20
        const val REASON_ARTISTS = "artists"
        const val REASON_DISCOVER = "discover"
    }
}
