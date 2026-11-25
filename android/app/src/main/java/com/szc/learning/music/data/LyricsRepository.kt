package com.szc.learning.music.data

import android.content.Context
import android.net.Uri
import android.util.LruCache
import com.szc.learning.core.common.music.MusicTrack
import com.szc.learning.core.common.music.TrackSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.charset.Charset

/**
 * 读取音频内嵌歌词。
 *
 * Android 的 MediaStore / MediaMetadataRetriever 都不暴露歌词字段，
 * 歌词藏在 ID3v2 的 `USLT`（非同步歌词）或 `SYLT`（同步歌词）帧里。
 * 这里做一个只认歌词帧的小解析器：不引第三方 tag 库，也不为了封面去解整张图。
 *
 * 本地曲目按需解析并缓存；云端曲目优先用接口下发的 `lyrics` 字段。
 */
class LyricsRepository(private val context: Context) {

    private val cache = object : LruCache<String, String>(24) {
        override fun sizeOf(key: String, value: String): Int = value.length.coerceAtLeast(1)
    }

    suspend fun lyrics(track: MusicTrack): String? = withContext(Dispatchers.IO) {
        track.lyrics?.trim()?.takeIf { it.isNotEmpty() }?.let {
            return@withContext normalize(it)
        }
        if (track.source != TrackSource.LOCAL) return@withContext null

        cache.get(track.key)?.let { cached ->
            return@withContext if (cached == NO_LYRICS) null else cached
        }

        val parsed = runCatching { readEmbeddedLyrics(Uri.parse(track.uri)) }.getOrNull()
        cache.put(track.key, parsed ?: NO_LYRICS)
        parsed
    }

    // ---------------------------------------------------------------- ID3 入口

    private fun readEmbeddedLyrics(uri: Uri): String? {
        val input = context.contentResolver.openInputStream(uri) ?: return null
        return input.use { stream ->
            val header = ByteArray(ID3_HEADER_SIZE)
            if (!stream.readFully(header)) return@use null
            if (header[0] != 'I'.code.toByte() ||
                header[1] != 'D'.code.toByte() ||
                header[2] != '3'.code.toByte()
            ) {
                return@use null
            }

            val major = header[3].toInt() and 0xFF
            if (major !in 2..4) return@use null
            val flags = header[5].toInt() and 0xFF
            val size = synchsafe(header, 6)
            if (size <= 0 || size > MAX_TAG_BYTES) return@use null

            // 标签级 unsynchronisation 把所有字节都改写过，只能整块读出来再还原
            if (flags and 0x80 != 0) {
                val body = ByteArray(size)
                if (!stream.readFully(body)) return@use null
                return@use parseFrames(removeUnsynchronization(body), major, flags)
            }
            // 常见情况：逐帧读帧头，非歌词帧（APIC 封面等）直接 skip，
            // 不再为了一首歌词把整张标签（含几 MB 封面）读进内存
            parseFramesStreaming(stream, size, major, flags)
        }
    }

    /**
     * 逐帧读 ID3v2 标签。只把歌词帧的数据读进内存，其余帧跳过。
     *
     * 与 [parseFrames] 的判定逻辑逐项一致，区别只是数据来源从整块数组换成流。
     */
    private fun parseFramesStreaming(
        stream: InputStream,
        tagSize: Int,
        major: Int,
        tagFlags: Int,
    ): String? {
        val idLength = if (major == 2) 3 else 4
        val frameHeader = if (major == 2) 6 else 10
        var remaining = tagSize
        var syncedLyrics: String? = null

        // 扩展头不包含歌词；跳过，否则第一个 frame id 会是垃圾
        if (tagFlags and 0x40 != 0) {
            val ext = ByteArray(4)
            if (remaining < 4 || !stream.readFully(ext)) return null
            remaining -= 4
            val total = when (major) {
                3 -> 4 + readInt32(ext, 0)
                4 -> synchsafe(ext, 0, 4)
                else -> 0
            }
            val extra = (total - 4).coerceIn(0, remaining)
            if (extra > 0 && !skipFully(stream, extra)) return null
            remaining -= extra
        }

        while (remaining >= frameHeader) {
            val header = ByteArray(frameHeader)
            if (!stream.readFully(header)) break
            remaining -= frameHeader

            val id = String(header, 0, idLength, Charsets.ISO_8859_1)
            if (id[0] == '\u0000') break

            val frameSize = when (major) {
                2 -> readUInt24(header, 3)
                3 -> readInt32(header, 4)
                else -> synchsafe(header, 4, 4)
            }
            if (frameSize <= 0 || frameSize > remaining) break

            if (id in LYRIC_FRAME_IDS) {
                val data = ByteArray(frameSize)
                if (!stream.readFully(data)) break
                remaining -= frameSize
                val prepared = prepareFrameData(data, major, header) ?: continue
                when (id) {
                    "USLT", "ULT" -> parseUslt(prepared)?.let { return it }
                    "SYLT", "SLT" -> syncedLyrics = parseSylt(prepared) ?: syncedLyrics
                    "TXXX" -> parseTxxx(prepared)?.let { return it }
                }
            } else {
                if (!skipFully(stream, frameSize)) break
                remaining -= frameSize
            }
        }
        return syncedLyrics
    }

    private fun parseFrames(tag: ByteArray, major: Int, tagFlags: Int): String? {
        val idLength = if (major == 2) 3 else 4
        val frameHeader = if (major == 2) 6 else 10
        val sizeLength = if (major == 2) 3 else 4

        var offset = extendedHeaderSize(tag, major, tagFlags)
        var syncedLyrics: String? = null

        while (offset + frameHeader <= tag.size) {
            val id = String(tag, offset, idLength, Charsets.ISO_8859_1)
            if (id[0] == '\u0000') break

            val frameSize = when {
                major == 2 -> readUInt24(tag, offset + 3)
                major == 3 -> readInt32(tag, offset + 4)
                else -> synchsafe(tag, offset + 4, 4)
            }
            val dataStart = offset + frameHeader
            if (frameSize <= 0 || dataStart + frameSize > tag.size) break

            val raw = tag.copyOfRange(dataStart, dataStart + frameSize)
            val data = prepareFrameData(raw, major, tag, offset)
            if (data == null) {
                offset = dataStart + frameSize
                continue
            }

            when (id) {
                "USLT", "ULT" -> parseUslt(data)?.let { return it }
                "SYLT", "SLT" -> syncedLyrics = parseSylt(data) ?: syncedLyrics
                "TXXX" -> parseTxxx(data)?.let { return it }
            }
            offset = dataStart + frameSize
        }

        return syncedLyrics
    }

    /**
     * 处理帧格式标志（压缩 / 加密 / unsynchronisation / data length indicator）。
     * 返回 null 表示这一帧不可解析，调用方跳过。
     */
    private fun prepareFrameData(
        data: ByteArray,
        major: Int,
        header: ByteArray,
        headerOffset: Int = 0,
    ): ByteArray? {
        if (major == 2) return data
        val formatFlags = header[headerOffset + 9].toInt() and 0xFF
        var out = data
        if (major == 3) {
            if (formatFlags and 0x80 != 0 || formatFlags and 0x40 != 0) return null
            if (formatFlags and 0x20 != 0 && out.isNotEmpty()) {
                out = out.copyOfRange(1, out.size)
            }
        } else {
            if (formatFlags and 0x08 != 0 || formatFlags and 0x04 != 0) return null
            if (formatFlags and 0x02 != 0) out = removeUnsynchronization(out)
            if (formatFlags and 0x01 != 0 && out.size >= 4) {
                out = out.copyOfRange(4, out.size)
            }
        }
        return out
    }

    /** 扩展头不包含歌词；跳过，否则第一个 frame id 会是垃圾。 */
    private fun extendedHeaderSize(tag: ByteArray, major: Int, flags: Int): Int {
        if (flags and 0x40 == 0) return 0
        if (major == 3 && tag.size >= 4) {
            val size = readInt32(tag, 0)
            return (4 + size).coerceIn(0, tag.size)
        }
        if (major == 4 && tag.size >= 4) {
            return synchsafe(tag, 0, 4).coerceIn(0, tag.size)
        }
        return 0
    }

    // ---------------------------------------------------------------- 帧解析

    private fun parseUslt(data: ByteArray): String? {
        if (data.size < 5) return null
        val encoding = data[0].toInt() and 0x03
        val textStart = skipTerminator(data, 4, encoding)
        if (textStart >= data.size) return null
        return decode(data, textStart, data.size, encoding)
            .trim()
            .takeIf { it.isNotEmpty() }
    }

    /**
     * SYLT 是「文本 + 4 字节时间戳（毫秒）」重复。
     * 直接拼成 LRC 文本交给 UI，逐行高亮和滚动就能直接复用 LRC 那套逻辑。
     */
    private fun parseSylt(data: ByteArray): String? {
        if (data.size < 7) return null
        val encoding = data[0].toInt() and 0x03
        var cursor = skipTerminator(data, 6, encoding)
        val lines = ArrayList<String>()
        while (cursor < data.size) {
            val textEnd = skipTerminator(data, cursor, encoding)
            if (textEnd <= cursor || textEnd + 4 > data.size) break
            val line = decode(data, cursor, textEnd - terminatorLength(encoding), encoding)
            val timeMs = readInt32(data, textEnd).coerceAtLeast(0)
            if (line.isNotBlank()) {
                val minutes = timeMs / 60_000
                val seconds = (timeMs / 1_000) % 60
                val centis = (timeMs % 1_000) / 10
                lines += "[%02d:%02d.%02d]%s".format(minutes, seconds, centis, line.trim())
            }
            cursor = textEnd + 4
        }
        return lines.joinToString("\n").takeIf { it.isNotBlank() }
    }

    /** 某些打标工具把歌词塞进 TXXX:LYRICS，而不是 USLT。 */
    private fun parseTxxx(data: ByteArray): String? {
        if (data.size < 2) return null
        val encoding = data[0].toInt() and 0x03
        val descEnd = skipTerminator(data, 1, encoding)
        if (descEnd >= data.size) return null
        val description = decode(data, 1, descEnd - terminatorLength(encoding), encoding)
            .trim()
            .uppercase()
        if (description !in setOf("LYRICS", "LYRIC", "UNSYNCEDLYRICS", "UNSYNCED LYRICS")) return null
        return decode(data, descEnd, data.size, encoding)
            .trim()
            .takeIf { it.isNotEmpty() }
    }

    // ---------------------------------------------------------------- 字节/文本

    private fun decode(data: ByteArray, start: Int, end: Int, encoding: Int): String {
        if (end <= start) return ""
        val charset = when (encoding) {
            1 -> Charsets.UTF_16
            2 -> Charsets.UTF_16BE
            3 -> Charsets.UTF_8
            else -> Charsets.ISO_8859_1
        }
        val raw = String(data, start, end - start, charset)

        // 不少中文打标工具把 GBK 歌词写在 encoding=0（规范应为 ISO-8859-1）里。
        // 纯 Latin-1 解出来不会有汉字，这时用 GBK 再解一次，汉字更多的那个胜出。
        if (encoding == 0) {
            val gbk = runCatching {
                String(data, start, end - start, Charset.forName("GBK"))
            }.getOrNull()
            if (gbk != null && cjkCount(gbk) > cjkCount(raw)) return normalize(gbk)
        }
        return normalize(raw)
    }

    private fun cjkCount(text: String): Int =
        text.count { it in '\u4E00'..'\u9FFF' }

    private fun normalize(text: String): String = text
        .replace("\u0000", "")
        .replace("\r\n", "\n")
        .replace('\r', '\n')
        .trim()

    private fun skipTerminator(data: ByteArray, start: Int, encoding: Int): Int {
        if (encoding == 1 || encoding == 2) {
            var index = start
            while (index + 1 < data.size) {
                if (data[index] == 0.toByte() && data[index + 1] == 0.toByte()) {
                    return index + 2
                }
                index += 2
            }
            return data.size
        }
        for (index in start until data.size) {
            if (data[index] == 0.toByte()) return index + 1
        }
        return data.size
    }

    private fun terminatorLength(encoding: Int): Int =
        if (encoding == 1 || encoding == 2) 2 else 1

    private fun removeUnsynchronization(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(bytes.size)
        var index = 0
        while (index < bytes.size) {
            val value = bytes[index]
            out.write(value.toInt())
            if (value == 0xFF.toByte() && index + 1 < bytes.size && bytes[index + 1] == 0.toByte()) {
                index += 2
            } else {
                index++
            }
        }
        return out.toByteArray()
    }

    private fun synchsafe(bytes: ByteArray, offset: Int, length: Int = 4): Int {
        var value = 0
        for (index in 0 until length) {
            value = (value shl 7) or (bytes[offset + index].toInt() and 0x7F)
        }
        return value
    }

    private fun readUInt24(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            (bytes[offset + 2].toInt() and 0xFF)

    private fun readInt32(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)

    private fun InputStream.readFully(buffer: ByteArray): Boolean {
        var offset = 0
        while (offset < buffer.size) {
            val read = read(buffer, offset, buffer.size - offset)
            if (read < 0) return false
            if (read == 0) continue
            offset += read
        }
        return true
    }

    /** 跳过 [count] 字节。`skip` 可能返回 0，此时读一个字节推进。 */
    private fun skipFully(stream: InputStream, count: Int): Boolean {
        var remaining = count.toLong()
        while (remaining > 0) {
            val skipped = stream.skip(remaining)
            if (skipped > 0) {
                remaining -= skipped
            } else {
                if (stream.read() < 0) return false
                remaining--
            }
        }
        return true
    }

    private companion object {
        const val ID3_HEADER_SIZE = 10
        const val MAX_TAG_BYTES = 8 * 1024 * 1024

        /** 需要真正读进内存的帧（歌词 / 歌词文本）。其余帧直接 skip。 */
        val LYRIC_FRAME_IDS = setOf("USLT", "ULT", "SYLT", "SLT", "TXXX")

        /** 用一个不可能出现在正常歌词里的哨兵表示"解析过，但没有歌词"。 */
        const val NO_LYRICS = "\u0000"
    }
}