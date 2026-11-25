package com.szc.learning.music.data

import android.content.Context
import com.szc.learning.R
import com.szc.learning.core.common.AppResult
import com.szc.learning.core.common.music.MusicTrack
import com.szc.learning.core.common.music.TrackSource
import com.szc.learning.core.network.music.MusicRepository
import com.szc.learning.music.ui.common.sourceLabel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/**
 * 收藏的唯一入口。
 *
 * 为什么不能留在 `MusicViewModel` 里：**通知栏也要能点红心**。
 * Service 活在 UI 之外，拿不到 ViewModel 实例，如果收藏的写入口在 ViewModel 上，
 * 通知栏就只能写本机、不走后端同步，两边行为会分叉。
 *
 * 这里只做「本机落盘 + 后端/平台同步」这一件事，两个调用方（ViewModel、PlaybackService）
 * 共享同一条路径：[toggle]。
 */
class FavoritesRepository(
    context: Context,
    private val store: FavoritesStore,
    private val musicRepository: MusicRepository,
    private val router: OnlineSourceRouter,
    private val deviceIds: DeviceIdStore,
    private val sources: SourceConfigStore,
    private val listenEvents: ListenEventReporter,
    private val scope: CoroutineScope,
) {

    private val appContext: Context = context.applicationContext

    /** 收藏变化流：通知栏和页面都订阅它，任一侧改动另一侧立刻能看到。 */
    val flow: StateFlow<FavoritesSnapshot> get() = store.flow

    /** 同步结果的一次性提示（已同步到哪个平台 / 为什么没同步上）。 */
    private val _notice = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val notice: SharedFlow<String> = _notice.asSharedFlow()

    fun keys(): Set<String> = store.flow.value.keys

    fun tracks(): List<MusicTrack> = store.flow.value.tracks

    fun isFavorite(key: String): Boolean = key in store.flow.value.keys

    /**
     * 切换收藏。**返回切换后的状态**，调用方直接拿去画图标。
     *
     * 本机先落盘（离线也能点），再异步推后端与平台。同步失败不改变本机结果 ——
     * 用户看到红心亮着，却收到一句「同步失败」是最容易让人困惑的组合。
     */
    fun toggle(track: MusicTrack): Boolean {
        val nowFavorite = store.toggle(track)
        // 红心是榜单里最强的一条正向信号，但只在「加上」时报：
        // 取消收藏多半是整理列表，不该把一首歌踩下去
        if (nowFavorite) listenEvents.reportLike(track)
        syncLike(track, nowFavorite)
        return nowFavorite
    }

    private fun syncLike(track: MusicTrack, liked: Boolean) {
        scope.launch {
            // 后端不在就到此为止：红心已经落在本机，这一层只是「顺手同步到平台」。
            // 弹「同步失败」会把本地成功说成失败，纯属误导。
            if (!router.probeNow(force = false)) return@launch
            val result = musicRepository.setLike(
                deviceIds.id,
                track,
                liked,
                sources.cookiesJson(),
            )
            when (result) {
                is AppResult.Success -> {
                    val data = result.data
                    // 只对在线音源给出同步提示：本地/云端本来就没有「平台」可同步
                    if (track.source != TrackSource.ONLINE) return@launch
                    val ok = data.syncs.filter { it.ok }
                    val failed = data.syncs.filterNot { it.ok }
                    val message = when {
                        !liked -> null
                        ok.isNotEmpty() -> appContext.getString(
                            R.string.like_synced,
                            ok.joinToString("、") { sourceLabel(appContext, it.source) },
                        )

                        failed.isNotEmpty() -> appContext.getString(
                            R.string.like_local_only,
                            failed.first().message,
                        )

                        !data.saved -> appContext.getString(R.string.like_backend_missing)
                        else -> null
                    }
                    message?.let { _notice.emit(it) }
                }

                is AppResult.Failure -> {
                    // 本机/云端曲目只做口味记录，后端挂了不该弹「同步失败」吓人
                    if (liked && track.source == TrackSource.ONLINE) {
                        _notice.emit(
                            appContext.getString(R.string.like_sync_failed, result.message),
                        )
                    }
                }
            }
        }
    }
}
