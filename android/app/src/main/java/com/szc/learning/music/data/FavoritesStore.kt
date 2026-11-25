package com.szc.learning.music.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.szc.learning.core.common.music.MusicTrack
import com.szc.learning.core.common.music.TrackSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.Executors

/** 收藏的一份快照：键与完整曲目一起发，免得订阅方要读两次、还可能读到中间状态。 */
data class FavoritesSnapshot(
    val keys: Set<String>,
    val tracks: List<MusicTrack>,
)

/**
 * 我喜欢的歌。
 *
 * 存的是**完整曲目**，不是只有键。原因：推荐页里收藏的是在线曲目，
 * 它不在本地/云端曲库里，只有键的话下次打开根本还原不出标题、封面和取址参数。
 *
 * 与后端的关系：
 * - 本机写入先落盘（离线也能点红心），再异步推给后端和音源平台
 * - 后端返回的喜欢列表通过 [merge] 并回来，重装后能恢复
 * - 旧的纯键数据（`keys` StringSet）保留兼容：能匹配就继续显示，不会因为升级丢收藏
 */
class FavoritesStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val gson = Gson()
    private val type = object : TypeToken<List<MusicTrack>>() {}.type

    private val items: MutableList<MusicTrack> by lazy {
        runCatching {
            val json = prefs.getString(KEY_TRACKS, null)
            if (json.isNullOrBlank()) mutableListOf() else gson.fromJson<List<MusicTrack>>(json, type)
                ?.toMutableList() ?: mutableListOf()
        }.getOrDefault(mutableListOf())
    }

    /** 旧版本只存过键，这里只读不写，用来兜住升级前的数据。 */
    private val legacyKeys: Set<String>
        get() = prefs.getStringSet(KEY_LEGACY_KEYS, emptySet())?.toSet() ?: emptySet()

    /**
     * 收藏变化流。
     *
     * 存在的唯一理由：**通知栏也能点红心**。Service 里拿不到 ViewModel，
     * 只能让两边都盯着同一份数据 —— 否则通知栏改了收藏，页面上的心形不会变。
     * 懒初始化：首次订阅时才解析 JSON，不拖慢冷启动。
     */
    private val flowLazy = lazy { MutableStateFlow(snapshot()) }
    private val _flow: MutableStateFlow<FavoritesSnapshot> by flowLazy
    val flow: StateFlow<FavoritesSnapshot> get() = _flow

    /** 键集合缓存：`isFavorite()` 每次重建整个 Set，列表滚动时会反复调用。 */
    private var cachedKeys: Set<String>? = null

    /** 串行写盘：内存仍是真相源，序列化与写文件不占主线程。 */
    private val writeExecutor = Executors.newSingleThreadExecutor()

    @Synchronized
    fun snapshot(): FavoritesSnapshot = FavoritesSnapshot(keys(), items.toList())

    @Synchronized
    fun tracks(): List<MusicTrack> = items.toList()

    @Synchronized
    fun keys(): Set<String> {
        cachedKeys?.let { return it }
        return (items.map { it.key }.toSet() + legacyKeys).also { cachedKeys = it }
    }

    @Synchronized
    fun isFavorite(key: String): Boolean = key in keys()

    /** 返回切换后的状态：true = 已收藏。 */
    @Synchronized
    fun toggle(track: MusicTrack): Boolean {
        val nowFavorite = if (items.any { it.key == track.key }) {
            items.removeAll { it.key == track.key }
            false
        } else {
            items.add(0, track.copy(lyrics = null))
            true
        }
        if (!nowFavorite && track.key in legacyKeys) {
            // 旧数据里只有键，删掉时也要把键清掉，否则 keys() 又会把它算回来
            prefs.edit().putStringSet(KEY_LEGACY_KEYS, legacyKeys - track.key).apply()
        }
        cachedKeys = null
        persist()
        publish()
        return nowFavorite
    }

    /**
     * 把后端的喜欢列表并进来，返回新增条数。
     *
     * 只做「后端有、本机没有」的补写；本机有的以本机为准（可能刚点了取消、还没来得及同步）。
     */
    @Synchronized
    fun merge(remote: List<MusicTrack>): Int {
        val existing = items.map { it.key }.toSet()
        val added = remote.filter { it.key !in existing }
        if (added.isEmpty()) return 0
        items.addAll(added)
        cachedKeys = null
        persist()
        publish()
        return added.size
    }

    /** 只在有人订阅过 flow 时才写 —— 没人看就别白解析一遍 JSON 比快照。 */
    private fun publish() {
        if (flowLazy.isInitialized()) {
            _flow.value = snapshot()
        }
    }

    private fun persist() {
        // 快照必须在调用线程取：items 是可变列表，后台线程晚一点读可能读到后续改动
        val snapshot = items.toList()
        writeExecutor.execute {
            // 在线曲目的 uri 可能是播放时换到的临时 CDN 地址，落盘后下次启动就过期。
            // 这里统一清掉，点播时会重新换址；本地/云端曲目的有效 uri 保留。
            val serializable = snapshot.map {
                if (it.source == TrackSource.ONLINE) it.copy(uri = "", lyrics = null)
                else it.copy(lyrics = null)
            }
            // 序列化整份收藏是纯 CPU 活，别让主线程（以及 onPause 的 QueuedWork）等它
            prefs.edit().putString(KEY_TRACKS, gson.toJson(serializable)).apply()
        }
    }

    private companion object {
        const val PREFS = "aurora_favorites"
        const val KEY_TRACKS = "tracks_json"
        const val KEY_LEGACY_KEYS = "keys"
    }
}
