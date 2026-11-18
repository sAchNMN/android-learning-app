package com.szc.learning.music.ui.source

import android.webkit.CookieManager
import com.szc.learning.R
import java.util.Locale

/**
 * 用桌面 UA 打开酷狗：网页版登录入口（二维码/手机号）是给桌面浏览器做的，
 * 移动 UA 会被引导去下载 App，反而拿不到登录态。
 */
private const val DESKTOP_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

/**
 * 一个平台的「登录页 → 登录态」映射。
 *
 * 只声明登录页和 Cookie 白名单：登录动作由平台自己的网页完成，
 * App 不接触账号密码，也不解析登录接口。
 */
data class SourceLoginSpec(
    val sourceKey: String,
    val labelRes: Int,
    val loginUrl: String,
    /** 从这些 URL 的 Cookie 罐里读；覆盖主域和接口域，避免漏掉 HttpOnly 登录票据。 */
    val cookieUrls: List<String>,
    /** 小写名字白名单：只把鉴权/设备相关的 Cookie 交给后端，不把统计 Cookie 一起带走。 */
    val allowedCookies: Set<String>,
    /** 任意一个存在且非空即视为已登录。 */
    val requiredCookies: Set<String>,
    /** null 表示用 WebView 默认的移动 UA（网易云移动登录页才有手机号登录）。 */
    val userAgent: String? = null,
)

object SourceLoginSpecs {

    private val NETEASE = SourceLoginSpec(
        sourceKey = "netease",
        labelRes = R.string.source_netease,
        loginUrl = "https://music.163.com/#/login",
        cookieUrls = listOf("https://music.163.com/"),
        allowedCookies = setOf(
            "music_u",
            "music_a",
            "__csrf",
            "nmtid",
            "_ntes_nuid",
            "_ntes_nnid",
            "os",
            "appver",
            "channel",
            "mode",
        ),
        requiredCookies = setOf("music_u"),
    )

    private val KUGOU = SourceLoginSpec(
        sourceKey = "kugou",
        labelRes = R.string.source_kugou,
        loginUrl = "https://www.kugou.com/",
        cookieUrls = listOf(
            "https://www.kugou.com/",
            "https://kugou.com/",
            "https://m.kugou.com/",
        ),
        allowedCookies = setOf(
            "kugoo",
            "kugou",
            "token",
            "userid",
            "kg_mid",
            "kg_mid_temp",
            "kg_dfid",
            "dfid",
            "mid",
            "kugoumusic_session",
        ),
        requiredCookies = setOf("kugoo", "userid"),
        userAgent = DESKTOP_UA,
    )

    private val QQ = SourceLoginSpec(
        sourceKey = "qq",
        labelRes = R.string.source_qq,
        // 登录页只有桌面版才有二维码；移动版直接跳下载 App
        loginUrl = "https://y.qq.com/",
        cookieUrls = listOf("https://y.qq.com/", "https://c.y.qq.com/"),
        allowedCookies = setOf(
            "uin",
            "p_uin",
            "wxuin",
            "qm_keyst",
            "qqmusic_key",
            "skey",
            "p_skey",
            "psrf_musickey_createtime",
            "psrf_qqopenid",
            "psrf_access_token_expiresat",
        ),
        // 真正能解锁播放的是 qm_keyst（网页端的 qqmusic_key），光有 uin 只是标识
        requiredCookies = setOf("qm_keyst", "qqmusic_key"),
        userAgent = DESKTOP_UA,
    )

    private val KUWO = SourceLoginSpec(
        sourceKey = "kuwo",
        labelRes = R.string.source_kuwo,
        loginUrl = "https://www.kuwo.cn/",
        cookieUrls = listOf("https://www.kuwo.cn/", "http://www.kuwo.cn/"),
        allowedCookies = setOf(
            "kw_token",
            "kw_id",
            "userid",
            "kw_ukey",
            "Hm_token",
        ),
        // kw_token 是酷我的 CSRF/设备令牌，游客访问首页也会下发 ——
        // 这里只要有它就算「拿到了可用登录态」，不宣称是会员态。
        requiredCookies = setOf("kw_token", "kw_id"),
        userAgent = DESKTOP_UA,
    )

    fun of(sourceKey: String): SourceLoginSpec? = when (sourceKey) {
        "netease" -> NETEASE
        "kugou" -> KUGOU
        "qq" -> QQ
        "kuwo" -> KUWO
        else -> null
    }
}

object SourceCookieReader {

    /** 把 Cookie 罐里的白名单项按 `name=value; ...` 拼回请求头。 */
    fun read(spec: SourceLoginSpec): LinkedHashMap<String, String> {
        val manager = CookieManager.getInstance()
        val result = LinkedHashMap<String, String>()
        spec.cookieUrls.forEach { url ->
            val raw = runCatching { manager.getCookie(url) }.getOrNull() ?: return@forEach
            raw.split(';').forEach { chunk ->
                val part = chunk.trim()
                val eq = part.indexOf('=')
                if (eq <= 0) return@forEach
                val name = part.substring(0, eq).trim()
                if (name.lowercase(Locale.ROOT) !in spec.allowedCookies) return@forEach
                val value = part.substring(eq + 1).trim()
                if (value.isEmpty()) return@forEach
                // 同名 Cookie 以更具体的路径/后读到的为准；LinkedHashMap 保留首次顺序
                result[name] = value
            }
        }
        return result
    }

    fun header(cookies: Map<String, String>): String =
        cookies.entries.joinToString("; ") { (name, value) -> "$name=$value" }

    /** `userid=0` 是酷狗的游客态，不能被当成登录成功。 */
    fun hasLogin(spec: SourceLoginSpec, cookies: Map<String, String>): Boolean =
        cookies.any { (name, value) ->
            name.lowercase(Locale.ROOT) in spec.requiredCookies &&
                value.isNotBlank() &&
                value != "0" &&
                !value.equals("deleted", ignoreCase = true)
        }
}