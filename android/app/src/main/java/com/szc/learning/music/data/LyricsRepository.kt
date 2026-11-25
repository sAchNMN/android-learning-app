package com.szc.learning.music.data

import android.content.Context
import android.net.Uri
import android.util.LruCache
import com.szc.learning.core.common.music.MusicTrack
import com.szc.learning.core.common.music.TrackSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 读取音频内嵌歌词。
 *
 * Android 的 MediaStore / MediaMetadataRetriever 都不暴露歌词字段，
 * 歌词藏在 ID3v2 的 `USLT`（非同步歌词）或 `SYLT`（同步歌词）帧里。
 * 解析细节在 [Id3Lyrics]，这里只负责按曲目缓存与选择数据来源。
 *
 * 本地曲目按需解析并缓存；云端曲目优先用接口下发的 `lyrics` 字段。
 */
class LyricsRepository(private val context: Context) {

    private val cache = object : LruCache<String, String>(24) {
        override fun sizeOf(key: String, value: String): Int = value.length.coerceAtLeast(1)
    }

    suspend fun lyrics(track: MusicTrack): String? = withContext(Dispatchers.IO) {
        track.lyrics?.trim()?.takeIf { it.isNotEmpty() }?.let {
            return@withContext Id3Lyrics.normalize(it)
        }
        if (track.source != TrackSource.LOCAL) return@withContext null

        cache.get(track.key)?.let { cached ->
            return@withContext if (cached == NO_LYRICS) null else cached
        }

        val parsed = runCatching { readEmbeddedLyrics(Uri.parse(track.uri)) }.getOrNull()
        cache.put(track.key, parsed ?: NO_LYRICS)
        parsed
    }

    private fun readEmbeddedLyrics(uri: Uri): String? {
        val input = context.contentResolver.openInputStream(uri) ?: return null
        return input.use { Id3Lyrics.read(it) }
    }

    private companion object {
        /** 用一个不可能出现在正常歌词里的哨兵表示"解析过，但没有歌词"。 */
        const val NO_LYRICS = "\u0000"
    }
}
