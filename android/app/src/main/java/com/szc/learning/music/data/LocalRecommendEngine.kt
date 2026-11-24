package com.szc.learning.music.data

import com.szc.learning.core.common.music.MusicTrack
import com.szc.learning.core.network.music.RecommendResult
import java.time.LocalDate
import java.util.Locale
import kotlin.random.Random

/**
 * 「猜你喜欢」，**纯本机版本**。
 *
 * 数据源只取本机曲库 + 收藏：酷我直连因 anti.s 接口失效（返回「下载 App」提示音）
 * 已全链路停用，其它平台在后端离线时无客户端实现，所以离线推荐不能再靠在线搜索。
 *
 * 保留「同一天多次进入顺序一致」（seed 默认取当天天数 ×31），刷新时才变。
 *
 * 与在线推荐刻意不同的一点：**不排除已红心的歌**。在线推荐排除红心是为了「发现新歌」，
 * 但本机模式下收藏本身就是曲库来源，排除掉等于把最能代表口味的那批歌全删了。
 *
 * 口味排序：种子歌手（收藏 / 最近播放里出现最多的）的歌排前面，其余按 seed 洗牌。
 * 不训练模型、不猜音频特征 —— 拿不到播放行为明细之前，任何「深度学习推荐」都是自欺欺人。
 */
class LocalRecommendEngine(
    private val localMusic: LocalMusicRepository,
    private val favorites: FavoritesStore,
) {
    suspend fun recommend(
        artists: List<String>,
        seed: Long? = null,
        limit: Int = 20,
    ): RecommendResult {
        val size = limit.coerceIn(LIMIT_MIN, LIMIT_MAX)
        val seeds = artists.map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(MAX_SEEDS)
        val reason = if (seeds.isEmpty()) "discover" else "favorites"

        // 本机曲库 + 收藏，按 key 去重；收藏排在前面，它比扫描到的曲子更能代表口味
        val pool = LinkedHashMap<String, MusicTrack>()
        favorites.tracks().forEach { pool.putIfAbsent(it.key, it) }
        runCatching { localMusic.load() }
            .getOrDefault(emptyList())
            .forEach { pool.putIfAbsent(it.key, it) }

        val seedNorm = seeds.map { normalize(it) }.toSet()

        // 种子歌手的歌优先，其余洗牌在后 —— 保住收藏的原始顺序
        val (matched, rest) = pool.values.partition { track ->
            val artist = normalize(track.artist)
            seedNorm.any { s -> artist.contains(s) }
        }
        val picked = buildList {
            addAll(matched)
            addAll(rest.shuffled(Random(seed ?: defaultSeed())))
        }.distinctBy { dedupeKey(it) }
            .take(size)

        return RecommendResult(
            tracks = picked,
            basedOn = seeds,
            reason = reason,
        )
    }

    /** 去重键：歌名 + 主歌手。保留括号里的版本标识，`晴天 (Live)` 与 `晴天` 是两首歌。 */
    private fun dedupeKey(track: MusicTrack): String =
        normalize(track.title) + "|" + normalize(mainArtist(track.artist))

    private fun mainArtist(artist: String): String =
        artist.split(ARTIST_SEPARATORS).firstOrNull { it.isNotBlank() }?.trim() ?: artist.trim()

    private fun normalize(value: String): String =
        value.lowercase(Locale.ROOT).replace(PUNCTUATION, "").trim()

    private fun defaultSeed(): Long = LocalDate.now().toEpochDay() * 31

    private companion object {
        const val LIMIT_MIN = 6
        const val LIMIT_MAX = 50
        const val MAX_SEEDS = 3

        val ARTIST_SEPARATORS = Regex("[/、,，&]")
        val PUNCTUATION = Regex("[\\s\\p{Punct}，。、！？：；“”‘’（）【】《》·—…]")
    }
}
