package com.szc.learning.music.data.source

import com.szc.learning.core.common.music.MusicTrack
import com.szc.learning.core.network.music.OnlinePlayback
import com.szc.learning.core.network.music.UserPlaylistInfo

/**
 * 客户端直连的在线音源。
 *
 * 三个平台各实现一份，[com.szc.learning.music.data.OnlineSourceRouter] 按用户在
 * 音源管理页排的顺序依次调它们。后端只在这三个都拿不到结果时才作为兜底参与
 * —— 那时说明是平台侧改版了，换后端还能救一次。
 *
 * 契约上所有方法都**不抛异常**：
 * - 搜不到返回空列表（"确实没有这首歌"和"平台挂了"对用户是同一件事，都不该弹错误）
 * - 取不到地址返回 `track == null` 的 [OnlinePlayback]，message 是给人看的原因
 * - 歌词拿不到返回空串，UI 显示"暂无歌词"
 *
 * 这不是"防御性编程"，是业务需要：搜索结果里有一首取址失败不该让整页报错。
 */
interface ClientSourceProvider {

    /** 与后端 SQL 的 `source_key` 一致（netease / kugou / qq），缓存和收藏都靠它做键。 */
    val key: String

    /** 搜索结果。 */
    suspend fun search(keyword: String, limit: Int, cookie: String): List<MusicTrack>

    /**
     * 换取播放地址。
     *
     * 成功时返回的 track 已填好 [MusicTrack.uri]。
     * [quality] 是用户选的档位，平台不支持某个档位时可以忽略它 ——
     * 但必须在返回的 [OnlinePlayback] 里带上**实际**档位/码率，
     * 上层靠这个比对「请求的」和「拿到的」来决定要不要提示权限不足。
     */
    suspend fun resolve(
        track: MusicTrack,
        cookie: String,
        quality: AudioQuality = AudioQuality.DEFAULT,
    ): OnlinePlayback

    /**
     * 平台是否有真正的批量取址接口。
     *
     * 只有网易云的 `/api/song/enhance/player/url` 支持一次问多个 id；
     * 酷狗只能逐首换，硬凑批量只会把并发度降低，所以保持 false。
     */
    val supportsBatch: Boolean get() = false

    /**
     * 批量取址，返回 `key = MusicTrack.key` 的映射，**必须包含每个输入曲目**
     * （不可播的项放 `track == null` 的结果）。
     *
     * 默认实现逐首调用 [resolve]，语义与单曲路径完全一致；有批量接口的平台覆盖它。
     */
    suspend fun resolveBatch(
        tracks: List<MusicTrack>,
        cookie: String,
        quality: AudioQuality = AudioQuality.DEFAULT,
    ): Map<String, OnlinePlayback> = tracks.associate { it.key to resolve(it, cookie, quality) }

    /** LRC 文本。 */
    suspend fun lyric(track: MusicTrack, cookie: String): String

    /**
     * 平台自己的推荐流，用来给「猜你喜欢」兜底。
     *
     * 只有网易云实现（新歌推荐是公开接口，不带 Cookie 也能用）；其它平台
     * 没有等价的匿名入口，返回空即可 —— 推荐引擎会把它们跳过。
     */
    suspend fun dailyRecommend(limit: Int, cookie: String): List<MusicTrack> = emptyList()

    /**
     * 账号里的歌单。需要用户填了 Cookie，否则返回空 —— 只有网易云实现了这一层
     * （酷狗的网页版没有等价的匿名/半匿名入口）。
     */
    suspend fun myPlaylists(cookie: String): List<UserPlaylistInfo> = emptyList()

    /**
     * 登录后的账号资料。拿不到就返回 null（未登录、登录态失效、平台没有公开接口）。
     *
     * 实现方**不抛异常**：账号页是纯展示，不该因为一个字段解析失败整页报错。
     */
    suspend fun account(cookie: String): SourceAccount? = null

    /** 歌单里的曲目。 */
    suspend fun playlistTracks(
        playlistId: String,
        name: String?,
        limit: Int,
        cookie: String,
    ): List<MusicTrack> = emptyList()
}
