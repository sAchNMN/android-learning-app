package com.szc.learning.music.data.source

import com.google.gson.JsonObject
import com.szc.learning.core.common.music.MusicTrack
import com.szc.learning.core.common.music.TrackSource
import com.szc.learning.core.network.music.OnlinePlayback
import com.szc.learning.core.network.music.UserPlaylistInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.util.Base64

/**
 * 网易云音源，**客户端直连**。
 *
 * 用的是三条公开老接口，不需要 weapi/eapi 加密 —— 后端 `NeteaseSourceProvider`
 * 当初就是这个取舍，这里原样搬过来：
 *
 * - 搜索  `/api/search/get/web`
 * - 取址  `/api/song/enhance/player/url`，失败再探 `/song/media/outer/url`
 * - 歌词  `/api/song/lyric`
 *
 * 唯一的加密在**封面**：搜索接口只给 `album.picId` 不给 `picUrl`，
 * 图片地址必须由 picId 算出 CDN 目录段（见 [encryptPicId]）。少了它搜索结果全是灰块。
 *
 * 与后端的差异只有一处：**VIP 曲目不会因为"后端没登录"而拿不到地址**。
 * 用户在音源管理页填的 Cookie 现在直接参与请求，谁的会员谁的歌，不经过任何服务器。
 */
internal class NeteaseClientSource : ClientSourceProvider {

    override val key: String = KEY

    // ---------------------------------------------------------------- 搜索

    override suspend fun search(keyword: String, limit: Int, cookie: String): List<MusicTrack> =
        withContext(Dispatchers.IO) {
            val size = limit.coerceIn(1, 30)
            val url = "$BASE/api/search/get/web?csrf_token=&s=${SourceHttp.encode(keyword)}" +
                "&type=1&offset=0&total=true&limit=$size"
            val json = SourceHttp.getJson(url, referer = "$BASE/", cookie = mergedCookie(cookie))
                ?: return@withContext emptyList()
            val songs = json.objectOrNull("result")?.arrayOrNull("songs")
                ?: return@withContext emptyList()

            val tracks = ArrayList<MusicTrack>(songs.size())
            for (element in songs) {
                if (tracks.size >= size) break
                val song = element.takeIf { it.isJsonObject }?.asJsonObject ?: continue
                toTrack(song)?.let { tracks += it }
            }
            tracks
        }

    // ---------------------------------------------------------------- 取址

    /**
     * 换取播放地址。
     *
     * `br` 直接决定平台下发什么码率，但它只是**请求**：非会员、曲目本身没有
     * 无损母带、地区限制，平台都会静默降到它能给的档位，甚至在
     * `freeTrialInfo` 里塞一个 30 秒片段。所以返回值必须把平台实际给的
     * `level` / `br` / 试听标记一起带出去，由上层判断要不要提示用户。
     */
    override suspend fun resolve(
        track: MusicTrack,
        cookie: String,
        quality: AudioQuality,
    ): OnlinePlayback =
        withContext(Dispatchers.IO) {
            val id = track.id
            if (id.isBlank()) return@withContext fail("缺少网易云歌曲 id")
            val jar = mergedCookie(cookie)
            val url = "$BASE/api/song/enhance/player/url?ids=%5B${SourceHttp.encode(id)}%5D&br=${quality.br}"
            val json = SourceHttp.getJson(url, referer = "$BASE/", cookie = jar)
            val item = json?.arrayOrNull("data")?.firstOrNull()
                ?.takeIf { it.isJsonObject }?.asJsonObject
            resolveFromItem(track, item, jar)
        }

    override val supportsBatch: Boolean get() = true

    /**
     * 批量取址：同一接口一次问多个 id，按响应里的 `id` 对回曲目。
     *
     * 这是首屏等待时间的主要来源 —— 原先每首一次请求，被同域名 450ms 节流串行化，
     * 51 首「我喜欢的音乐」要等二十多秒。一次最多 100 个 id（URL 长度足够，平台也接受）。
     *
     * 有 `url` 的直接算可播；没有的仍然逐首走外链探测与错误码翻译，
     * 和 [resolve] 共用 [resolveFromItem]，因此判定结果与逐首路径逐项一致。
     */
    override suspend fun resolveBatch(
        tracks: List<MusicTrack>,
        cookie: String,
        quality: AudioQuality,
    ): Map<String, OnlinePlayback> =
        withContext(Dispatchers.IO) {
            val out = LinkedHashMap<String, OnlinePlayback>(tracks.size * 2)
            if (tracks.isEmpty()) return@withContext out
            val jar = mergedCookie(cookie)

            for (chunk in tracks.chunked(BATCH_MAX_IDS)) {
                val items = HashMap<String, JsonObject>(chunk.size * 2)
                val ids = chunk.map { it.id }.filter { it.isNotBlank() }
                if (ids.isNotEmpty()) {
                    val url = "$BASE/api/song/enhance/player/url" +
                        "?ids=%5B${SourceHttp.encode(ids.joinToString(","))}%5D&br=${quality.br}"
                    SourceHttp.getJson(url, referer = "$BASE/", cookie = jar)
                        ?.arrayOrNull("data")
                        ?.forEach { element ->
                            val item = element.takeIf { it.isJsonObject }?.asJsonObject
                                ?: return@forEach
                            // 不依赖返回顺序，按 id 对回曲目
                            val id = item.str("id")
                            if (id.isNotBlank()) items[id] = item
                        }
                }
                for (track in chunk) {
                    out[track.key] = if (track.id.isBlank()) {
                        fail("缺少网易云歌曲 id")
                    } else {
                        resolveFromItem(track, items[track.id], jar)
                    }
                }
            }
            out
        }

    /**
     * 从一条取址响应得出最终结果。单曲与批量共用，保证两条路径判定一致。
     *
     * 1.1 增强接口正常下发地址；
     * 1.2 没地址时用外链 302 兜一层（固定 128k 转码流，不带 level）；
     * 1.3 都没有，按平台错误码翻译成人话。
     */
    private suspend fun resolveFromItem(
        track: MusicTrack,
        item: JsonObject?,
        jar: String,
    ): OnlinePlayback {
        val playUrl = item?.str("url").orEmpty()
        if (playUrl.isNotBlank()) {
            return play(track, playUrl, item)
        }

        // 1.2 增强接口对部分免费曲目也不下发地址，用外链 302 兜一层。
        //     这一层不认 br 参数，拿到的固定是 128k 转码流，不带 level。
        val outer = "$BASE/song/media/outer/url?id=${SourceHttp.encode(track.id)}.mp3"
        SourceHttp.probeAudio(outer, referer = "$BASE/", cookie = jar)?.let { final ->
            return OnlinePlayback(
                track = track.copy(uri = SourceHttp.secure(final)),
                qualityLevel = AudioQuality.STANDARD.level,
            )
        }

        // 1.3 都没有，按平台错误码翻译成人话
        val code = item?.intValue("code") ?: 0
        val fee = item?.intValue("fee") ?: 0
        return when {
            code == -110 || code == 301 ->
                fail("网易云需要登录后才能获取完整播放地址，可在音源管理里填入 Cookie")
            fee == 4 -> fail("网易云这首歌需要购买单曲或专辑")
            fee == 1 -> fail("网易云这首歌需要会员权限")
            code == 404 || code == -105 -> fail("网易云这首歌暂无版权或受地区限制")
            else -> fail("网易云没有返回可播放地址")
        }
    }

    /**
     * 把取址响应里和音质有关的三件事抠出来。
     *
     * `freeTrialInfo` 非 null 就是试听片段 —— 这是唯一可信的「没有完整播放权」
     * 信号，比 `fee` 准：有些会员曲目在会员态下 `fee` 仍是 1，但会给完整地址。
     */
    private fun play(track: MusicTrack, rawUrl: String, item: JsonObject?): OnlinePlayback =
        OnlinePlayback(
            track = track.copy(uri = SourceHttp.secure(rawUrl)),
            qualityLevel = item?.str("level").orEmpty().ifBlank { null },
            br = item?.intValue("br") ?: 0,
            trial = item?.objectOrNull("freeTrialInfo") != null,
        )

    // ---------------------------------------------------------------- 歌词

    override suspend fun lyric(track: MusicTrack, cookie: String): String =
        withContext(Dispatchers.IO) {
            val id = track.id
            if (id.isBlank()) return@withContext ""
            val url = "$BASE/api/song/lyric?os=pc&id=${SourceHttp.encode(id)}&lv=-1&kv=-1&tv=-1"
            val json = SourceHttp.getJson(url, referer = "$BASE/", cookie = mergedCookie(cookie))
                ?: return@withContext ""
            json.objectOrNull("lrc")?.str("lyric").orEmpty()
        }

    // ---------------------------------------------------------------- 推荐流

    /**
     * 新歌推荐。公开接口，不带 Cookie 也能用 ——
     * 新用户还没有收藏时，推荐页靠它兜底，不会开屏一片空白。
     */
    override suspend fun dailyRecommend(limit: Int, cookie: String): List<MusicTrack> =
        withContext(Dispatchers.IO) {
            val size = limit.coerceIn(1, 30)
            val json = SourceHttp.getJson(
                "$BASE/api/personalized/newsong?limit=$size",
                referer = "$BASE/",
                cookie = mergedCookie(cookie),
            ) ?: return@withContext emptyList()
            val result = json.arrayOrNull("result") ?: return@withContext emptyList()
            result.mapNotNull { element ->
                val item = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                // 新歌接口把歌曲本体包在 song 里，老版本直接平铺
                toTrack(item.objectOrNull("song") ?: item)
            }.take(size)
        }

    // ---------------------------------------------------------------- 账号歌单

    /**
     * 账号里的歌单：自己建的排前面，收藏的排后面。
     *
     * 两步请求：先 `/api/nuser/account/get` 拿 uid，再 `/api/user/playlist` 拉列表。
     * 未登录或登录态失效都返回空数组，不抛异常 —— 音源页会显示"还没登录"。
     */
    override suspend fun myPlaylists(cookie: String): List<UserPlaylistInfo> =
        withContext(Dispatchers.IO) {
            if (cookie.isBlank()) return@withContext emptyList()
            val jar = mergedCookie(cookie)
            val uid = currentUid(jar) ?: return@withContext emptyList()
            playlistsOf(uid, jar)
        }

    // ---------------------------------------------------------------- 账号资料

    /**
     * 登录后能读到的全部资料。
     *
     * 三步请求，全部走 [SourceHttp]（带节流）：账号主体 → 等级/累计听歌 → 歌单。
     * 只读一次就缓存 10 分钟（见 `SourceAccountCache`），进页面反复刷新不会打平台。
     *
     * 任何一步拿不到都只是这一块空着，不抛异常 —— 账号页是展示页，
     * 某个字段解析失败不该让整页变成错误提示。
     */
    override suspend fun account(cookie: String): SourceAccount? =
        withContext(Dispatchers.IO) {
            if (cookie.isBlank()) return@withContext null
            val jar = mergedCookie(cookie)

            /*
             * ================================================================
             * 步骤1：确认登录的是谁
             * ================================================================
             * 接口：/api/nuser/account/get
             * 要点：
             * 1) 未登录时返回 code=200 但 account=null，不能只看 code
             * 2) profile 里已经带昵称、头像、会员类型、注册时间、关注/粉丝
             */
            val root = SourceHttp.getJson("$BASE/api/nuser/account/get", referer = "$BASE/", cookie = jar)
                ?: return@withContext null
            val accountObj = root.objectOrNull("account") ?: return@withContext null
            val uid = accountObj.longValue("id")
            if (uid <= 0) return@withContext null
            val basic = root.objectOrNull("profile")

            /*
             * ================================================================
             * 步骤2：补充等级与累计听歌
             * ================================================================
             * 接口：/api/v1/user/detail/{uid}（公开）
             * 要点：这一层还会给一份更完整的 profile，优先用它
             */
            val detail = SourceHttp.getJson("$BASE/api/v1/user/detail/$uid", referer = "$BASE/", cookie = jar)
            val profile = detail?.objectOrNull("profile") ?: basic

            /*
             * ================================================================
             * 步骤3：歌单
             * ================================================================
             * 与「账号歌单」共用同一个解析，避免两处字段名各写一套
             */
            val playlists = playlistsOf(uid, jar)

            /*
             * ================================================================
             * 步骤4：最近一周听歌排行
             * ================================================================
             * 接口：/api/v1/play/record?uid=&type=0
             * 要点：
             * 1) 这个接口必须带登录 Cookie，未登录返回 code=-2
             * 2) weekData 是最近一周，allData 是全部；页面要的是「最近在听什么」
             * 3) 拿不到就当没有，不额外重试 —— 多打一次就多一分风控风险
             */
            val record = SourceHttp.getJson(
                "$BASE/api/v1/play/record?uid=$uid&type=0",
                referer = "$BASE/",
                cookie = jar,
            )
            // 近一周没听歌时 weekData 是空数组（不是 0 条数据，是整块空）——
            // 这时候退到 allData，页面改标题为「常听排行」。有真数据就别用旧账。
            val weekRaw = record?.arrayOrNull("weekData")
            val recentWeekly = weekRaw != null && weekRaw.size() > 0
            val recentRaw = if (recentWeekly) weekRaw else record?.arrayOrNull("allData")
            val recentTracks = recentRaw?.mapNotNull { element ->
                val item = element.takeIf { it.isJsonObject }?.asJsonObject
                    ?: return@mapNotNull null
                item.objectOrNull("song")?.let { toTrack(it) }
            }.orEmpty().take(RECENT_LIMIT)

            // 排行光是排序没有说服力，「听了 105 次」才是信息
            val recentPlayCounts = recentRaw?.mapNotNull { element ->
                val item = element.takeIf { it.isJsonObject }?.asJsonObject
                    ?: return@mapNotNull null
                val song = item.objectOrNull("song") ?: return@mapNotNull null
                val track = toTrack(song) ?: return@mapNotNull null
                val count = item.longValue("playCount")
                if (count <= 0) null else track.key to count
            }?.toMap().orEmpty()

            SourceAccount(
                sourceKey = KEY,
                userId = uid.toString(),
                nickname = profile?.str("nickname").orEmpty(),
                avatarUrl = SourceHttp.secure(profile?.str("avatarUrl").orEmpty()),
                vipType = profile?.intValue("vipType") ?: 0,
                level = detail?.intValue("level") ?: 0,
                listenSongs = detail?.longValue("listenSongs") ?: 0,
                follows = profile?.intValue("follows") ?: 0,
                followeds = profile?.intValue("followeds") ?: 0,
                playlistCount = profile?.intValue("playlistCount") ?: playlists.size,
                createTimeMs = profile?.longValue("createTime") ?: 0,
                lastLoginMs = profile?.longValue("lastLoginTime") ?: 0,
                signature = profile?.str("signature").orEmpty(),
                gender = profile?.intValue("gender") ?: 0,
                birthdayMs = profile?.longValue("birthday") ?: 0,
                playlists = playlists,
                recentTracks = recentTracks,
                recentPlayCounts = recentPlayCounts,
                recentWeekly = recentWeekly,
                statsFromPlatform = true,
            )
        }

    /** 当前登录账号的 uid；未登录返回 null。 */
    private suspend fun currentUid(jar: String): Long? {
        val account = SourceHttp.getJson("$BASE/api/nuser/account/get", referer = "$BASE/", cookie = jar)
            ?.objectOrNull("account") ?: return null
        val uid = account.longValue("id")
        return if (uid > 0) uid else null
    }

    private suspend fun playlistsOf(uid: Long, jar: String): List<UserPlaylistInfo> {
        val list = SourceHttp.getJson(
            "$BASE/api/user/playlist?uid=$uid&limit=100&offset=0",
            referer = "$BASE/",
            cookie = jar,
        )?.arrayOrNull("playlist") ?: return emptyList()

        return list.mapNotNull { element ->
            val item = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val id = item.str("id")
            val name = item.str("name")
            if (id.isBlank() || name.isBlank()) return@mapNotNull null
            val creator = item.objectOrNull("creator")
            UserPlaylistInfo(
                source = KEY,
                id = id,
                name = name,
                coverUrl = withSize(SourceHttp.secure(item.str("coverImgUrl"))).ifBlank { null },
                trackCount = item.intValue("trackCount"),
                // 「我创建的」排前面：收藏来的歌单 creator 是别人
                own = creator != null && creator.longValue("userId") == uid,
            )
        }.sortedByDescending { it.own }
    }

    override suspend fun playlistTracks(
        playlistId: String,
        name: String?,
        limit: Int,
        cookie: String,
    ): List<MusicTrack> = withContext(Dispatchers.IO) {
        if (playlistId.isBlank()) return@withContext emptyList()
        val size = limit.coerceIn(1, 500)
        val tracks = SourceHttp.getJson(
            "$BASE/api/v6/playlist/detail?id=${SourceHttp.encode(playlistId)}&n=$size",
            referer = "$BASE/",
            cookie = mergedCookie(cookie),
        )?.objectOrNull("playlist")?.arrayOrNull("tracks")
            ?: return@withContext emptyList()
        tracks.mapNotNull { element ->
            element.takeIf { it.isJsonObject }?.asJsonObject?.let { toTrack(it) }
        }.take(size)
    }

    // ---------------------------------------------------------------- 解析

    private fun toTrack(song: JsonObject): MusicTrack? {
        val id = song.str("id")
        val title = SourceHttp.stripTags(song.str("name", "title"))
        if (id.isBlank() || title.isBlank()) return null

        val album = song.objectOrNull("album") ?: song.objectOrNull("al")
        val duration = song.longValue("duration", "dt")
        // fee: 0 免费 / 8 免费但低音质 / 1 会员 / 4 付费专辑
        val fee = song.intValue("fee")

        return MusicTrack(
            id = id,
            title = title,
            artist = SourceHttp.joinArtists(
                (song.arrayOrNull("artists") ?: song.arrayOrNull("ar"))
                    ?.mapNotNull { element ->
                        element.takeIf { it.isJsonObject }?.asJsonObject?.str("name")?.takeIf { it.isNotBlank() }
                    }.orEmpty(),
            ),
            album = SourceHttp.stripTags(album?.str("name").orEmpty()),
            durationMs = duration.coerceAtLeast(0L),
            uri = "",
            artUri = albumCover(album),
            source = TrackSource.ONLINE,
            sourceKey = KEY,
            playable = fee == 0 || fee == 8,
        )
    }

    /**
     * 封面地址。
     *
     * 新版接口直接给 `picUrl`；老接口只给 `picId`，要自己算 CDN 目录段。
     * 两者都要补 `?param=240y240`，否则图片是原图（可达 1MB），列表会卡。
     */
    private fun albumCover(album: JsonObject?): String {
        if (album == null) return ""
        val direct = album.str("picUrl")
        if (direct.isNotBlank()) return withSize(SourceHttp.secure(direct))
        val picId = album.longValue("picId")
        return if (picId <= 0) "" else withSize("$IMG_CDN/${encryptPicId(picId.toString())}/$picId.jpg")
    }

    private fun withSize(url: String): String =
        if (url.isBlank()) "" else if (url.contains("?")) url else "$url?param=240y240"

    /**
     * 网易云 CDN 的目录段：picId 与固定串逐位异或，取 MD5 后 Base64（URL 安全变体）。
     *
     * 这个"算法"是前端 JS 里公开的，不是密钥。写死在这里没有安全问题 ——
     * 它只决定图片路径，猜不出来就只是看不到封面。
     */
    private fun encryptPicId(id: String): String {
        val raw = StringBuilder(id.length)
        for (index in id.indices) {
            // 必须 toChar()：StringBuilder.append(Int) 拼的是十进制数字，不是码点字符
            raw.append((MAGIC[index % MAGIC.length].code xor id[index].code).toChar())
        }
        return runCatching {
            val digest = MessageDigest.getInstance("MD5").digest(raw.toString().toByteArray(Charsets.UTF_8))
            Base64.getEncoder().encodeToString(digest).replace('/', '_').replace('+', '-')
        }.getOrDefault("")
    }

    // ---------------------------------------------------------------- 工具

    /**
     * 平台要求带 `appver` / `os`，否则部分接口返回精简字段。
     * 用户 Cookie 拼在后面，不覆盖基础项。
     */
    private fun mergedCookie(cookie: String): String {
        val base = "appver=2.0.2; os=pc"
        return if (cookie.isBlank()) base else "$base; ${cookie.trim().trimEnd(';')}"
    }

    private fun fail(message: String): OnlinePlayback = OnlinePlayback(track = null, message = message)

    companion object {
        const val KEY = "netease"
        const val LABEL = "网易云音乐"

        private const val BASE = "https://music.163.com"
        private const val IMG_CDN = "https://p1.music.126.net"
        private const val MAGIC = "3go8&$8*3*3h0k(2)2"

        /** 账号页只摆十首「最近常听」，再多就是一张歌单了。 */
        private const val RECENT_LIMIT = 10

        /** 批量取址一次最多问多少个 id：再多 URL 会超长，平台也会截断。 */
        private const val BATCH_MAX_IDS = 100
    }
}
