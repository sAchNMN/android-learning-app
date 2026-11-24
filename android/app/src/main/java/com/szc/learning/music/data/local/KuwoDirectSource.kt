package com.szc.learning.music.data.local

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.szc.learning.core.common.music.MusicTrack
import com.szc.learning.core.common.music.TrackSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.UnsupportedEncodingException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * 酷我音源，**客户端直连**版本。
 *
 * 存在的意义只有一个：后端关掉时 App 还能搜歌、还能播。
 * 这是四个平台里最适合搬到客户端的一个 —— 两条公开接口都不需要签名：
 *
 * - 搜索 `search.kuwo.cn/r.s`（带 `mobi=1&vipver=1` 时返回**标准 JSON**）
 * - 取址 `antiserver.kuwo.cn/anti.s?response=url`（只回一行 CDN 地址）
 *
 * 与后端 `KuwoSourceProvider` 的三处刻意偏离，都是踩过或权衡过的：
 *
 * 1. **全部走 HTTPS**。后端用 HTTP 没问题，但 App 的 `network_security_config`
 *    release 版全局禁明文，`http://search.kuwo.cn` 会被系统直接拦掉。
 *    实测三个域名都支持 HTTPS。
 * 2. **不拿 `FORMATS` 字段判断可播性**。实测该字段在 `mobi=1` 的返回里根本不存在，
 *    照搬后端逻辑会把整页结果都标成不可播 —— 而 `MusicViewModel.claim()` 会用
 *    `playable` 过滤刷歌流，等于把酷我整个平台从刷歌里剔除。改成乐观 `true`，
 *    真实可播性以取址结果为准（取不到会走 `vip_required` 提示）。
 * 3. **歌词失败一律返回空串**。该接口对同一出口 IP 有频控，手测就已稳定 301，
 *    是平台侧策略不是 bug。没歌词不影响播放，UI 显示「暂无歌词」即可。
 */
class KuwoDirectSource(
    private val client: OkHttpClient = defaultClient(),
) {

    /**
     * 搜索。失败返回空列表 —— 调用方要靠「空」来判断走降级，不该收到异常。
     *
     * @param cookie 用户在音源管理页填的酷我 Cookie，可空（匿名也能搜）
     */
    suspend fun search(keyword: String, limit: Int = 20, cookie: String = ""): List<MusicTrack> =
        withContext(Dispatchers.IO) {
            val size = limit.coerceIn(1, MAX_PAGE_SIZE)
            val url = buildString {
                append(SEARCH_URL)
                append("?all=").append(encode(keyword))
                append("&ft=music&itemset=web_2013&client=kt&pn=0&rn=").append(size)
                append("&rformat=json&encoding=utf8&mobi=1&vipver=1")
            }
            val raw = getText(url, REFERER_WEB, cookie) ?: return@withContext emptyList()
            val root = parseFlexible(raw) ?: return@withContext emptyList()
            val list = root.arrayOrNull("abslist") ?: return@withContext emptyList()

            val tracks = ArrayList<MusicTrack>(list.size())
            for (element in list) {
                if (tracks.size >= size) break
                val item = element.takeIf { it.isJsonObject }?.asJsonObject ?: continue
                val rid = normalizeRid(item.str("MUSICRID", "DC_TARGETID"))
                val title = clean(item.str("SONGNAME", "NAME"))
                if (rid.isBlank() || title.isBlank()) continue
                tracks += MusicTrack(
                    id = rid,
                    title = title,
                    artist = clean(item.str("ARTIST")),
                    album = clean(item.str("ALBUM")),
                    // DURATION 单位是秒，个别曲目会返回浮点串
                    durationMs = (item.long("DURATION") ?: 0L).coerceAtLeast(0L) * 1000L,
                    uri = "",
                    artUri = coverOf(item.str("web_albumpic_short", "web_artistpic_short", "ARTISTPIC")),
                    source = TrackSource.ONLINE,
                    sourceKey = KEY,
                    hash = rid,
                    albumId = item.str("ALBUMID"),
                    playable = true,
                )
            }
            tracks
        }

    /**
     * 换取播放地址。返回 null 表示这首取不到（会员曲 / 已下架 / 网络失败），
     * 调用方据此提示「可能需要会员」，不要当成异常。
     */
    suspend fun resolve(track: MusicTrack, cookie: String = ""): String? =
        withContext(Dispatchers.IO) {
            val rid = ridOf(track)
            if (rid.isBlank()) return@withContext null
            // 优先 mp3：ExoPlayer 对 aac 容器偶尔要额外解析，mp3 兼容性最好
            for (format in PREFERRED_FORMATS) {
                val url = "$PLAY_URL?type=convert_url&rid=${encode(rid)}&format=$format&response=url"
                val body = getText(url, REFERER_WEB, cookie)?.trim().orEmpty()
                if (isPlayableUrl(body)) return@withContext body
            }
            null
        }

    /**
     * LRC 文本。拿不到返回空串，不重试（接口频控，重试只会更糟）。
     *
     * **不能用 `getAsJsonObject("data")` 判空**：Gson 遇到 `"data": null` 时
     * 不会返回 null，而是抛 `ClassCastException: JsonNull cannot be cast to JsonObject`。
     * 酷我的歌词接口在限流时正好返回 `{"data":null,"status":301}` —— 这曾让
     * 一进播放页就崩，因为歌词是在页面渲染时自动拉的。必须先判 `isJsonObject`。
     */
    suspend fun lyric(track: MusicTrack, cookie: String = ""): String =
        withContext(Dispatchers.IO) {
            val musicId = ridOf(track).replace(NON_DIGIT, "")
            if (musicId.isBlank()) return@withContext ""
            val url = "$LYRIC_URL?musicId=${encode(musicId)}"
            val raw = getText(url, REFERER_MOBILE, cookie, ua = MOBILE_UA)
                ?: return@withContext ""
            val root = runCatching { JsonParser.parseString(raw).asJsonObject }.getOrNull()
                ?: return@withContext ""
            val data = root.objectOrNull("data") ?: return@withContext ""
            val lines = data.arrayOrNull("lrclist") ?: return@withContext ""
            buildString {
                for (element in lines) {
                    val line = element.takeIf { it.isJsonObject }?.asJsonObject ?: continue
                    val text = line.str("lineLyric").trim()
                    val time = line.str("time").trim()
                    // 没有时间戳的行在 LRC 里是废行，解析器会丢掉，这里直接不写
                    if (time.isBlank()) continue
                    append('[').append(formatTime(time)).append(']').append(text).append('\n')
                }
            }.trim()
        }

    // ---------------------------------------------------------------- 请求

    private fun getText(
        url: String,
        referer: String,
        cookie: String,
        ua: String = DESKTOP_UA,
    ): String? {
        val builder = Request.Builder()
            .url(url)
            .get()
            .header("User-Agent", ua)
            .header("Referer", referer)
            .header("Accept", "application/json, text/plain, */*")
        if (cookie.isNotBlank()) {
            builder.header("Cookie", cookie.trim())
        }
        return runCatching {
            client.newCall(builder.build()).execute().use { response ->
                // 平台限流时会给 403/301，取不到就按失败处理，不要拿错误页当结果
                if (!response.isSuccessful) null else response.body?.string()
            }
        }.getOrNull()
    }

    // ---------------------------------------------------------------- 解析

    /**
     * 酷我两种返回格式都可能出现，取决于参数：
     * 带 `mobi=1&vipver=1` 是标准 JSON，不带则是 JS 对象字面量（单引号 + 裸 key）。
     *
     * **必须先试标准 JSON 再降级**。反过来的后果：状态机把歌名里的单引号
     * （如 Don't）当成字符串起始符，整段结构错位，报一个看不出所以然的语法错。
     */
    private fun parseFlexible(raw: String): JsonObject? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        if (text.startsWith("{")) {
            val direct = runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull()
            if (direct != null) return direct
        }
        val converted = jsToJson(text)
        return runCatching { JsonParser.parseString(converted).asJsonObject }.getOrNull()
    }

    private fun JsonObject.str(vararg keys: String): String {
        for (key in keys) {
            val element = get(key) ?: continue
            if (element.isJsonNull) continue
            val value = runCatching { element.asString }.getOrNull() ?: continue
            if (value.isNotBlank()) return value
        }
        return ""
    }

    private fun JsonObject.long(key: String): Long? {
        val element = get(key) ?: return null
        if (element.isJsonNull) return null
        return runCatching { element.asLong }.getOrNull()
    }

    companion object {

        const val KEY = "kuwo"
        const val LABEL = "酷我音乐"

        private const val SEARCH_URL = "https://search.kuwo.cn/r.s"
        private const val PLAY_URL = "https://antiserver.kuwo.cn/anti.s"
        private const val LYRIC_URL = "https://m.kuwo.cn/newh5/singles/songinfoandlrc"
        private const val COVER_PREFIX = "https://img4.kuwo.cn/star/albumcover/"
        private const val REFERER_WEB = "https://www.kuwo.cn/"
        private const val REFERER_MOBILE = "https://m.kuwo.cn/"
        private const val MAX_PAGE_SIZE = 30

        private val PREFERRED_FORMATS = listOf("mp3", "aac")
        private val NON_DIGIT = Regex("[^0-9]")

        /** 后端强制桌面 Chrome UA：移动端 UA 会拿到另一套字段名。 */
        private const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        /**
         * 歌词接口只认这一种 UA。桌面 Chrome 和安卓 Chrome 都稳定返回
         * `{"data":null,"status":301,"msg":"音乐查询失败"}`。
         */
        private const val MOBILE_UA =
            "Mozilla/5.0 (iPhone; CPU iPhone OS 16_0 like Mac OS X) AppleWebKit/605.1.15 " +
                "(KHTML, like Gecko) Version/16.0 Mobile/15E148 Safari/604.1"

        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()

        private fun encode(value: String): String = try {
            URLEncoder.encode(value, "UTF-8")
        } catch (ex: UnsupportedEncodingException) {
            value
        }

        /** 酷我 rid 形如 `MUSIC_228908`，接口既能吃完整形式也能吃纯数字，统一存完整形式。 */
        fun normalizeRid(raw: String): String {
            if (raw.isBlank()) return ""
            if (raw.startsWith("MUSIC_")) return raw
            val digits = raw.replace(NON_DIGIT, "")
            return if (digits.isBlank()) "" else "MUSIC_$digits"
        }

        private fun ridOf(track: MusicTrack): String =
            normalizeRid(track.hash.ifBlank { track.id })

        /** anti.s 成功时只回一行 http(s) 地址；失败会回 "no copyright" / 空串 / 一层 HTML。 */
        private fun isPlayableUrl(body: String): Boolean =
            body.startsWith("http") && !body.contains("<") && body.length > 20

        /** 酷我的 time 是秒（浮点），LRC 要 mm:ss.xx。 */
        private fun formatTime(seconds: String): String {
            val value = seconds.toDoubleOrNull() ?: return "00:00.00"
            val total = (value * 100).toLong()
            return "%02d:%02d.%02d".format(total / 6000, (total % 6000) / 100, total % 100)
        }

        private fun coverOf(shortPath: String): String? {
            if (shortPath.isBlank()) return null
            val path = shortPath.trim()
            return if (path.startsWith("http")) path else COVER_PREFIX + path.trimStart('/')
        }

        /** 酷我用 &nbsp; 占位空格，还夹 HTML 实体，个别字段带字面的 `\u0026`。 */
        private fun clean(raw: String): String {
            if (raw.isBlank()) return ""
            return raw
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("\\u0026", "&")
                .replace(WHITESPACE, " ")
                .trim()
        }

        private val WHITESPACE = Regex("\\s+")

        /**
         * JS 对象字面量 → JSON。`{'TOTAL':'10','abslist':[{'NAME':'晴天'}]}`
         * 字符串用单引号、key 不加引号，标准解析器直接报错。
         *
         * 必须用状态机而不是正则替换。踩过的坑：直接给 `{` `,` 后面的裸 key 补引号，
         * 会把字符串**内部**的内容也改掉 —— 酷我的 MINFO 字段长这样
         * `'level:p,bitrate:320,format:mp3'`，里头的 `,bitrate:` 被补成 `,"bitrate":`
         * 后引号就错配了。
         */
        private fun jsToJson(raw: String): String {
            if (raw.isBlank()) return "{}"
            val out = StringBuilder(raw.length + 64)
            var expectKey = false
            var i = 0
            while (i < raw.length) {
                val c = raw[i]
                if (c == '\'') {
                    out.append('"')
                    i++
                    while (i < raw.length) {
                        val d = raw[i]
                        if (d == '\\' && i + 1 < raw.length) {
                            val next = raw[i + 1]
                            if (next == '\'') out.append('\'') else out.append(d).append(next)
                            i += 2
                            continue
                        }
                        if (d == '\'') {
                            out.append('"')
                            i++
                            break
                        }
                        // 字符串内的双引号必须转义，否则拼出来的 JSON 会提前闭合
                        if (d == '"') out.append("\\\"") else out.append(d)
                        i++
                    }
                    expectKey = false
                    continue
                }
                if (c == '{' || c == ',') {
                    out.append(c)
                    expectKey = true
                    i++
                    continue
                }
                if (expectKey) {
                    if (c.isWhitespace()) {
                        out.append(c)
                        i++
                        continue
                    }
                    if (c.isLetter() || c == '_' || c == '$') {
                        val start = i
                        while (i < raw.length) {
                            val d = raw[i]
                            if (!(d.isLetterOrDigit() || d == '_' || d == '$')) break
                            i++
                        }
                        val ident = raw.substring(start, i)
                        var look = i
                        while (look < raw.length && raw[look].isWhitespace()) look++
                        if (look < raw.length && raw[look] == ':') {
                            out.append('"').append(ident).append('"')
                            i = look
                        } else {
                            out.append(ident)
                        }
                        expectKey = false
                        continue
                    }
                    expectKey = false
                }
                out.append(c)
                i++
            }
            return out.toString()
        }
    }
}

/**
 * 安全取子对象 / 子数组。
 *
 * Gson 的 `getAsJsonObject(key)` / `getAsJsonArray(key)` 在字段存在但值为 `null` 时
 * **抛 ClassCastException 而不是返回 null**（JsonNull 不能强转）。平台接口在限流、
 * 下架、参数不对时经常就是这么返回的，所以取值一律走这两个函数。
 */
private fun JsonObject.objectOrNull(key: String): JsonObject? =
    get(key)?.takeIf { it.isJsonObject }?.asJsonObject

private fun JsonObject.arrayOrNull(key: String): JsonArray? =
    get(key)?.takeIf { it.isJsonArray }?.asJsonArray
