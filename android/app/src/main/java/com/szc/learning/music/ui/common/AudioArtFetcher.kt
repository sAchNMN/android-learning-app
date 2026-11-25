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
import coil.size.Dimension

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
            isSampled = sampled,
            dataSource = DataSource.DISK,
        )
    }

    /** 内嵌封面是否做过降采样（只影响 Coil 的缓存策略标记）。 */
    private var sampled = false

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
                decodeSampled(bytes)
            }
        } catch (error: Throwable) {
            Log.d(TAG, "解 ID3 封面失败 $uri: ${error.message}")
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    /**
     * 按请求尺寸降采样解码。
     *
     * 内嵌封面常是 1000~3000px 的原图，全尺寸解一张就是十几 MB。
     * 先只读边界算 `inSampleSize`，再解真正要用的那一档。
     */
    private fun decodeSampled(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val target = (options.size.width as? Dimension.Pixels)?.px ?: THUMB_SIZE
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        var sample = 1
        while (longest / (sample * 2) >= target) sample *= 2
        sampled = sample > 1
        val decode = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decode)
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
