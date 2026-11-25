package com.szc.learning.music.data

/** 一行歌词；[timeMs] 为 -1 表示没有时间码，只能静态展示。 */
data class LyricLine(val timeMs: Long, val text: String)

/**
 * 解析后的歌词。
 *
 * [timed] 为 false 时只有纯文本（很多本地 USLT 就是这样），
 * 控件只做静态展示，不跟随播放进度高亮。
 */
data class LyricSheet(
    val lines: List<LyricLine>,
    val timed: Boolean,
) {
    val isEmpty: Boolean get() = lines.isEmpty()

    companion object {
        val EMPTY = LyricSheet(emptyList(), timed = false)
    }
}

/**
 * LRC 歌词解析。
 *
 * 在线音源的歌词接口普遍直接返回 LRC；本地 SYLT 由 [LyricsRepository]
 * 转成同样的 `[mm:ss.xx]` 文本。两种来源在这里汇合，UI 不用关心出处。
 *
 * 支持：
 * - 一行多个时间码 `[00:12.00][01:20.00]副歌`
 * - 毫秒位数 1~3 位、`[mm:ss]` 不带毫秒
 * - `[offset:500]` 全局偏移
 * - `[ti:]` / `[ar:]` / `[al:]` 等元数据行自动跳过
 */
object LrcParser {

    private val timeTag = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")
    private val offsetTag = Regex("""\[offset:\s*([+-]?\d+)]""", RegexOption.IGNORE_CASE)
    private val metaTag = Regex("""^\[[a-zA-Z]+:.*]$""")

    /**
     * 解析 LRC。
     *
     * [userOffsetMs] 是用户手动校准的偏移，正数=歌词提前。和文件自带的
     * `[offset:xx]` 叠加：后者是制作者写进文件的修正，前者是「这首在我这台上差半秒」
     * 的本地补救 —— 两种偏移来源不同，不能互相覆盖。
     */
    fun parse(raw: String?, userOffsetMs: Long = 0L): LyricSheet {
        val text = raw
            ?.replace("\r\n", "\n")
            ?.replace('\r', '\n')
            ?.trim()
            .orEmpty()
        if (text.isEmpty()) return LyricSheet.EMPTY

        val fileOffsetMs = offsetTag.find(text)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        val offsetMs = fileOffsetMs + userOffsetMs
        val timed = ArrayList<LyricLine>()
        val plain = ArrayList<LyricLine>()

        text.lineSequence().forEach { rawLine ->
            val stamps = timeTag.findAll(rawLine).toList()
            val content = rawLine.replace(timeTag, "").trim()
            if (content.isEmpty()) return@forEach

            if (stamps.isEmpty()) {
                if (!metaTag.matches(rawLine.trim())) plain += LyricLine(-1L, content)
                return@forEach
            }
            stamps.forEach { stamp ->
                val minutes = stamp.groupValues[1].toLongOrNull() ?: return@forEach
                val seconds = stamp.groupValues[2].toLongOrNull() ?: return@forEach
                val time = minutes * 60_000 + seconds * 1_000 +
                    fractionMs(stamp.groupValues[3]) + offsetMs
                timed += LyricLine(time.coerceAtLeast(0L), content)
            }
        }

        if (timed.isNotEmpty()) {
            timed.sortBy { it.timeMs }
            return LyricSheet(timed, timed = true)
        }
        return LyricSheet(plain, timed = false)
    }

    /** `[00:01.5]` = 500ms、`[00:01.05]` = 50ms、`[00:01.005]` = 5ms。 */
    private fun fractionMs(raw: String): Long {
        if (raw.isEmpty()) return 0L
        val value = raw.take(3).toLongOrNull() ?: return 0L
        return when (raw.length) {
            1 -> value * 100
            2 -> value * 10
            else -> value
        }
    }
}
