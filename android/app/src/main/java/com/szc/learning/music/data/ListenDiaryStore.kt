package com.szc.learning.music.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.szc.learning.core.common.music.MusicTrack
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 听歌日记：按天记时长和曲目。
 *
 * 和 [ListenStatsStore] 的分工：那个只存一个累计秒数（"我的"页上一行数字就够），
 * 这个要支撑周报 —— 必须有日期维度，否则「这周听了多少」永远算不出来。
 * 两个都留着，是因为累计值查询极其频繁（每次进我的页），不该每次都遍历 30 天的数据。
 *
 * 存 SharedPreferences + Gson：30 天 × 几十首的 JSON 大概几十 KB，
 * 与收藏、歌单同一套做法，不引新依赖。
 *
 * 只留最近 [KEEP_DAYS] 天：再久的数据在一张周报里用不到，
 * 而无限增长的小 JSON 迟早会拖慢每次读写的序列化。
 */
class ListenDiaryStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val gson = Gson()
    private val type = object : TypeToken<MutableMap<String, DiaryDay>>() {}.type
    private val dayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    /**
     * 记一段真实播放时长。
     *
     * [track] 为空时只累加当天总时长（比如曲目信息暂时读不到），
     * 宁可少一条曲目统计，也不要丢掉这十秒钟。
     */
    @Synchronized
    fun add(track: MusicTrack?, seconds: Long) {
        if (seconds <= 0) return
        val days = load()
        val today = dayFormat.format(Date())
        val day = days[today] ?: DiaryDay()
        val tracks = day.tracks.toMutableMap()
        if (track != null) {
            val prev = tracks[track.key]
            tracks[track.key] = TrackTime(
                title = track.title,
                seconds = (prev?.seconds ?: 0L) + seconds,
            )
        }
        days[today] = day.copy(seconds = day.seconds + seconds, tracks = tracks)
        prune(days)
        save(days)
    }

    /** 最近 [days] 天（含今天）的周报。没有记录的日期补 0，图表才不会缺柱子。 */
    @Synchronized
    fun recentReport(dayCount: Int = 7): WeekReport {
        val all = load()
        val calendar = Calendar.getInstance()
        val dates = ArrayList<String>(dayCount)
        val points = ArrayList<DayPoint>(dayCount)
        repeat(dayCount) { offset ->
            val date = calendar.time
            val key = dayFormat.format(date)
            dates += key
            points += DayPoint(key, all[key]?.seconds ?: 0L)
            calendar.add(Calendar.DAY_OF_YEAR, -1)
        }
        // 上面是从今天往回走，画图要按时间正序
        points.reverse()

        val total = points.sumOf { it.seconds }
        val activeDays = points.count { it.seconds > 0 }
        val best = points.maxByOrNull { it.seconds }

        // 曲目统计跨天合并：同一首歌分三天听，周报里应该是一行
        val merged = LinkedHashMap<String, TrackTime>()
        all.filterKeys { it in dates.toSet() }.values.forEach { day ->
            day.tracks.forEach { (key, item) ->
                val prev = merged[key]
                merged[key] = TrackTime(item.title, (prev?.seconds ?: 0L) + item.seconds)
            }
        }
        val top = merged.entries
            .sortedByDescending { it.value.seconds }
            .take(TOP_TRACKS)
            .map { TrackStat(it.key, it.value.title, it.value.seconds) }

        return WeekReport(
            totalSeconds = total,
            days = points,
            activeDays = activeDays,
            bestDay = best?.takeIf { it.seconds > 0 },
            topTracks = top,
        )
    }

    private fun prune(days: MutableMap<String, DiaryDay>) {
        if (days.size <= KEEP_DAYS) return
        val keep = days.keys.sortedDescending().take(KEEP_DAYS).toSet()
        days.keys.retainAll(keep)
    }

    private fun load(): MutableMap<String, DiaryDay> = runCatching {
        val json = prefs.getString(KEY_DAYS, null)
        if (json.isNullOrBlank()) {
            null
        } else {
            gson.fromJson<MutableMap<String, DiaryDay>>(json, type)
        }
    }.getOrNull() ?: mutableMapOf()

    private fun save(days: MutableMap<String, DiaryDay>) {
        prefs.edit().putString(KEY_DAYS, gson.toJson(days)).apply()
    }

    private companion object {
        const val PREFS = "aurora_listen_diary"
        const val KEY_DAYS = "days_json"
        const val KEEP_DAYS = 30

        /** 周报只列前 5 首：再多就成了一张列表，不是「本周最爱」。 */
        const val TOP_TRACKS = 5
    }
}

/** 单日记录。[tracks] 的 key 是 `MusicTrack.key`。 */
data class DiaryDay(
    val seconds: Long = 0,
    val tracks: Map<String, TrackTime> = emptyMap(),
)

data class TrackTime(
    val title: String = "",
    val seconds: Long = 0,
)

data class DayPoint(val date: String, val seconds: Long)

data class TrackStat(val key: String, val title: String, val seconds: Long)

/** 周报。所有时长都是秒，展示层统一换算。 */
data class WeekReport(
    val totalSeconds: Long,
    val days: List<DayPoint>,
    val activeDays: Int,
    val bestDay: DayPoint?,
    val topTracks: List<TrackStat>,
) {
    val isEmpty: Boolean get() = totalSeconds <= 0
}
