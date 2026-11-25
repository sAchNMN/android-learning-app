package com.ruoyi.music.source;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.music.domain.OnlinePlay;
import com.ruoyi.music.domain.OnlineTrack;
import com.ruoyi.music.domain.SyncResult;
import com.ruoyi.music.domain.UserPlaylist;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 网易云音乐适配器。
 *
 * 对应 Mineradio {@code server.js} 里的 netease 分支 + NeteaseCloudMusicApi 包。
 * 这里只用到公开的三个老接口：
 *
 * - 搜索  {@code /api/search/get/web}
 * - 取址  {@code /api/song/enhance/player/url}
 * - 歌词  {@code /api/song/lyric}
 *
 * 不移植 weapi/eapi 加密：Mineradio 依赖的 NeteaseCloudMusicApi 本质也是这些接口的
 * 签名封装，而匿名可播的曲目用公开接口就能拿到地址。VIP/付费曲目由用户自己填
 * Cookie 解锁，服务端不参与绕过会员。
 */
@Component
public class NeteaseSourceProvider implements MusicSourceProvider {

    private static final String BASE = "https://music.163.com";

    @Override
    public String key() {
        return "netease";
    }

    @Override
    public String label() {
        return "网易云音乐";
    }

    @Override
    public List<OnlineTrack> search(String keyword, int limit, String cookie) {
        int size = Math.max(1, Math.min(limit, 30));
        String url = BASE + "/api/search/get/web?csrf_token=&s=" + HttpJson.encode(keyword)
                + "&type=1&offset=0&total=true&limit=" + size;
        JSONObject json = HttpJson.getJson(url, headers(cookie), 8);
        JSONObject result = json.getJSONObject("result");
        if (result == null) {
            return new ArrayList<>();
        }
        JSONArray songs = result.getJSONArray("songs");
        if (songs == null) {
            return new ArrayList<>();
        }

        List<OnlineTrack> tracks = new ArrayList<>();
        for (int i = 0; i < songs.size() && tracks.size() < size; i++) {
            OnlineTrack track = toTrack(songs.getJSONObject(i));
            if (track != null) {
                tracks.add(track);
            }
        }
        return tracks;
    }

    /**
     * 把网易云的 song 对象翻成统一模型。
     *
     * 三个接口（搜索 / 歌单详情 / 推荐新歌）返回的是同一套字段，只是外层包装不同，
     * 所以解析只写一份：{@code name/artists|ar/album|al/duration|dt} 都兼容。
     */
    private OnlineTrack toTrack(JSONObject song) {
        if (song == null) {
            return null;
        }
        OnlineTrack track = new OnlineTrack();
        track.setSource(key());
        track.setId(song.getString("id"));
        track.setTitle(firstNonBlank(song.getString("name"), song.getString("title")));
        track.setArtist(joinArtists(song));
        JSONObject album = song.getJSONObject("album");
        if (album == null) {
            album = song.getJSONObject("al");
        }
        if (album != null) {
            track.setAlbum(album.getString("name"));
            track.setCoverUrl(albumCover(album));
        }
        long duration = song.getLongValue("duration");
        if (duration <= 0) {
            duration = song.getLongValue("dt");
        }
        track.setDurationMs(duration);
        int fee = song.getIntValue("fee");
        // fee: 0 免费 / 8 免费但低音质 / 1 会员 / 4 付费专辑
        track.setPlayable(fee == 0 || fee == 8);
        return track.getTitle() != null && !track.getTitle().isBlank() && track.getId() != null
                ? track : null;
    }

    @Override
    public OnlinePlay resolve(OnlineTrack track, String cookie, String quality) {
        String id = track.getId();
        int br = bitrate(quality);
        String url = BASE + "/api/song/enhance/player/url?ids=%5B" + HttpJson.encode(id) + "%5D&br=" + br;
        try {
            JSONObject json = HttpJson.getJson(url, headers(cookie), 8);
            JSONArray data = json.getJSONArray("data");
            JSONObject item = data == null || data.isEmpty() ? null : data.getJSONObject(0);
            if (item != null) {
                String playUrl = item.getString("url");
                if (playUrl != null && !playUrl.isBlank()) {
                    // 发布版 Android 默认禁止明文 HTTP，网易云 CDN 同时支持 https，这里统一升级
                    OnlinePlay play = OnlinePlay.ok(key(), id, secure(playUrl), levelOf(item, quality));
                    play.setTrial(item.getIntValue("fee") == 1 && playUrl.contains("short"));
                    return play;
                }
            }
            int code = item == null ? 0 : item.getIntValue("code");
            int fee = item == null ? 0 : item.getIntValue("fee");

            // 增强接口偶尔对免费曲目也不下发地址，再用「外链 302」兜一层。
            String outer = BASE + "/song/media/outer/url?id=" + HttpJson.encode(id) + ".mp3";
            HttpJson.HttpResult probe = HttpJson.probe(outer, headers(cookie), 8);
            if (probe.statusCode < 400
                    && probe.contentType.toLowerCase().contains("audio")
                    && probe.finalUrl != null && !probe.finalUrl.contains("outer/url")) {
                return OnlinePlay.ok(key(), id, secure(probe.finalUrl), "standard");
            }

            if (code == -110 || code == 301) {
                return OnlinePlay.fail(key(), id, "login_required",
                        "网易云需要登录后才能获取完整播放地址，可在音源管理里填入 Cookie");
            }
            if (fee == 4) {
                return OnlinePlay.fail(key(), id, "paid_required", "网易云这首歌需要购买单曲或专辑");
            }
            if (fee == 1) {
                return OnlinePlay.fail(key(), id, "vip_required", "网易云这首歌需要会员权限");
            }
            if (code == 404 || code == -105) {
                return OnlinePlay.fail(key(), id, "copyright_unavailable", "网易云这首歌暂无版权或受地区限制");
            }
            return OnlinePlay.fail(key(), id, "url_unavailable", "网易云没有返回可播放地址，可尝试自动换源");
        } catch (HttpJson.SourceHttpException ex) {
            return OnlinePlay.fail(key(), id, "url_unavailable", "网易云请求失败：" + ex.getMessage());
        }
    }

    @Override
    public String lyric(OnlineTrack track, String cookie) {
        try {
            String url = BASE + "/api/song/lyric?os=pc&id=" + HttpJson.encode(track.getId())
                    + "&lv=-1&kv=-1&tv=-1";
            JSONObject json = HttpJson.getJson(url, headers(cookie), 8);
            JSONObject lrc = json.getJSONObject("lrc");
            return lrc == null ? "" : lrc.getString("lyric");
        } catch (RuntimeException ex) {
            return "";
        }
    }

    // ---------------------------------------------------------------- 账号能力

    @Override
    public boolean supportsAccount() {
        return true;
    }

    /**
     * 平台推荐流：新歌推荐。
     *
     * 这是公开接口，不带 Cookie 也能用 —— 新用户还没收藏时，
     * 推荐页就靠它兜底，不会开屏一片空白。
     */
    @Override
    public List<OnlineTrack> dailyRecommend(int limit, String cookie) {
        int size = Math.max(1, Math.min(limit, 30));
        try {
            JSONObject json = HttpJson.getJson(
                    BASE + "/api/personalized/newsong?limit=" + size, headers(cookie), 8);
            JSONArray result = json.getJSONArray("result");
            if (result == null) {
                return new ArrayList<>();
            }
            List<OnlineTrack> tracks = new ArrayList<>();
            for (int i = 0; i < result.size() && tracks.size() < size; i++) {
                JSONObject item = result.getJSONObject(i);
                if (item == null) {
                    continue;
                }
                // 新歌接口把歌曲本体包在 song 里，老版本直接平铺
                JSONObject song = item.getJSONObject("song");
                OnlineTrack track = toTrack(song == null ? item : song);
                if (track != null) {
                    tracks.add(track);
                }
            }
            return tracks;
        } catch (RuntimeException ex) {
            return new ArrayList<>();
        }
    }

    /**
     * 同步「喜欢 / 取消喜欢」。
     *
     * 用老 web 接口 {@code /api/radio/like}：它只需要登录 Cookie + csrf_token，
     * 不必移植 weapi 加密。返回 200 表示平台侧已生效。
     */
    @Override
    public SyncResult like(String trackId, boolean liked, String cookie) {
        if (cookie == null || cookie.isBlank()) {
            return SyncResult.fail(key(), "网易云未登录，已只记在本机与后台");
        }
        try {
            String url = BASE + "/api/radio/like?alg=itembased&trackId=" + HttpJson.encode(trackId)
                    + "&like=" + liked + "&csrf_token=" + HttpJson.encode(csrf(cookie));
            JSONObject json = HttpJson.postForm(url, "", headers(cookie), 8);
            int code = json.getIntValue("code");
            if (code == 200) {
                return SyncResult.ok(key());
            }
            if (code == 301 || code == -460 || code == -462) {
                return SyncResult.fail(key(), "网易云登录态已失效，请在音源管理里重新登录");
            }
            String message = json.getString("message");
            return SyncResult.fail(key(), "网易云返回 " + code
                    + (message == null || message.isBlank() ? "" : "：" + message));
        } catch (RuntimeException ex) {
            return SyncResult.fail(key(), "网易云同步失败：" + ex.getMessage());
        }
    }

    /** 账号里的歌单：自己建的排前面，收藏的排后面。 */
    @Override
    public List<UserPlaylist> myPlaylists(String cookie) {
        List<UserPlaylist> out = new ArrayList<>();
        if (cookie == null || cookie.isBlank()) {
            return out;
        }
        try {
            JSONObject accountJson = HttpJson.getJson(BASE + "/api/nuser/account/get", headers(cookie), 8);
            JSONObject account = accountJson.getJSONObject("account");
            if (account == null) {
                return out;
            }
            long uid = account.getLongValue("id");
            JSONObject json = HttpJson.getJson(
                    BASE + "/api/user/playlist?uid=" + uid + "&limit=100&offset=0", headers(cookie), 8);
            JSONArray playlists = json.getJSONArray("playlist");
            if (playlists == null) {
                return out;
            }
            for (int i = 0; i < playlists.size(); i++) {
                JSONObject item = playlists.getJSONObject(i);
                if (item == null) {
                    continue;
                }
                JSONObject creator = item.getJSONObject("creator");
                boolean own = creator != null && creator.getLongValue("userId") == uid;
                out.add(new UserPlaylist(
                        key(),
                        item.getString("id"),
                        item.getString("name"),
                        coverUrl(item.getString("coverImgUrl")),
                        item.getIntValue("trackCount"),
                        own));
            }
        } catch (RuntimeException ex) {
            // 未登录 / 平台改版都走这里：返回已拿到的部分，不把异常抛给聚合层
            return out;
        }
        return out;
    }

    @Override
    public List<OnlineTrack> playlistTracks(String playlistId, int limit, String cookie) {
        List<OnlineTrack> out = new ArrayList<>();
        int size = Math.max(1, Math.min(limit, 500));
        try {
            JSONObject json = HttpJson.getJson(
                    BASE + "/api/v6/playlist/detail?id=" + HttpJson.encode(playlistId) + "&n=" + size,
                    headers(cookie), 10);
            JSONObject playlist = json.getJSONObject("playlist");
            if (playlist == null) {
                return out;
            }
            JSONArray tracks = playlist.getJSONArray("tracks");
            if (tracks == null) {
                return out;
            }
            for (int i = 0; i < tracks.size() && out.size() < size; i++) {
                OnlineTrack track = toTrack(tracks.getJSONObject(i));
                if (track != null) {
                    out.add(track);
                }
            }
        } catch (RuntimeException ex) {
            return out;
        }
        return out;
    }

    /** 网易云的写接口要求 csrf_token 与 Cookie 里的 __csrf 一致，否则一律 301。 */
    private static String csrf(String cookie) {
        if (cookie == null) {
            return "";
        }
        for (String part : cookie.split(";")) {
            String trimmed = part.trim();
            if (trimmed.startsWith("__csrf=")) {
                return trimmed.substring("__csrf=".length()).trim();
            }
        }
        return "";
    }

    private Map<String, String> headers(String cookie) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Referer", BASE + "/");
        headers.put("Accept", "application/json, text/plain, */*");
        headers.put("Cookie", joinCookie("appver=2.0.2; os=pc", cookie));
        return headers;
    }

    private static String joinCookie(String base, String cookie) {
        if (cookie == null || cookie.isBlank()) {
            return base;
        }
        return cookie.trim().endsWith(";") ? base + "; " + cookie.trim() : base + "; " + cookie.trim();
    }

    private static String joinArtists(JSONObject song) {
        JSONArray artists = song.getJSONArray("artists");
        if (artists == null) {
            artists = song.getJSONArray("ar");
        }
        if (artists == null || artists.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < artists.size(); i++) {
            JSONObject artist = artists.getJSONObject(i);
            String name = artist == null ? "" : artist.getString("name");
            if (name == null || name.isBlank()) {
                continue;
            }
            if (out.length() > 0) {
                out.append(" / ");
            }
            out.append(name);
        }
        return out.toString();
    }

    /**
     * 搜索接口只给 {@code album.picId}，不给 {@code picUrl}。
     * 网易云的图片地址 = CDN + encrypt(picId) + "/" + picId + ".jpg"，
     * 不补这一步，搜索结果会全是灰块（酷狗返回的是现成 URL，所以看起来只有网易云缺图）。
     */
    private static String albumCover(JSONObject album) {
        if (album == null) {
            return "";
        }
        String direct = album.getString("picUrl");
        if (direct != null && !direct.isBlank()) {
            return coverUrl(direct);
        }
        long picId = album.getLongValue("picId");
        if (picId <= 0) {
            return "";
        }
        String id = String.valueOf(picId);
        return coverUrl("https://p1.music.126.net/" + encryptPicId(id) + "/" + id + ".jpg");
    }

    /** 网易云 CDN 的目录段：picId 与固定串逐位异或后取 MD5 的 Base64（URL 安全变体）。 */
    private static String encryptPicId(String id) {
        String magic = "3go8&$8*3*3h0k(2)2";
        StringBuilder raw = new StringBuilder(id.length());
        for (int i = 0; i < id.length(); i++) {
            raw.append((char) (magic.charAt(i % magic.length()) ^ id.charAt(i)));
        }
        try {
            byte[] digest = MessageDigest.getInstance("MD5")
                    .digest(raw.toString().getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(digest)
                    .replace('/', '_')
                    .replace('+', '-');
        } catch (Exception ex) {
            return "";
        }
    }

    private static String coverUrl(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String https = secure(raw);
        return https.contains("?") ? https : https + "?param=240y240";
    }

    /** 网易云 CDN 同时支持 http/https；统一 https，避免 Android release 的明文拦截。 */
    private static String secure(String url) {
        return url.startsWith("http://") ? "https://" + url.substring(7) : url;
    }

    private static String levelOf(JSONObject item, String fallback) {
        String level = item.getString("level");
        return level == null || level.isBlank() ? fallback : level;
    }

    /**
     * 音质 → 网易云 `br` 参数。
     *
     * `999000` 是官方说的「无损」档，`1999000` 是 Hi-Res；两者都要求账号有对应
     * 会员权益且该曲存在该档资源，否则接口会静默降到它能给的档位 ——
     * 所以下发地址后必须把响应里的 `level` 原样带回去（见 {@link #levelOf}），
     * 由客户端提示「实际拿到的是什么」，而不是假装请求成功。
     */
    private static int bitrate(String quality) {
        if (quality == null) {
            return 320000;
        }
        switch (quality) {
            case "standard":
                return 128000;
            case "high":
                return 192000;
            case "lossless":
                return 999000;
            case "hires":
                return 1999000;
            case "exhigh":
            default:
                return 320000;
        }
    }

    private static String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : (second == null ? "" : second);
    }
}