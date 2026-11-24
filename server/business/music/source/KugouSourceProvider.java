package com.ruoyi.music.source;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.music.domain.OnlinePlay;
import com.ruoyi.music.domain.OnlineTrack;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * 酷狗音乐适配器。
 *
 * 是 Mineradio {@code kugou-api.js} 的精简移植，只保留「搜索 → 取址 → 歌词」三段：
 *
 * - 搜索   {@code songsearch.kugou.com/song_search_v2}（appid=1014，不需要签名）
 * - 取址   {@code m.kugou.com/app/i/getSongInfo.php}（key = md5(hash + "kgcloud")）
 *          失败再退到网页版 {@code wwwapi.kugou.com/play/songinfo}，参数用 H5 签名
 * - 歌词   {@code krcs.kugou.com} 的 search + download
 *
 * 签名盐和 appid 与 Mineradio 保持一致。这类常量属于平台前端公开参数，
 * 不是用户凭证；真正的用户态仍然只来自音源管理里填的 Cookie。
 */
@Component
public class KugouSourceProvider implements MusicSourceProvider {

    private static final String SEARCH_URL = "https://songsearch.kugou.com/song_search_v2";
    private static final String PLAY_MOBILE = "https://m.kugou.com/app/i/getSongInfo.php";
    private static final String PLAY_WEB = "https://wwwapi.kugou.com/play/songinfo";
    private static final String LYRIC_SEARCH = "https://krcs.kugou.com/search";
    private static final String LYRIC_DOWNLOAD = "https://krcs.kugou.com/download";

    private static final String APPID = "1014";
    private static final String H5_SRC_APPID = "2919";
    private static final String H5_CLIENTVER = "20000";
    private static final String H5_SALT = "NVPh5oo715z5DIWAeQlhMDsWXXQV4hwt";

    /** 本机固定 mid，避免每次搜索都被平台当成新设备。用户 Cookie 里带了就用用户的。 */
    private final String processMid = HttpJson.md5("aurora-android-" + UUID.randomUUID());

    @Override
    public String key() {
        return "kugou";
    }

    @Override
    public String label() {
        return "酷狗音乐";
    }

    @Override
    public List<OnlineTrack> search(String keyword, int limit, String cookie) {
        int size = Math.max(1, Math.min(limit, 20));
        Auth auth = auth(cookie);
        String url = SEARCH_URL + "?keyword=" + HttpJson.encode(keyword)
                + "&page=1&pagesize=" + size
                + "&userid=" + (auth.userId.isBlank() ? "-1" : auth.userId)
                + "&clientver=2000&platform=WebFilter&tag=em&filter=2&iscorrection=1"
                + "&privilege_filter=0&filter_ver=2&appid=" + APPID
                + "&token=" + HttpJson.encode(auth.token)
                + "&mid=" + HttpJson.encode(auth.mid);
        JSONObject json = HttpJson.getJson(url, headers(cookie, "https://www.kugou.com/"), 8);
        if (json.getIntValue("status") != 1) {
            throw new IllegalStateException("酷狗搜索服务暂不可用");
        }
        JSONObject data = json.getJSONObject("data");
        JSONArray lists = data == null ? null : data.getJSONArray("lists");
        if (lists == null) {
            return new ArrayList<>();
        }

        List<OnlineTrack> tracks = new ArrayList<>();
        for (int i = 0; i < lists.size() && tracks.size() < size; i++) {
            JSONObject item = lists.getJSONObject(i);
            if (item == null) {
                continue;
            }
            String hash = first(item, "FileHash", "fileHash", "hash");
            String title = HttpJson.stripTags(first(item, "SongName", "songname", "FileName"));
            if (title.isBlank() || hash.isBlank()) {
                continue;
            }
            OnlineTrack track = new OnlineTrack();
            track.setSource(key());
            track.setId(hash);
            track.setHash(hash);
            track.setTitle(title);
            track.setArtist(HttpJson.stripTags(first(item, "SingerName", "singername")));
            track.setAlbum(HttpJson.stripTags(first(item, "AlbumName", "albumname")));
            track.setAlbumId(first(item, "AlbumID", "album_id"));
            track.setAlbumAudioId(first(item, "MixSongID", "mixsongid", "EMixSongID", "AlbumAudioID"));
            track.setDurationMs(Math.max(0, item.getLongValue("Duration")) * 1000);
            track.setCoverUrl(cover(first(item, "Image", "AlbumImage", "img")));
            int privilege = item.getIntValue("Privilege");
            track.setPlayable(privilege <= 8);
            tracks.add(track);
        }
        return tracks;
    }

    @Override
    public OnlinePlay resolve(OnlineTrack track, String cookie, String quality) {
        Auth auth = auth(cookie);
        String hash = firstNonBlank(track.getHash(), track.getId());
        String albumId = firstNonBlank(track.getAlbumId(), "0");

        OnlinePlay mobile = playViaMobile(track, hash, albumId, auth, cookie);
        if (Boolean.TRUE.equals(mobile.getPlayable())) {
            return mobile;
        }
        OnlinePlay web = playViaWeb(track, hash, albumId, auth, cookie);
        if (Boolean.TRUE.equals(web.getPlayable())) {
            return web;
        }
        // 两个入口都失败时，优先把「需要登录/会员」这种可行动的原因给客户端。
        if (mobile.getCategory() != null && !"url_unavailable".equals(mobile.getCategory())) {
            return mobile;
        }
        return web;
    }

    private OnlinePlay playViaMobile(OnlineTrack track, String hash, String albumId, Auth auth, String cookie) {
        try {
            String key = HttpJson.md5(hash + "kgcloud");
            StringBuilder url = new StringBuilder(PLAY_MOBILE)
                    .append("?cmd=playInfo&hash=").append(HttpJson.encode(hash))
                    .append("&key=").append(key)
                    .append("&album_id=").append(HttpJson.encode(albumId))
                    .append("&pid=1&forceDown=0")
                    .append("&vip=").append(auth.token.isBlank() ? "65530" : "1");
            if (!auth.userId.isBlank()) {
                url.append("&userid=").append(HttpJson.encode(auth.userId));
            }
            if (!auth.token.isBlank()) {
                url.append("&token=").append(HttpJson.encode(auth.token));
            }
            JSONObject json = HttpJson.getJson(url.toString(), headers(cookie, "https://m.kugou.com/"), 8);
            String playUrl = pickUrl(json);
            if (json.getIntValue("status") == 1 && !playUrl.isBlank()) {
                OnlinePlay play = OnlinePlay.ok(key(), track.getId(), playUrl, "standard");
                play.setTrial(isTrial(json));
                return play;
            }
            return failure(track, json);
        } catch (RuntimeException ex) {
            return OnlinePlay.fail(key(), track.getId(), "url_unavailable", "酷狗取址失败：" + ex.getMessage());
        }
    }

    private OnlinePlay playViaWeb(OnlineTrack track, String hash, String albumId, Auth auth, String cookie) {
        try {
            long now = System.currentTimeMillis();
            Map<String, String> params = new LinkedHashMap<>();
            params.put("srcappid", H5_SRC_APPID);
            params.put("clientver", H5_CLIENTVER);
            params.put("clienttime", String.valueOf(now));
            params.put("mid", auth.mid);
            params.put("uuid", auth.mid);
            params.put("dfid", auth.dfid);
            params.put("appid", APPID);
            params.put("token", auth.token);
            params.put("userid", auth.userId.isBlank() ? "0" : auth.userId);
            params.put("platid", "4");
            params.put("hash", hash);
            params.put("album_id", albumId);
            if (track.getAlbumAudioId() != null && !track.getAlbumAudioId().isBlank()) {
                params.put("album_audio_id", track.getAlbumAudioId());
            }
            params.put("signature", h5Signature(params));
            StringBuilder url = new StringBuilder(PLAY_WEB).append('?');
            boolean first = true;
            for (Map.Entry<String, String> entry : params.entrySet()) {
                if (!first) {
                    url.append('&');
                }
                first = false;
                url.append(entry.getKey()).append('=').append(HttpJson.encode(entry.getValue()));
            }
            JSONObject json = HttpJson.getJson(url.toString(), headers(cookie, "https://www.kugou.com/"), 8);
            JSONObject data = json.getJSONObject("data");
            String playUrl = pickUrl(json);
            if (json.getIntValue("status") == 1 && !playUrl.isBlank()) {
                int bitrate = data == null ? 0 : data.getIntValue("bitrate");
                String level = bitrate >= 900000 ? "lossless" : (bitrate >= 300000 ? "exhigh" : "standard");
                OnlinePlay play = OnlinePlay.ok(key(), track.getId(), playUrl, level);
                play.setTrial(isTrial(json));
                return play;
            }
            return failure(track, json);
        } catch (RuntimeException ex) {
            return OnlinePlay.fail(key(), track.getId(), "url_unavailable", "酷狗网页取址失败：" + ex.getMessage());
        }
    }

    private OnlinePlay failure(OnlineTrack track, JSONObject json) {
        int code = json.getIntValue("err_code");
        if (code == 0) {
            code = json.getIntValue("error_code");
        }
        String message = first(json, "error", "errmsg", "msg", "message");
        if (code == 30020) {
            return OnlinePlay.fail(key(), track.getId(), "login_required", "酷狗需要安全验证，请更新音源 Cookie");
        }
        if (code == 30022) {
            return OnlinePlay.fail(key(), track.getId(), "copyright_unavailable", "该酷狗歌曲仅支持官方客户端播放");
        }
        if (message.matches(".*(登录|token|login|会话|凭证).*")) {
            return OnlinePlay.fail(key(), track.getId(), "login_required", "酷狗登录态失效，请在音源管理里更新 Cookie");
        }
        if (message.matches(".*(付费|会员|vip|购买).*")) {
            return OnlinePlay.fail(key(), track.getId(), "vip_required", "酷狗这首歌需要会员或购买权限");
        }
        return OnlinePlay.fail(key(), track.getId(), "url_unavailable", "酷狗没有返回播放地址");
    }

    @Override
    public String lyric(OnlineTrack track, String cookie) {
        String hash = firstNonBlank(track.getHash(), track.getId());
        if (hash.isBlank()) {
            return "";
        }
        try {
            long duration = track.getDurationMs() == null ? 0 : track.getDurationMs() / 1000;
            String searchUrl = LYRIC_SEARCH + "?ver=1&man=yes&client=mobi&keyword=&duration=" + duration
                    + "&hash=" + HttpJson.encode(hash);
            JSONObject json = HttpJson.getJson(searchUrl, headers(cookie, "https://www.kugou.com/"), 8);
            JSONArray candidates = json.getJSONArray("candidates");
            if (candidates == null || candidates.isEmpty()) {
                return "";
            }
            JSONObject candidate = candidates.getJSONObject(0);
            String id = candidate.getString("id");
            String accessKey = candidate.getString("accesskey");
            String downloadUrl = LYRIC_DOWNLOAD + "?ver=1&client=pc&id=" + HttpJson.encode(id)
                    + "&accesskey=" + HttpJson.encode(accessKey) + "&fmt=lrc&charset=utf8";
            JSONObject download = HttpJson.getJson(downloadUrl, headers(cookie, "https://www.kugou.com/"), 8);
            String content = download.getString("content");
            if (content == null || content.isBlank()) {
                return "";
            }
            return new String(Base64.getDecoder().decode(content), StandardCharsets.UTF_8);
        } catch (RuntimeException ex) {
            return "";
        }
    }

    // ---------------------------------------------------------------- 内部工具

    private Map<String, String> headers(String cookie, String referer) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Referer", referer);
        headers.put("Accept", "application/json, text/plain, */*");
        headers.put("Cookie", requestCookie(cookie));
        return headers;
    }

    private Auth auth(String cookie) {
        Map<String, String> jar = parseCookie(cookie);
        Auth auth = new Auth();
        auth.mid = firstNonBlank(jar.get("kg_mid"), jar.get("KG_MID"), processMid);
        auth.dfid = firstNonBlank(jar.get("kg_dfid"), jar.get("KG_DFID"), "-");
        auth.token = firstNonBlank(jar.get("token"), "");
        auth.userId = firstNonBlank(jar.get("userid"), jar.get("user_id"), "");
        return auth;
    }

    private String requestCookie(String cookie) {
        Map<String, String> jar = parseCookie(cookie);
        Auth auth = auth(cookie);
        StringBuilder out = new StringBuilder();
        if (cookie != null && !cookie.isBlank()) {
            out.append(cookie.trim());
        }
        if (!jar.containsKey("kg_mid") && !jar.containsKey("KG_MID")) {
            appendCookie(out, "kg_mid", auth.mid);
        }
        if (!jar.containsKey("kg_dfid") && !jar.containsKey("KG_DFID")) {
            appendCookie(out, "kg_dfid", auth.dfid);
        }
        return out.toString();
    }

    private static void appendCookie(StringBuilder out, String key, String value) {
        if (out.length() > 0 && out.charAt(out.length() - 1) != ';') {
            out.append(';');
        }
        out.append(' ').append(key).append('=').append(value);
    }

    private static Map<String, String> parseCookie(String cookie) {
        Map<String, String> jar = new LinkedHashMap<>();
        if (cookie == null) {
            return jar;
        }
        for (String part : cookie.split(";")) {
            int index = part.indexOf('=');
            if (index <= 0) {
                continue;
            }
            jar.put(part.substring(0, index).trim(), part.substring(index + 1).trim());
        }
        return jar;
    }

    private static String h5Signature(Map<String, String> params) {
        TreeMap<String, String> sorted = new TreeMap<>(params);
        StringBuilder joined = new StringBuilder();
        sorted.forEach((key, value) -> joined.append(key).append('=').append(value));
        return HttpJson.md5(H5_SALT + joined + H5_SALT);
    }

    private static String pickUrl(JSONObject json) {
        String url = first(json, "url", "play_url", "backupUrl", "backup_url");
        if (url.isBlank()) {
            JSONObject data = json.getJSONObject("data");
            if (data != null) {
                url = first(data, "url", "play_url", "backupUrl", "backup_url", "play_backup_url");
            }
        }
        return url.replace("\\/", "/").trim();
    }

    private static boolean isTrial(JSONObject json) {
        JSONObject data = json.getJSONObject("data");
        return truthy(json, "is_free_part", "trial", "is_trial") || (data != null && truthy(data, "is_free_part", "trial"));
    }

    private static boolean truthy(JSONObject json, String... keys) {
        for (String key : keys) {
            Object value = json.get(key);
            if (value instanceof Boolean && (Boolean) value) {
                return true;
            }
            if (value instanceof Number && ((Number) value).intValue() > 0) {
                return true;
            }
            if (value instanceof String && ("1".equals(value) || "true".equalsIgnoreCase((String) value))) {
                return true;
            }
        }
        return false;
    }

    private static String cover(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String url = raw.replace("{size}", "240").trim();
        return url.startsWith("http://") ? "https://" + url.substring(7) : url;
    }

    private static String first(JSONObject json, String... keys) {
        for (String key : keys) {
            String value = json.getString(key);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private static final class Auth {
        String mid = "";
        String dfid = "-";
        String token = "";
        String userId = "";
    }
}