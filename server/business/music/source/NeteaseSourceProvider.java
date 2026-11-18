package com.ruoyi.music.source;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.music.domain.OnlinePlay;
import com.ruoyi.music.domain.OnlineTrack;
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
            JSONObject song = songs.getJSONObject(i);
            if (song == null) {
                continue;
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
            if (track.getTitle() != null && track.getId() != null) {
                tracks.add(track);
            }
        }
        return tracks;
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
            case "exhigh":
            default:
                return 320000;
        }
    }

    private static String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : (second == null ? "" : second);
    }
}