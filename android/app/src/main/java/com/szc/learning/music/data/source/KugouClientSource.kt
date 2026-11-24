package com.szc.learning.music.data.source

import com.google.gson.JsonObject
import com.szc.learning.core.common.music.MusicTrack
import com.szc.learning.core.common.music.TrackSource
import com.szc.learning.core.network.music.OnlinePlayback
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Base64
import java.util.TreeMap
import java.util.UUID

/**
 * 酷狗音源，**客户端直连**。
 *
 * 三段接口，来自后端 `KugouSourceProvider` 的同一套参数：
 *
 * - 搜索  `songsearch.kugou.com/song_search_v2`（appid=1014，不签名）
 * - 取址  `m.kugou.com/app/i/getSongInfo.php`（key = md5(hash + "kgcloud")）
 *          失败退到网页版 `wwwapi.kugou.com/play/songinfo`，参数走 H5 签名
 * - 歌词  `krcs.kugou.com` 的 search + download（返回 Base64 的 LRC）
 *
 * 签名盐和 appid 是平台前端的公开常量，不是凭证。真正的用户态只有音源管理里填的 Cookie。
 *
 * 移动端取址优先：它只要一个 md5，命中率高；网页版带时间戳和排序签名，
 * 失败时基本是「这首歌要会员」，留作兜底。
 */
internal class KugouClientSource : ClientSourceProvider {

    override val key: String = KEY

    /**
     * 本机固定 mid。
     *
     * 每次搜索都换 mid 会被平台当成新设备，轻则结果排序漂移，重则直接限流。
     * 这里在进程内生成一次，用户 Cookie 里带了 `kg_mid` 就优先用用户的。
     */
    private val processMid: String = SourceHttp.md5("aurora-android-${UUID.randomUUID()}")

    // ---------------------------------------------------------------- 搜索

    override suspend fun search(keyword: String, limit: Int, cookie: String): List<MusicTrack> =
        withContext(Dispatchers.IO) {
            val size = limit.coerceIn(1, 20)
            val auth = auth(cookie)
            val url = "$SEARCH_URL?keyword=${SourceHttp.encode(keyword)}" +
                "&page=1&pagesize=$size" +
                "&userid=${auth.userId.ifBlank { "-1" }}" +
                "&clientver=2000&platform=WebFilter&tag=em&filter=2&iscorrection=1" +
                "&privilege_filter=0&filter_ver=2&appid=$APPID" +
                "&token=${SourceHttp.encode(auth.token)}&mid=${SourceHttp.encode(auth.mid)}"
            val json = SourceHttp.getJson(url, referer = REFERER_WEB, cookie = requestCookie(cookie))
                ?: return@withContext emptyList()
            if (json.intValue("status") != 1) return@withContext emptyList()
            val lists = json.objectOrNull("data")?.arrayOrNull("lists")
                ?: return@withContext emptyList()

            val tracks = ArrayList<MusicTrack>(lists.size())
            for (element in lists) {
                if (tracks.size >= size) break
                val item = element.takeIf { it.isJsonObject }?.asJsonObject ?: continue
                val hash = item.str("FileHash", "fileHash", "hash")
                val title = SourceHttp.stripTags(item.str("SongName", "songname", "FileName"))
                if (hash.isBlank() || title.isBlank()) continue
                tracks += MusicTrack(
                    id = hash,
                    title = title,
                    artist = SourceHttp.stripTags(item.str("SingerName", "singername")),
                    album = SourceHttp.stripTags(item.str("AlbumName", "albumname")),
                    // Duration 是秒
                    durationMs = item.longValue("Duration").coerceAtLeast(0L) * 1000L,
                    uri = "",
                    artUri = cover(item.str("Image", "AlbumImage", "img")),
                    source = TrackSource.ONLINE,
                    sourceKey = KEY,
                    hash = hash,
                    albumId = item.str("AlbumID", "album_id"),
                    albumAudioId = item.str("MixSongID", "mixsongid", "EMixSongID", "AlbumAudioID"),
                    playable = item.intValue("Privilege") <= 8,
                )
            }
            tracks
        }

    // ---------------------------------------------------------------- 取址

    override suspend fun resolve(track: MusicTrack, cookie: String): OnlinePlayback =
        withContext(Dispatchers.IO) {
            val hash = track.hash.ifBlank { track.id }
            if (hash.isBlank()) return@withContext fail("缺少酷狗 FileHash")
            val auth = auth(cookie)
            val albumId = track.albumId.ifBlank { "0" }

            // 1.1 移动端：只要 md5(hash + "kgcloud")
            playViaMobile(track, hash, albumId, auth, cookie).let { mobile ->
                if (mobile.track != null) return@withContext mobile
                // 1.2 网页版：带时间戳和排序签名，会员曲目走这条路更容易拿到试听
                val web = playViaWeb(track, hash, albumId, auth, cookie)
                if (web.track != null) return@withContext web
                // 1.3 两个都失败：优先保留「要登录 / 要会员」这类可行动的原因
                val mobileMessage = mobile.message
                if (mobileMessage != null && !mobileMessage.contains("没有返回")) mobile else web
            }
        }

    private fun playViaMobile(
        track: MusicTrack,
        hash: String,
        albumId: String,
        auth: Auth,
        cookie: String,
    ): OnlinePlayback {
        val url = buildString {
            append(PLAY_MOBILE)
            append("?cmd=playInfo&hash=").append(SourceHttp.encode(hash))
            append("&key=").append(SourceHttp.md5(hash + KUGOU_KEY_SALT))
            append("&album_id=").append(SourceHttp.encode(albumId))
            append("&pid=1&forceDown=0")
            append("&vip=").append(if (auth.token.isBlank()) "65530" else "1")
            if (auth.userId.isNotBlank()) append("&userid=").append(SourceHttp.encode(auth.userId))
            if (auth.token.isNotBlank()) append("&token=").append(SourceHttp.encode(auth.token))
        }
        val json = SourceHttp.getJson(url, referer = REFERER_MOBILE, cookie = requestCookie(cookie))
            ?: return fail("酷狗取址请求失败")
        val playUrl = pickUrl(json)
        if (json.intValue("status") == 1 && playUrl.isNotBlank()) {
            return OnlinePlayback(track = track.copy(uri = SourceHttp.secure(playUrl)))
        }
        return failure(json)
    }

    private fun playViaWeb(
        track: MusicTrack,
        hash: String,
        albumId: String,
        auth: Auth,
        cookie: String,
    ): OnlinePlayback {
        // TreeMap 是有序的：签名要求参数按 key 字典序拼接，LinkedHashMap 不行
        val params = TreeMap<String, String>()
        params["srcappid"] = H5_SRC_APPID
        params["clientver"] = H5_CLIENTVER
        params["clienttime"] = System.currentTimeMillis().toString()
        params["mid"] = auth.mid
        params["uuid"] = auth.mid
        params["dfid"] = auth.dfid
        params["appid"] = APPID
        params["token"] = auth.token
        params["userid"] = auth.userId.ifBlank { "0" }
        params["platid"] = "4"
        params["hash"] = hash
        params["album_id"] = albumId
        if (track.albumAudioId.isNotBlank()) params["album_audio_id"] = track.albumAudioId
        params["signature"] = h5Signature(params)

        val url = buildString {
            append(PLAY_WEB).append('?')
            params.entries.forEachIndexed { index, entry ->
                if (index > 0) append('&')
                append(entry.key).append('=').append(SourceHttp.encode(entry.value))
            }
        }
        val json = SourceHttp.getJson(url, referer = REFERER_WEB, cookie = requestCookie(cookie))
            ?: return fail("酷狗网页取址请求失败")
        val playUrl = pickUrl(json)
        if (json.intValue("status") == 1 && playUrl.isNotBlank()) {
            return OnlinePlayback(track = track.copy(uri = SourceHttp.secure(playUrl)))
        }
        return failure(json)
    }

    private fun failure(json: JsonObject): OnlinePlayback {
        val code = json.intValue("err_code", "error_code")
        val message = json.str("error", "errmsg", "msg", "message")
        return when {
            code == 30020 -> fail("酷狗需要安全验证，请更新音源 Cookie")
            code == 30022 -> fail("该酷狗歌曲仅支持官方客户端播放")
            message.contains("登录") || message.contains("token", true) ||
                message.contains("会话") || message.contains("凭证") ->
                fail("酷狗登录态失效，请在音源管理里更新 Cookie")
            message.contains("付费") || message.contains("会员") || message.contains("vip", true) ->
                fail("酷狗这首歌需要会员或购买权限")
            else -> fail("酷狗没有返回播放地址")
        }
    }

    // ---------------------------------------------------------------- 歌词

    override suspend fun lyric(track: MusicTrack, cookie: String): String =
        withContext(Dispatchers.IO) {
            val hash = track.hash.ifBlank { track.id }
            if (hash.isBlank()) return@withContext ""
            val durationSec = track.durationMs / 1000
            val searchUrl = "$LYRIC_SEARCH?ver=1&man=yes&client=mobi&keyword=" +
                "&duration=$durationSec&hash=${SourceHttp.encode(hash)}"
            val search = SourceHttp.getJson(searchUrl, referer = REFERER_WEB, cookie = requestCookie(cookie))
                ?: return@withContext ""
            val candidate = search.arrayOrNull("candidates")?.firstOrNull()
                ?.takeIf { it.isJsonObject }?.asJsonObject ?: return@withContext ""
            val id = candidate.str("id")
            val accessKey = candidate.str("accesskey")
            if (id.isBlank()) return@withContext ""

            val downloadUrl = "$LYRIC_DOWNLOAD?ver=1&client=pc&id=${SourceHttp.encode(id)}" +
                "&accesskey=${SourceHttp.encode(accessKey)}&fmt=lrc&charset=utf8"
            val download = SourceHttp.getJson(downloadUrl, referer = REFERER_WEB, cookie = requestCookie(cookie))
                ?: return@withContext ""
            // 歌词正文是 Base64 的 LRC，直接当字符串返回会让整页显示乱码
            val content = download.str("content")
            if (content.isBlank()) return@withContext ""
            runCatching {
                String(Base64.getDecoder().decode(content), Charsets.UTF_8)
            }.getOrDefault("")
        }

    // ---------------------------------------------------------------- 工具

    private fun auth(cookie: String): Auth {
        val jar = parseCookie(cookie)
        return Auth(
            mid = jar["kg_mid"] ?: jar["KG_MID"] ?: processMid,
            dfid = jar["kg_dfid"] ?: jar["KG_DFID"] ?: "-",
            token = jar["token"].orEmpty(),
            userId = jar["userid"] ?: jar["user_id"].orEmpty(),
        )
    }

    /**
     * 把本机 mid / dfid 补进 Cookie。
     *
     * 平台用 mid 串搜索和取址是同一次会话；不补的话匿名请求也能过，
     * 但两次请求会被当成两台设备，取址偶尔拿不到地址。
     */
    private fun requestCookie(cookie: String): String {
        val jar = parseCookie(cookie)
        val auth = auth(cookie)
        val out = StringBuilder(cookie.trim())
        if ("kg_mid" !in jar && "KG_MID" !in jar) appendCookie(out, "kg_mid", auth.mid)
        if ("kg_dfid" !in jar && "KG_DFID" !in jar) appendCookie(out, "kg_dfid", auth.dfid)
        return out.toString()
    }

    private fun appendCookie(out: StringBuilder, key: String, value: String) {
        if (out.isNotEmpty() && out.last() != ';') out.append(';')
        out.append(' ').append(key).append('=').append(value)
    }

    private fun h5Signature(params: Map<String, String>): String {
        val joined = params.entries.joinToString("") { "${it.key}=${it.value}" }
        return SourceHttp.md5(H5_SALT + joined + H5_SALT)
    }

    private fun pickUrl(json: JsonObject): String {
        var url = json.str("url", "play_url", "backupUrl", "backup_url")
        if (url.isBlank()) {
            url = json.objectOrNull("data")?.str("url", "play_url", "backupUrl", "backup_url", "play_backup_url").orEmpty()
        }
        return url.replace("\\/", "/").trim()
    }

    private fun cover(raw: String): String {
        if (raw.isBlank()) return ""
        return SourceHttp.secure(raw.replace("{size}", "240").trim())
    }

    private fun fail(message: String): OnlinePlayback = OnlinePlayback(track = null, message = message)

    private data class Auth(
        val mid: String,
        val dfid: String,
        val token: String,
        val userId: String,
    )

    companion object {
        const val KEY = "kugou"
        const val LABEL = "酷狗音乐"

        private const val SEARCH_URL = "https://songsearch.kugou.com/song_search_v2"
        private const val PLAY_MOBILE = "https://m.kugou.com/app/i/getSongInfo.php"
        private const val PLAY_WEB = "https://wwwapi.kugou.com/play/songinfo"
        private const val LYRIC_SEARCH = "https://krcs.kugou.com/search"
        private const val LYRIC_DOWNLOAD = "https://krcs.kugou.com/download"

        private const val REFERER_WEB = "https://www.kugou.com/"
        private const val REFERER_MOBILE = "https://m.kugou.com/"

        private const val APPID = "1014"
        private const val H5_SRC_APPID = "2919"
        private const val H5_CLIENTVER = "20000"
        private const val H5_SALT = "NVPh5oo715z5DIWAeQlhMDsWXXQV4hwt"
        private const val KUGOU_KEY_SALT = "kgcloud"
    }
}
