package com.szc.learning.music.data.source

import com.google.gson.JsonObject
import com.szc.learning.core.common.music.MusicTrack
import com.szc.learning.core.common.music.TrackSource
import com.szc.learning.core.network.music.OnlinePlayback
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * QQ 音乐音源，**客户端直连**。
 *
 * 沿用后端三个网页版老接口，都不需要 weapi 签名：
 *
 * - 搜索  `c.y.qq.com/soso/fcgi-bin/client_search_cp`（Referer 必须是 y.qq.com）
 * - 取址  `u.y.qq.com/cgi-bin/musicu.fcg`，module=vkey.GetVkeyServer，
 *          返回的 purl 前面拼 CDN 域名才是完整地址
 * - 歌词  `c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg`，
 *          Referer 必须是 `y.qq.com/portal/player.html`，用首页会 403
 *
 * 匿名态限制是真实存在的：`purl` 为空 + `result=104003` 就是「要会员」。
 * 这里如实翻译成提示，不假装能播 —— 用户填了自己的 Cookie 才有机会播。
 */
internal class QqClientSource : ClientSourceProvider {

    override val key: String = KEY

    // ---------------------------------------------------------------- 搜索

    override suspend fun search(keyword: String, limit: Int, cookie: String): List<MusicTrack> =
        withContext(Dispatchers.IO) {
            val size = limit.coerceIn(1, 30)
            // 不能加 new_json=1：加了之后 albummid/singer 会换成嵌套结构，这里按老扁平结构解析
            val url = "$SEARCH_URL?w=${SourceHttp.encode(keyword)}&n=$size&p=1&format=json&t=0"
            val json = SourceHttp.getJson(url, referer = REFERER_WEB, cookie = cookie)
                ?: return@withContext emptyList()
            val list = json.objectOrNull("data")?.objectOrNull("song")?.arrayOrNull("list")
                ?: return@withContext emptyList()

            val tracks = ArrayList<MusicTrack>(list.size())
            for (element in list) {
                if (tracks.size >= size) break
                val item = element.takeIf { it.isJsonObject }?.asJsonObject ?: continue
                val mid = SourceHttp.stripTags(item.str("songmid", "mid"))
                val title = SourceHttp.stripTags(item.str("songname", "title", "name"))
                if (mid.isBlank() || title.isBlank()) continue
                val albumMid = SourceHttp.stripTags(item.str("albummid"))
                tracks += MusicTrack(
                    id = mid,
                    title = title,
                    artist = SourceHttp.joinArtists(
                        item.arrayOrNull("singer")?.mapNotNull { singer ->
                            singer.takeIf { it.isJsonObject }?.asJsonObject
                                ?.let { SourceHttp.stripTags(it.str("name")) }
                                ?.takeIf { it.isNotBlank() }
                        }.orEmpty(),
                    ),
                    album = SourceHttp.stripTags(item.str("albumname")),
                    // interval 是秒
                    durationMs = item.longValue("interval").coerceAtLeast(0L) * 1000L,
                    uri = "",
                    artUri = if (albumMid.isBlank()) "" else "$COVER$albumMid.jpg",
                    source = TrackSource.ONLINE,
                    sourceKey = KEY,
                    albumId = albumMid,
                    playable = isFree(item),
                )
            }
            tracks
        }

    // ---------------------------------------------------------------- 取址

    override suspend fun resolve(track: MusicTrack, cookie: String): OnlinePlayback =
        withContext(Dispatchers.IO) {
            val mid = SourceHttp.stripTags(track.id)
            if (mid.isBlank()) return@withContext fail("缺少 QQ 音乐 songmid")
            // 匿名态传 uin=0；填了 Cookie 就用用户自己的登录态，平台侧自己判断权限
            val uin = cookieValue(cookie, "uin", "wxuin", "p_uin")
                .replace(Regex("[^0-9]"), "")
                .ifBlank { "0" }
            val payload = "{\"req_0\":{\"module\":\"vkey.GetVkeyServer\",\"method\":\"CgiGetVkey\"," +
                "\"param\":{\"guid\":\"$GUID\",\"songmid\":[\"$mid\"],\"songtype\":[0]," +
                "\"uin\":\"$uin\",\"loginUin\":\"$uin\",\"hostUin\":0," +
                "\"platform\":\"yqq.json\",\"needNewCode\":0}}}"
            val url = "$VKEY_URL?data=${SourceHttp.encode(payload)}&format=json"
            val json = SourceHttp.getJson(url, referer = REFERER_WEB, cookie = cookie)
                ?: return@withContext fail("QQ音乐取址请求失败")

            val info = json.objectOrNull("req_0")?.objectOrNull("data")
                ?.arrayOrNull("midurlinfo")?.firstOrNull()
                ?.takeIf { it.isJsonObject }?.asJsonObject
            val purl = info?.str("purl").orEmpty().trim()
            if (purl.isNotBlank()) {
                val playUrl = if (purl.startsWith("http")) purl else CDN + purl
                return@withContext OnlinePlayback(track = track.copy(uri = SourceHttp.secure(playUrl)))
            }

            // purl 为空按 result 码给原因。104003 是「无版权 / 需要会员」，
            // 周杰伦这类全量 VIP 曲目都会落到这里。
            when (info?.intValue("result") ?: 0) {
                104003 -> {
                    val tips = info?.str("tips").orEmpty()
                    fail(tips.ifBlank { "这首歌在 QQ音乐 需要会员，可在音源管理里填入自己的 Cookie" })
                }
                104004, 104005 -> fail("QQ音乐 未上架这首歌")
                104001 -> fail("QQ音乐 登录态失效，请更新 Cookie")
                else -> fail("QQ音乐 没有返回播放地址")
            }
        }

    // ---------------------------------------------------------------- 歌词

    override suspend fun lyric(track: MusicTrack, cookie: String): String =
        withContext(Dispatchers.IO) {
            val mid = SourceHttp.stripTags(track.id)
            if (mid.isBlank()) return@withContext ""
            val url = "$LYRIC_URL?songmid=${SourceHttp.encode(mid)}&format=json&nobase64=1&g_tk=5381"
            val json = SourceHttp.getJson(url, referer = REFERER_PLAYER, cookie = cookie)
                ?: return@withContext ""
            json.str("lyric")
        }

    // ---------------------------------------------------------------- 工具

    /**
     * `pay.payplay == 0` 才是匿名可播。
     * 缺字段时按「可播」乐观处理 —— 真实可播性以取址结果为准，
     * 这里判死只会让搜索结果凭空少一批。
     */
    private fun isFree(item: JsonObject): Boolean {
        val pay: JsonObject = item.objectOrNull("pay") ?: return true
        return pay.intValue("payplay") == 0
    }

    private fun fail(message: String): OnlinePlayback = OnlinePlayback(track = null, message = message)

    companion object {
        const val KEY = "qq"
        const val LABEL = "QQ音乐"

        private const val SEARCH_URL = "https://c.y.qq.com/soso/fcgi-bin/client_search_cp"
        private const val VKEY_URL = "https://u.y.qq.com/cgi-bin/musicu.fcg"
        private const val LYRIC_URL = "https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg"
        private const val CDN = "https://dl.stream.qqmusic.qq.com/"
        private const val COVER = "https://y.gtimg.cn/music/photo_new/T002R240x240M000"

        private const val REFERER_WEB = "https://y.qq.com/"
        private const val REFERER_PLAYER = "https://y.qq.com/portal/player.html"

        /** 取址要一个设备 guid，固定成常量，免得每次随机被平台当成异常设备。 */
        private const val GUID = "3982123456"
    }
}
