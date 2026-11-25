package com.szc.learning.music.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset

/**
 * ID3 歌词解析的表征测试。
 *
 * 重点是这次改成逐帧读取后的行为：大封面帧必须先跳过、歌词帧照常解析，
 * v2.2 / v2.3 / v2.4 与标签级 unsynchronisation 的结果都不能变。
 */
class Id3LyricsTest {

    // ---------------------------------------------------------------- v2.3

    @Test
    fun `skips a large cover frame before reading USLT`() {
        val cover = ByteArray(200_000) { (it % 251).toByte() }
        val apic = frameV23(
            "APIC",
            byteArrayOf(0) + "image/jpeg".toByteArray(Charsets.ISO_8859_1) +
                byteArrayOf(0, 3, 0) + cover,
        )
        val uslt = frameV23("USLT", usltUtf8("[00:01.00]第一行\n[00:02.00]second"))

        val tag = tagV23(flags = 0, frames = arrayOf(apic, uslt))
        assertEquals("[00:01.00]第一行\n[00:02.00]second", Id3Lyrics.read(tag.inputStream()))
    }

    @Test
    fun `falls back to TXXX LYRICS`() {
        val txxx = frameV23(
            "TXXX",
            byteArrayOf(3) + "LYRICS".toByteArray(Charsets.ISO_8859_1) +
                byteArrayOf(0) + "从 TXXX 来的歌词".toByteArray(Charsets.UTF_8),
        )
        val tag = tagV23(flags = 0, frames = arrayOf(txxx))
        assertEquals("从 TXXX 来的歌词", Id3Lyrics.read(tag.inputStream()))
    }

    @Test
    fun `tag level unsynchronization is restored`() {
        val cover = byteArrayOf(0xFF.toByte(), 0x00, 0x11, 0xFF.toByte(), 0x22)
        val apic = frameV23(
            "APIC",
            byteArrayOf(0) + "image/jpeg".toByteArray(Charsets.ISO_8859_1) +
                byteArrayOf(0, 3, 0) + cover,
        )
        val uslt = frameV23("USLT", usltUtf8("unsync 歌词"))
        val body = apic + uslt

        // 标签级 unsynchronisation：每个 0xFF 后插入一个 0x00
        val unsynced = ByteArrayOutputStream().apply {
            for (byte in body) {
                write(byte.toInt())
                if (byte == 0xFF.toByte()) write(0)
            }
        }.toByteArray()
        val tag = id3Header(major = 3, flags = 0x80, bodySize = unsynced.size) + unsynced

        assertEquals("unsync 歌词", Id3Lyrics.read(tag.inputStream()))
    }

    @Test
    fun `GBK lyrics stored with encoding 0 are decoded`() {
        val text = "中文歌词测试"
        val uslt = frameV23(
            "USLT",
            byteArrayOf(0) + "eng".toByteArray(Charsets.ISO_8859_1) + byteArrayOf(0) +
                text.toByteArray(Charset.forName("GBK")),
        )
        val tag = tagV23(flags = 0, frames = arrayOf(uslt))
        assertEquals(text, Id3Lyrics.read(tag.inputStream()))
    }

    // ---------------------------------------------------------------- v2.4

    @Test
    fun `v24 reads synchsafe frame sizes`() {
        val uslt = frameV24("USLT", usltUtf8("v2.4 歌词"))
        val tag = tagV24(flags = 0, frames = arrayOf(uslt))
        assertEquals("v2.4 歌词", Id3Lyrics.read(tag.inputStream()))
    }

    @Test
    fun `v24 frame level unsynchronization is restored`() {
        val raw = usltUtf8("带 0xFF 的歌词")
        val withMarker = raw + byteArrayOf(0xFF.toByte(), 0x00)
        val encoded = ByteArrayOutputStream().apply {
            for (byte in withMarker) {
                write(byte.toInt())
                if (byte == 0xFF.toByte()) write(0)
            }
        }.toByteArray()
        val uslt = frameV24("USLT", encoded, formatFlags = 0x02)
        val tag = tagV24(flags = 0, frames = arrayOf(uslt))
        // 末尾的 0xFF 在还原后会留下一个非法 UTF-8 字节，只要求正文前缀正确、且没有残留 NUL
        assertEquals(
            true,
            Id3Lyrics.read(tag.inputStream())?.startsWith("带 0xFF 的歌词"),
        )
    }

    // ---------------------------------------------------------------- v2.2

    @Test
    fun `v22 uses three character ids`() {
        val uslt = frameV22("ULT", usltUtf8("v2.2 歌词"))
        val tag = tagV22(frames = arrayOf(uslt))
        assertEquals("v2.2 歌词", Id3Lyrics.read(tag.inputStream()))
    }

    // ---------------------------------------------------------------- 负例

    @Test
    fun `returns null when there is no lyric frame`() {
        val apic = frameV23(
            "APIC",
            byteArrayOf(0) + "image/jpeg".toByteArray(Charsets.ISO_8859_1) +
                byteArrayOf(0, 3, 0) + ByteArray(1_000),
        )
        val tag = tagV23(flags = 0, frames = arrayOf(apic))
        assertNull(Id3Lyrics.read(tag.inputStream()))
    }

    @Test
    fun `returns null for non ID3 input`() {
        assertNull(Id3Lyrics.read("not an mp3".toByteArray().inputStream()))
    }

    // ---------------------------------------------------------------- 构造工具

    private fun usltUtf8(text: String): ByteArray =
        byteArrayOf(3) + "eng".toByteArray(Charsets.ISO_8859_1) + byteArrayOf(0) +
            text.toByteArray(Charsets.UTF_8)

    private fun synchsafe(value: Int): ByteArray = byteArrayOf(
        ((value shr 21) and 0x7F).toByte(),
        ((value shr 14) and 0x7F).toByte(),
        ((value shr 7) and 0x7F).toByte(),
        (value and 0x7F).toByte(),
    )

    private fun frameV23(id: String, data: ByteArray, formatFlags: Int = 0): ByteArray =
        ByteArrayOutputStream().apply {
            write(id.toByteArray(Charsets.ISO_8859_1))
            write(
                byteArrayOf(
                    ((data.size shr 24) and 0xFF).toByte(),
                    ((data.size shr 16) and 0xFF).toByte(),
                    ((data.size shr 8) and 0xFF).toByte(),
                    (data.size and 0xFF).toByte(),
                ),
            )
            write(byteArrayOf(0, formatFlags.toByte()))
            write(data)
        }.toByteArray()

    private fun frameV24(id: String, data: ByteArray, formatFlags: Int = 0): ByteArray =
        ByteArrayOutputStream().apply {
            write(id.toByteArray(Charsets.ISO_8859_1))
            write(synchsafe(data.size))
            write(byteArrayOf(0, formatFlags.toByte()))
            write(data)
        }.toByteArray()

    /** v2.2：3 字节 id + 3 字节长度，没有帧标志。 */
    private fun frameV22(id: String, data: ByteArray): ByteArray =
        ByteArrayOutputStream().apply {
            write(id.toByteArray(Charsets.ISO_8859_1))
            write(
                byteArrayOf(
                    ((data.size shr 16) and 0xFF).toByte(),
                    ((data.size shr 8) and 0xFF).toByte(),
                    (data.size and 0xFF).toByte(),
                ),
            )
            write(data)
        }.toByteArray()

    private fun id3Header(major: Int, flags: Int, bodySize: Int): ByteArray =
        ByteArrayOutputStream().apply {
            write("ID3".toByteArray(Charsets.ISO_8859_1))
            write(byteArrayOf(major.toByte(), 0, flags.toByte()))
            write(synchsafe(bodySize))
        }.toByteArray()

    private fun tagV23(flags: Int, frames: Array<ByteArray>): ByteArray {
        val body = frames.fold(ByteArrayOutputStream()) { acc, frame ->
            acc.apply { write(frame) }
        }.toByteArray()
        return id3Header(3, flags, body.size) + body
    }

    private fun tagV24(flags: Int, frames: Array<ByteArray>): ByteArray {
        val body = frames.fold(ByteArrayOutputStream()) { acc, frame ->
            acc.apply { write(frame) }
        }.toByteArray()
        return id3Header(4, flags, body.size) + body
    }

    private fun tagV22(frames: Array<ByteArray>): ByteArray {
        val body = frames.fold(ByteArrayOutputStream()) { acc, frame ->
            acc.apply { write(frame) }
        }.toByteArray()
        return id3Header(2, 0, body.size) + body
    }
}
