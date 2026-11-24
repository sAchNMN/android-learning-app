package com.szc.learning.music.ui.common

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.Log
import android.util.Size
import coil.ImageLoader
import coil.decode.DataSource
import coil.fetch.DrawableResult
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.request.Options

/**
 * 本地音频的封面取图器。
 *
 * 为什么需要它：Android 10 之后 MediaProvider **不再维护 albumart 表**，
 * `content://media/external/audio/albumart/{albumId}` 查出来是空的，
 * 而封面其实还在 mp3 的 ID3 里，只能通过
 * `ContentResolver.loadThumbnail(音频uri)` 让系统去解 ID3 的 APIC 帧。
 *
 * 所以本地曲目的 `artUri` 存的是**音频文件自己的 content uri**，
 * 由这个 Fetcher 拦下来换成缩略图；云端曲目仍是普通 http 地址，不受影响。
 */
class AudioArtFetcher(
    private val uri: Uri,
    private val options: Options,
    private val resolver: ContentResolver,
) : Fetcher {

    override suspend fun fetch(): FetchResult? {
        val bitmap = loadThumbnail() ?: return null
        Log.d(TAG, "封面命中 $uri → ${bitmap.width}x${bitmap.height}")
        return DrawableResult(
            drawable = BitmapDrawable(options.context.resources, bitmap),
            isSampled = false,
            dataSource = DataSource.DISK,
        )
    }

    /** 两条路都试：系统缩略图（快、有缓存）优先，失败再直接解 ID3。 */
    private fun loadThumbnail(): Bitmap? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching { resolver.loadThumbnail(uri, Size(THUMB_SIZE, THUMB_SIZE), null) }
                .onSuccess { if (it != null) return it }
                .onFailure { Log.d(TAG, "loadThumbnail 失败 $uri: ${it.message}") }
        }
        return embeddedPicture()
    }

    /** Android 9 及以下没有 loadThumbnail，退回 MediaMetadataRetriever 直接读 ID3。 */
    private fun embeddedPicture(): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            resolver.openFileDescriptor(uri, "r")?.use { descriptor ->
                retriever.setDataSource(descriptor.fileDescriptor)
                val bytes = retriever.embeddedPicture ?: return null
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }
        } catch (error: Throwable) {
            Log.d(TAG, "解 ID3 封面失败 $uri: ${error.message}")
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    class Factory(private val resolver: ContentResolver) : Fetcher.Factory<Uri> {
        override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? =
            if (isAudioMedia(data)) AudioArtFetcher(data, options, resolver) else null

        private fun isAudioMedia(uri: Uri): Boolean =
            uri.scheme == ContentResolver.SCHEME_CONTENT &&
                uri.authority?.contains("media") == true &&
                uri.path?.contains("/audio/media/") == true
    }

    private companion object {
        /** 封面最大边。列表和播放页最大也就 300dp 见方，512px 足够且解码便宜。 */
        const val THUMB_SIZE = 512
        const val TAG = "AudioArt"
    }
}
