package com.szc.learning

import android.content.Context
import com.szc.learning.core.network.ApiClient
import com.szc.learning.core.network.AuthRepository
import com.szc.learning.core.network.SessionStore
import com.szc.learning.core.network.music.MusicRepository
import com.szc.learning.music.data.DeviceIdStore
import com.szc.learning.music.data.FavoritesStore
import com.szc.learning.music.data.ListenStatsStore
import com.szc.learning.music.data.LocalMusicRepository
import com.szc.learning.music.data.LyricsRepository
import com.szc.learning.music.data.RecentStore
import com.szc.learning.music.data.RecommendCacheStore
import com.szc.learning.music.player.PlayerController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * 手动依赖容器，进程内单例。
 *
 * 依赖方向：Activity/Fragment → ViewModel → Repository → ApiClient → OkHttp。
 * 播放器是例外：它与页面生命周期无关，所以在容器里只建一次，
 * MainActivity 和 NowPlayingActivity 共享同一个实例，切页面不断音。
 */
class AppContainer(context: Context) {

    /** 应用级作用域：播放位置轮询、曲库加载这类"不随页面销毁"的任务用它。 */
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val sessionStore: SessionStore = SessionStore(context)

    private val apiClient: ApiClient = ApiClient(
        baseUrl = BuildConfig.BASE_URL,
        tokenProvider = sessionStore::token,
        debug = BuildConfig.DEBUG,
    )

    val authRepository: AuthRepository = AuthRepository(apiClient.authApi, sessionStore)

    /** 云端曲库。接口匿名可访问，不登录也能拉全量列表。 */
    val musicRepository: MusicRepository = MusicRepository(apiClient.musicApi, BuildConfig.BASE_URL)

    /** 本地曲库：运行时读 MediaStore，不落库。 */
    val localMusicRepository: LocalMusicRepository = LocalMusicRepository(context)

    /** 内嵌歌词：只在当前播放曲目需要时解析 ID3，解析结果按曲目缓存。 */
    val lyricsRepository: LyricsRepository = LyricsRepository(context)

    /** 最近播放：数据量极小，SharedPreferences 足够，不必上 DataStore。 */
    val recentStore: RecentStore = RecentStore(context)

    /** 收藏：存完整曲目，在线曲目也要能还原。 */
    val favoritesStore: FavoritesStore = FavoritesStore(context)

    /** 设备号：后端用它归属「我喜欢」，App 没有登录体系。 */
    val deviceIdStore: DeviceIdStore = DeviceIdStore(context)

    /** 推荐结果缓存：断网时推荐页回放上一次的结果，而不是空白。 */
    val recommendCacheStore: RecommendCacheStore = RecommendCacheStore(context)

    /** 听歌时长：累计真实播放秒数，用于"我的"页的小统计。 */
    val listenStatsStore: ListenStatsStore = ListenStatsStore(context)

    /** 全局唯一播放器。 */
    val playerController: PlayerController = PlayerController(context, appScope)
}
