package com.szc.learning.core.network.music

import com.szc.learning.core.network.AjaxResult
import retrofit2.http.Body
import retrofit2.http.DELETE
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
        @Query("cookies") cookies: String? = null,
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
        @Query("cookies") cookies: String? = null,
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
        @Query("cookies") cookies: String? = null,
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
        @Query("cookies") cookies: String? = null,
    ): AjaxResult<RecommendDto>

    // ---------------------------------------------------------------- 我喜欢

    @POST("app/music/like")
    suspend fun setLike(
        @Body body: LikeBody,
        @Query("cookies") cookies: String? = null,
    ): AjaxResult<LikeResultDto>

    @GET("app/music/likes")
    suspend fun likes(
        @Query("deviceId") deviceId: String,
        @Query("limit") limit: Int = 200,
    ): AjaxResult<List<UserLikeDto>>

    // ---------------------------------------------------------------- 个人歌单

    @GET("app/music/playlists")
    suspend fun playlists(@Query("cookies") cookies: String? = null): AjaxResult<List<UserPlaylistDto>>

    @GET("app/music/playlist")
    suspend fun playlistTracks(
        @Query("source") source: String,
        @Query("id") id: String,
        @Query("name") name: String? = null,
        @Query("limit") limit: Int = 200,
        @Query("cookies") cookies: String? = null,
    ): AjaxResult<PlaylistDetailDto>

    // ---------------------------------------------------------------- 歌单分享码

    /**
     * 生成 6 位分享码。
     *
     * 这是唯一必须依赖服务端的能力：短码要在中心分配，别的设备才查得到。
     * 后端不可用时这个方法会失败，UI 要明确提示「需连接后端」，不要静默吞掉。
     */
    @POST("app/music/share")
    suspend fun createShare(@Body body: ShareRequestDto): AjaxResult<ShareResultDto>

    /** 按码取歌单。码不存在或已过期，后端返回非 200 的业务码。 */
    @GET("app/music/share/{code}")
    suspend fun resolveShare(@Path("code") code: String): AjaxResult<ShareResultDto>

    // ---------------------------------------------------------------- 歌曲评论

    /**
     * 某首歌的评论列表。
     *
     * 用 `title + artist` 定位而不是曲目 id：同一首歌在网易云和酷我是两条记录，
     * 评论却应该共享。归一化由后端做，客户端只管把原始字段传上去。
     */
    @GET("app/music/comments")
    suspend fun comments(
        @Query("title") title: String,
        @Query("artist") artist: String,
        @Query("deviceId") deviceId: String,
        @Query("limit") limit: Int = 20,
        @Query("offset") offset: Int = 0,
    ): AjaxResult<CommentPageDto>

    @POST("app/music/comments")
    suspend fun createComment(@Body body: CommentBodyDto): AjaxResult<CommentDto>

    /** 点赞 / 取消。返回最新计数，客户端不做加减。 */
    @POST("app/music/comments/{id}/like")
    suspend fun likeComment(
        @Path("id") id: Long,
        @Body body: CommentLikeBodyDto,
    ): AjaxResult<CommentLikeResultDto>

    /** 只能删自己的；别人的返回业务错误。 */
    @DELETE("app/music/comments/{id}")
    suspend fun deleteComment(
        @Path("id") id: Long,
        @Query("deviceId") deviceId: String,
    ): AjaxResult<Void>
}