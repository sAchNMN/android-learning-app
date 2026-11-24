package com.szc.learning

import android.content.Context
import com.szc.learning.core.network.ApiClient
import com.szc.learning.core.network.music.MusicRepository
import com.szc.learning.music.data.ClientRecommendEngine
import com.szc.learning.music.data.DeviceIdStore
import com.szc.learning.music.data.DislikeStore
import com.szc.learning.music.data.FavoritesRepository
import com.szc.learning.music.data.FavoritesStore
import com.szc.learning.music.data.ListenStatsStore
import com.szc.learning.music.data.ListenEventReporter
import com.szc.learning.music.data.LocalMusicRepository
import com.szc.learning.music.data.LocalRecommendEngine
import com.szc.learning.music.data.LoginStore
import com.szc.learning.music.data.LyricsRepository
import com.szc.learning.music.data.OnlineSourceRouter
import com.szc.learning.music.data.PlayableFilter
import com.szc.learning.music.data.QueueSnapshotStore
import com.szc.learning.music.data.RecentStore
import com.szc.learning.music.data.RecommendCacheStore
import com.szc.learning.music.data.SharedPlaylistStore
import com.szc.learning.music.data.SourceConfigStore
import com.szc.learning.music.data.room.TogetherListenManager
import com.szc.learning.music.data.source.KugouClientSource
import com.szc.learning.music.data.source.NeteaseClientSource
import com.szc.learning.music.data.source.QqClientSource
import com.szc.learning.music.player.FeedAudioCache
import com.szc.learning.music.player.PlayerController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * 手动依赖容器，进程内单例。
 *
 * 依赖方向：Activity/Fragment → ViewModel → Repository → ApiClient → OkHttp。
 * 播放器是例外：它与页面生命周期无关，所以在容器里只建一次，
 * 主界面与各 tab 共享同一个实例，切页面不断音。
 */
class AppContainer(context: Context) {

    /** 应用级作用域：播放位置轮询、曲库加载这类"不随页面销毁"的任务用它。 */
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val apiClient: ApiClient = ApiClient(
        baseUrl = BuildConfig.BASE_URL,
        debug = BuildConfig.DEBUG,
    )

    /** 云端曲库。接口匿名可访问，没有登录体系。 */
    val musicRepository: MusicRepository = MusicRepository(apiClient.musicApi, BuildConfig.BASE_URL)

    /** 本地曲库：运行时读 MediaStore，不落库。 */
    val localMusicRepository: LocalMusicRepository = LocalMusicRepository(context)

    /** 内嵌歌词：只在当前播放曲目需要时解析 ID3，解析结果按曲目缓存。 */
    val lyricsRepository: LyricsRepository = LyricsRepository(context)

    /** 最近播放：数据量极小，SharedPreferences 足够，不必上 DataStore。 */
    val recentStore: RecentStore = RecentStore(context)

    /** 播放现场的落盘：进程被杀后靠它续播（见 PlaybackService.onPlaybackResumption）。 */
    val queueSnapshotStore: QueueSnapshotStore = QueueSnapshotStore(context)

    /** 收藏：存完整曲目，在线曲目也要能还原。 */
    val favoritesStore: FavoritesStore = FavoritesStore(context)

    /** 不喜欢：推荐和刷歌流的过滤依据。 */
    val dislikeStore: DislikeStore = DislikeStore(context)

    /** 设备号：后端用它归属「我喜欢」，App 没有登录体系。 */
    val deviceIdStore: DeviceIdStore = DeviceIdStore(context)

    /** 首次启动的登录选择：登录页是否走完、从哪个入口进来。 */
    val loginStore: LoginStore = LoginStore(context)

    /** 推荐结果缓存：断网时推荐页回放上一次的结果，而不是空白。 */
    val recommendCacheStore: RecommendCacheStore = RecommendCacheStore(context)

    /** 听歌时长：累计真实播放秒数，用于"我的"页的小统计。 */
    val listenStatsStore: ListenStatsStore = ListenStatsStore(context)

    /** 刷歌流的音频缓存：边播边写盘 + 预取下一首，只在本次会话有效。 */
    val feedCache: FeedAudioCache = FeedAudioCache(context)

    /** 音源开关 / 优先级 / Cookie，全部本机存，不再依赖 `/app/music/sources`。 */
    val sourceConfigStore: SourceConfigStore = SourceConfigStore(context)

    /**
     * 在线音源路由：**客户端直连优先**，后端只在三个平台都失败时兜底。
     *
     * [OnlineSourceRouter.startWatching] 在构造时启动，把首次探测的 2.5 秒
     * 挪到 App 启动阶段 —— 用户点搜索时后端状态已经知道了。
     */
    val onlineSourceRouter: OnlineSourceRouter = OnlineSourceRouter(
        remote = musicRepository,
        providers = listOf(NeteaseClientSource(), KugouClientSource(), QqClientSource()),
        configStore = sourceConfigStore,
        baseUrl = BuildConfig.BASE_URL,
        scope = appScope,
    ).also { it.startWatching() }

    /** 列表入库前验证可播性：换不到地址的在线曲目不展示。 */
    val playableFilter: PlayableFilter = PlayableFilter(onlineSourceRouter)

    /** 本地推荐：在线音源全都搜不到时，从本机曲库 + 收藏挑歌。 */
    val localRecommendEngine: LocalRecommendEngine = LocalRecommendEngine(
        localMusic = localMusicRepository,
        favorites = favoritesStore,
    )

    /** 在线推荐：客户端直连音源按口味歌手搜，不再经过后端。 */
    val clientRecommendEngine: ClientRecommendEngine = ClientRecommendEngine(
        router = onlineSourceRouter,
        favorites = favoritesStore,
    )

    /** 导入的分享歌单：码在后端，但「我导入过哪些」是本机事实，离线也要能看。 */
    val sharedPlaylistStore: SharedPlaylistStore = SharedPlaylistStore(context)

    /** 全局唯一播放器。 */
    val playerController: PlayerController = PlayerController(context, appScope)

    /**
     * 播放埋点：榜单的唯一数据来源。
     *
     * 挂在应用级作用域上而不是某个页面：用户在播放页以外的任何地方切歌，
     * 这次播放同样该被记上。`start()` 只订阅状态流，不发请求。
     */
    val listenEventReporter: ListenEventReporter = ListenEventReporter(
        state = playerController.state,
        position = playerController.position,
        repository = musicRepository,
        deviceIds = deviceIdStore,
        scope = appScope,
    ).also { it.start() }

    /**
     * 收藏的写入口。监听通知栏红心的 PlaybackService 也要用它 ——
     * 服务在 UI 之外，拿不到 ViewModel，只能共享这一层。
     */
    val favoritesRepository: FavoritesRepository = FavoritesRepository(
        context = context,
        store = favoritesStore,
        musicRepository = musicRepository,
        router = onlineSourceRouter,
        deviceIds = deviceIdStore,
        sources = sourceConfigStore,
        listenEvents = listenEventReporter,
        scope = appScope,
    )

    /**
     * 一起听。
     *
     * 构造不建连接：房间是用户主动开才存在的东西，App 一启动就连一个
     * WebSocket 占着不放，对不用这个功能的人是纯浪费。
     *
     * 作用域挂在 [appScope] 上而不是某个页面：用户退出房间界面去翻歌单时，
     * 同步必须继续 —— 一起听的听众不会因为你翻到了"我的"页就听不到声音。
     */
    val togetherListenManager: TogetherListenManager = TogetherListenManager(
        appContext = context.applicationContext,
        player = playerController,
        router = onlineSourceRouter,
        deviceId = deviceIdStore.id,
        baseUrl = BuildConfig.BASE_URL,
        parentScope = appScope,
    )
}
