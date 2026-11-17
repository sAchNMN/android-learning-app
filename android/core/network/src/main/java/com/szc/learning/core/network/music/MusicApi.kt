package com.szc.learning.core.network.music

import com.szc.learning.core.network.AjaxResult
import retrofit2.http.GET

/**
 * 云端曲库接口。
 *
 * 曲库本身是公开数据，不要求登录 —— 后端这两个接口要配成匿名可访问，
 * 否则答辩现场没登录就一片空白。
 */
interface MusicApi {

    /** 全部云端曲目，按上传时间倒序。 */
    @GET("app/music/tracks")
    suspend fun tracks(): AjaxResult<List<MusicTrackDto>>
}
