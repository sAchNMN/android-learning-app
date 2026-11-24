package com.szc.learning.music.data

import com.szc.learning.core.common.music.MusicTrack
import java.util.Locale

/**
 * 跨平台去重规则。
 *
 * 网易云和酷狗搜同一首歌会返回两条记录，标题里还常带「(Live)」「[remix]」这类后缀。
 * 判重只取**歌名主干 + 主歌手**，忽略标点和括号内容 —— 用户看到的是"同一个作品"，
 * 列表里就不该出现两行。
 *
 * 规则必须与后端 `RecommendService.dedupeKey` 完全一致：不一致的后果是
 * 后端兜底时同一首歌又冒出来一次，用户看到重复。
 */
internal object TrackDedupe {

    private val PUNCTUATION = Regex("[\\s\\p{Punct}，。、！？：；“”‘’（）【】《》·—…]")
    private val BRACKETS = Regex("\\([^)]*\\)|（[^）]*）|\\[[^\\]]*]")

    fun keyOf(track: MusicTrack): String =
        baseTitle(normalize(track.title)) + "|" + normalize(mainArtist(track.artist))

    /** 保留首次出现的顺序，后到的重复项丢弃。 */
    fun distinct(tracks: List<MusicTrack>): List<MusicTrack> {
        val seen = HashSet<String>(tracks.size)
        return tracks.filter { seen.add(keyOf(it)) }
    }

    /** 多歌手串里取第一个当作主歌手。 */
    private fun mainArtist(artist: String): String =
        artist.split("/", "、", ",", "，", "&").firstOrNull { it.isNotBlank() }?.trim()
            ?: artist.trim()

    private fun normalize(value: String): String =
        value.lowercase(Locale.ROOT).replace(PUNCTUATION, "").trim()

    private fun baseTitle(value: String): String = value.replace(BRACKETS, "")
}
