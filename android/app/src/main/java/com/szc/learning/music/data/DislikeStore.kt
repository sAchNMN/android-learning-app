package com.szc.learning.music.data

import android.content.Context
import com.szc.learning.core.common.music.MusicTrack
import java.util.Locale

/**
 * 「不喜欢」。
 *
 * 能拿到的相似度信号只有歌手（没有音频特征，也没有曲风标签），所以「相似」按歌手算，
 * 分两档，一次误触不会把一个歌手整个拉黑：
 * - 第 1 次：这首歌不再出现；该歌手不再作为口味种子，不再被拿来扩展推荐；
 * - 第 2 次起：该歌手的所有歌都不再出现在推荐 / 刷歌流里。
 *
 * 只影响推荐和刷歌流，不碰本机曲库、歌单和收藏。
 */
class DislikeStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun dislike(track: MusicTrack) {
        val editor = prefs.edit()
        val keys = prefs.getStringSet(KEY_TRACKS, emptySet()).orEmpty().toMutableSet()
        keys += track.key
        keys += titleKey(track)
        editor.putStringSet(KEY_TRACKS, keys)
        val artist = mainArtist(track.artist)
        if (artist.isNotEmpty()) {
            editor.putInt(ARTIST_PREFIX + artist, artistCount(artist) + 1)
        }
        editor.apply()
    }

    /** 这首歌（或同名同歌手的别的音源版本）、或被拉黑的歌手的歌，不该再推荐。 */
    fun isBlocked(track: MusicTrack): Boolean {
        val keys = prefs.getStringSet(KEY_TRACKS, emptySet()).orEmpty()
        if (track.key in keys || titleKey(track) in keys) return true
        return artistCount(mainArtist(track.artist)) >= ARTIST_BLOCK_AT
    }

    /** 该歌手是否已被踩过：踩过就不再当口味种子。 */
    fun isSeedBlocked(artist: String): Boolean = artistCount(mainArtist(artist)) >= 1

    private fun artistCount(artist: String): Int =
        if (artist.isEmpty()) 0 else prefs.getInt(ARTIST_PREFIX + artist, 0)

    private fun titleKey(track: MusicTrack): String =
        "t:" + track.title.lowercase(Locale.ROOT).trim() + "|" + mainArtist(track.artist)

    private fun mainArtist(artist: String): String =
        artist.split('/', '、', ',', '，', '&')
            .firstOrNull { it.isNotBlank() }
            ?.trim()
            ?.lowercase(Locale.ROOT)
            .orEmpty()

    private companion object {
        const val PREFS = "aurora_dislike"
        const val KEY_TRACKS = "tracks"
        const val ARTIST_PREFIX = "a:"
        const val ARTIST_BLOCK_AT = 2
    }
}
