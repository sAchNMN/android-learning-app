package com.szc.learning.music.data

import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.szc.learning.core.common.music.MusicTrack
import com.szc.learning.core.common.music.TrackSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 本地曲库：直接读 MediaStore，不维护自己的数据库。
 *
 * 为什么不落库：系统媒体库已经是一份索引，再存一份就要处理"用户删了文件但库里还有"
 * 的同步问题。这里每次进资料库现查，几千首也在 100ms 量级。
 */
class LocalMusicRepository(private val context: Context) {

    suspend fun load(): List<MusicTrack> = withContext(Dispatchers.IO) {
        val collection: Uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }

        // Android 10 起用 RELATIVE_PATH（"Music/"），之前只能从 DATA 的绝对路径里截目录名
        val folderColumn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Audio.Media.RELATIVE_PATH
        } else {
            MediaStore.Audio.Media.DATA
        }

        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
            folderColumn,
        )

        // IS_MUSIC 过滤掉铃声、通知音；20 秒以下基本是提示音
        val selection =
            "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${MediaStore.Audio.Media.DURATION} > 20000"
        val sortOrder = "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC"

        val tracks = ArrayList<MusicTrack>()
        context.contentResolver.query(collection, projection, selection, null, sortOrder)
            ?.use { cursor -> readTracks(cursor, tracks) }
        tracks
    }

    private fun readTracks(cursor: Cursor, out: MutableList<MusicTrack>) {
        val idIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
        val titleIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
        val artistIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
        val albumIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
        val durationIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
        val folderIndex = cursor.getColumnIndex(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Audio.Media.RELATIVE_PATH
            } else {
                MediaStore.Audio.Media.DATA
            },
        )

        while (cursor.moveToNext()) {
            val id = cursor.getLong(idIndex)
            val rawArtist = cursor.getString(artistIndex).orEmpty()
            out += MusicTrack(
                id = id.toString(),
                title = cursor.getString(titleIndex).orEmpty().ifBlank { "未命名" },
                // MediaStore 对无艺人信息的文件会返回字面量 "<unknown>"
                artist = rawArtist.takeUnless { it.isBlank() || it == "<unknown>" }.orEmpty(),
                album = cursor.getString(albumIndex).orEmpty(),
                durationMs = cursor.getLong(durationIndex),
                uri = ContentUris.withAppendedId(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id,
                ).toString(),
                artUri = audioUri(id).toString(),
                source = TrackSource.LOCAL,
                folder = folderOf(cursor.getStringOrNull(folderIndex)),
            )
        }
    }

    /** "Music/Favorites/" → "Favorites"；拿不到就留空，由 UI 归到"未知文件夹"。 */
    private fun folderOf(path: String?): String {
        if (path.isNullOrBlank()) return ""
        val normalized = path.trimEnd('/')
        return normalized.substringAfterLast('/').ifBlank { normalized }
    }

    private fun Cursor.getStringOrNull(index: Int): String? =
        if (index >= 0 && !isNull(index)) getString(index) else null

    /**
     * 封面地址直接复用音频自己的 uri。
     *
     * 不用 `content://media/external/audio/albumart/{albumId}`：那张表从 Android 10
     * 起就没人维护了。真正的封面在 ID3 里，由 AudioArtFetcher 通过 loadThumbnail 取。
     */
    private fun audioUri(id: Long): Uri =
        ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
}