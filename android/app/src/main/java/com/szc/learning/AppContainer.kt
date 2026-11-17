package com.szc.learning

import android.content.Context
import com.szc.learning.core.network.ApiClient
import com.szc.learning.core.network.AuthRepository
import com.szc.learning.core.network.SessionStore
import com.szc.learning.core.network.music.MusicRepository
import com.szc.learning.music.data.LocalMusicRepository
import com.szc.learning.music.data.RecentStore
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

    /** 最近播放：数据量极小，SharedPreferences 足够，不必上 DataStore。 */
    val recentStore: RecentStore = RecentStore(context)

    /** 全局唯一播放器。 */
    val playerController: PlayerController = PlayerController(context, appScope)
}
