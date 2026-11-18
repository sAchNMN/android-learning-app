package com.szc.learning.core.network.music

import com.szc.learning.core.network.AjaxResult
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * 云端曲库 + 在线音源接口。
 *
 * 后端把「网易云 / 酷狗」的差异全部吃掉，客户端只认这一套参数：
 * 搜索拿统一模型，点播传 source + id + 换源用的元信息，失败时服务端会自动换源。
 */
interface MusicApi {

    /** 全部云端曲目，按上传时间倒序。 */
    @GET("app/music/tracks")
    suspend fun tracks(): AjaxResult<List<MusicTrackDto>>

    /**
     * 聚合搜索。
     *
     * @param sources 可选白名单，例如 `netease,kugou`；为空表示用音源管理里的开关。
     */
    @GET("app/music/search")
    suspend fun searchOnline(
        @Query("keyword") keyword: String,
        @Query("limit") limit: Int = 20,
        @Query("sources") sources: String? = null,
    ): AjaxResult<OnlineSearchDto>

    /**
     * 取播放地址。
     *
     * `fallback=true` 时，当前平台不可播会自动到别的平台找同名同歌手版本，
     * 响应里的 `switchedFrom/switchedTo` 告诉客户端换到了谁。
     */
    @GET("app/music/play")
    suspend fun playOnline(
        @Query("source") source: String,
        @Query("id") id: String,
        @Query("title") title: String? = null,
        @Query("artist") artist: String? = null,
        @Query("album") album: String? = null,
        @Query("durationMs") durationMs: Long? = null,
        @Query("hash") hash: String? = null,
        @Query("albumId") albumId: String? = null,
        @Query("albumAudioId") albumAudioId: String? = null,
        @Query("quality") quality: String? = null,
        @Query("fallback") fallback: Boolean = true,
    ): AjaxResult<OnlinePlayDto>

    @GET("app/music/lyric")
    suspend fun onlineLyric(
        @Query("source") source: String,
        @Query("id") id: String,
        @Query("hash") hash: String? = null,
        @Query("albumId") albumId: String? = null,
        @Query("albumAudioId") albumAudioId: String? = null,
        @Query("durationMs") durationMs: Long? = null,
        @Query("title") title: String? = null,
        @Query("artist") artist: String? = null,
    ): AjaxResult<String>

    @GET("app/music/sources")
    suspend fun sources(): AjaxResult<List<MusicSourceDto>>

    @PUT("app/music/sources/{key}")
    suspend fun updateSource(
        @Path("key") key: String,
        @Body body: SourceUpdateBody,
    ): AjaxResult<Void>

    // ---------------------------------------------------------------- 推荐

    /**
     * 猜你喜欢。
     *
     * @param artists 口味歌手，逗号分隔；后端还会去 own 的喜欢记录里补
     * @param seed    洗牌种子，null 表示按天固定（同一天结果一致）
     */
    @GET("app/music/recommend")
    suspend fun recommend(
        @Query("deviceId") deviceId: String? = null,
        @Query("artists") artists: String? = null,
        @Query("seed") seed: Long? = null,
        @Query("limit") limit: Int = 20,
    ): AjaxResult<RecommendDto>

    // ---------------------------------------------------------------- 我喜欢

    @POST("app/music/like")
    suspend fun setLike(@Body body: LikeBody): AjaxResult<LikeResultDto>

    @GET("app/music/likes")
    suspend fun likes(
        @Query("deviceId") deviceId: String,
        @Query("limit") limit: Int = 200,
    ): AjaxResult<List<UserLikeDto>>

    // ---------------------------------------------------------------- 个人歌单

    @GET("app/music/playlists")
    suspend fun playlists(): AjaxResult<List<UserPlaylistDto>>

    @GET("app/music/playlist")
    suspend fun playlistTracks(
        @Query("source") source: String,
        @Query("id") id: String,
        @Query("name") name: String? = null,
        @Query("limit") limit: Int = 200,
    ): AjaxResult<PlaylistDetailDto>
}