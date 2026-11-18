package com.ruoyi.music.source;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.music.domain.OnlinePlay;
import com.ruoyi.music.domain.OnlineTrack;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 酷我音乐适配器。
 *
 * 用的是两条不需要 csrf token 的公开接口，避开了 {@code www.kuwo.cn/api/www/...}
 * 那套「先拿 kw_token 再做 csrf 头」的流程：
 *
 * - 搜索 {@code search.kuwo.cn/r.s}（注意返回的是 **JS 对象字面量**，单引号 + 裸 key，
 *        不是标准 JSON，必须过 {@link #jsToJson} 才能解析）
 * - 取址 {@code antiserver.kuwo.cn/anti.s?type=convert_url&response=url}
 *        直接回一行 CDN 地址，实测匿名也能拿到绝大多数曲目，是几个平台里匿名可播率最高的
 * - 歌词 {@code m.kuwo.cn/newh5/singles/songinfoandlrc}
 *
 * 酷我的 rid 形如 {@code MUSIC_228908}，接口既能吃完整形式也能吃纯数字；
 * 这里统一存完整 rid（和搜索结果一致，客户端回传时不用改）。
 */
@Component
public class KuwoSourceProvider implements MusicSourceProvider {

    private static final String SEARCH_URL = "http://search.kuwo.cn/r.s";
    private static final String PLAY_URL = "https://antiserver.kuwo.cn/anti.s";
    private static final String LYRIC_URL = "https://m.kuwo.cn/newh5/singles/songinfoandlrc";
    private static final String COVER = "https://img4.kuwo.cn/star/albumcover/";

    /**
     * 酷我桌面 API 只接受移动 Safari UA 的歌词请求。
     * 换成桌面 Chrome UA 会稳定返回 {@code {"data":null,"status":301,"msg":"音乐查询失败"}}，
     * 安卓 Chrome UA 也一样 —— 实测只有这一种 UA 能过。
     *
     * 注意：这个接口对同一出口 IP 有频控，短时间内请求多了会连正确的 UA 也开始回 301。
     * 拿不到就返回空串（App 显示「暂无歌词」），不重试、不报错 —— 歌词缺失不影响播放。
     */
    private static final String MOBILE_UA =
            "Mozilla/5.0 (iPhone; CPU iPhone OS 16_0 like Mac OS X) AppleWebKit/605.1.15 "
                    + "(KHTML, like Gecko) Version/16.0 Mobile/15E148 Safari/604.1";

    @Override
    public String key() {
        return "kuwo";
    }

    @Override
    public String label() {
        return "酷我音乐";
    }

    @Override
    public List<OnlineTrack> search(String keyword, int limit, String cookie) {
        int size = Math.max(1, Math.min(limit, 30));
        String url = SEARCH_URL + "?all=" + HttpJson.encode(keyword)
                + "&ft=music&itemset=web_2013&client=kt&pn=0&rn=" + size
                + "&rformat=json&encoding=utf8&mobi=1&vipver=1";
        String raw = HttpJson.getText(url, headers(cookie, "http://www.kuwo.cn/"), 8);
        JSONObject json = parseFlexible(raw);
        if (json == null) {
            return new ArrayList<>();
        }
        JSONArray list = json.getJSONArray("abslist");
        if (list == null) {
            return new ArrayList<>();
        }

        List<OnlineTrack> tracks = new ArrayList<>();
        for (int i = 0; i < list.size() && tracks.size() < size; i++) {
            JSONObject item = list.getJSONObject(i);
            if (item == null) {
                continue;
            }
            String rid = first(item, "MUSICRID", "DC_TARGETID");
            String title = clean(first(item, "SONGNAME", "NAME"));
            if (rid.isBlank() || title.isBlank()) {
                continue;
            }
            if (!rid.startsWith("MUSIC_")) {
                rid = "MUSIC_" + rid.replaceAll("[^0-9]", "");
            }
            OnlineTrack track = new OnlineTrack();
            track.setSource(key());
            track.setId(rid);
            track.setHash(rid);
            track.setTitle(title);
            track.setArtist(clean(first(item, "ARTIST")));
            track.setAlbum(clean(first(item, "ALBUM")));
            track.setAlbumId(first(item, "ALBUMID"));
            track.setDurationMs(Math.max(0, item.getLongValue("DURATION")) * 1000);
            track.setCoverUrl(coverPath(first(item, "web_albumpic_short", "web_artistpic_short", "ARTISTPIC")));
            // FORMATS 为空基本等于「已下架」，这类直接标成不可播，省一次取址请求
            track.setPlayable(!first(item, "FORMATS").isBlank());
            tracks.add(track);
        }
        return tracks;
    }

    @Override
    public OnlinePlay resolve(OnlineTrack track, String cookie, String quality) {
        String rid = ridOf(track);
        if (rid.isBlank()) {
            return OnlinePlay.fail(key(), track.getId(), "url_unavailable", "缺少酷我 rid");
        }
        // 优先要 mp3：ExoPlayer 对 aac 容器偶尔要额外解析，mp3 兼容性最好
        for (String format : new String[]{"mp3", "aac"}) {
            try {
                String url = PLAY_URL + "?type=convert_url&rid=" + HttpJson.encode(rid)
                        + "&format=" + format + "&response=url";
                String body = HttpJson.getText(url, headers(cookie, "https://www.kuwo.cn/"), 8).trim();
                if (isPlayableUrl(body)) {
                    return OnlinePlay.ok(key(), track.getId(), body, qualityOf(format));
                }
            } catch (RuntimeException ignored) {
                // 换下一种格式再试
            }
        }
        return OnlinePlay.fail(key(), track.getId(), "vip_required",
                "酷我没有返回可播地址，这首歌可能需要会员");
    }

    @Override
    public String lyric(OnlineTrack track, String cookie) {
        String rid = ridOf(track);
        String musicId = rid.replaceAll("[^0-9]", "");
        if (musicId.isBlank()) {
            return "";
        }
        try {
            String url = LYRIC_URL + "?musicId=" + HttpJson.encode(musicId);
            Map<String, String> headers = headers(cookie, "https://m.kuwo.cn/");
            headers.put("User-Agent", MOBILE_UA);
            JSONObject json = HttpJson.getJson(url, headers, 8);
            JSONObject data = json.getJSONObject("data");
            JSONArray lines = data == null ? null : data.getJSONArray("lrclist");
            if (lines == null || lines.isEmpty()) {
                return "";
            }
            StringBuilder lrc = new StringBuilder();
            for (int i = 0; i < lines.size(); i++) {
                JSONObject line = lines.getJSONObject(i);
                if (line == null) {
                    continue;
                }
                String text = nullToEmpty(line.getString("lineLyric")).trim();
                String time = nullToEmpty(line.getString("time")).trim();
                if (time.isBlank()) {
                    continue;
                }
                lrc.append('[').append(formatTime(time)).append(']').append(text).append('\n');
            }
            return lrc.toString().trim();
        } catch (RuntimeException ex) {
            return "";
        }
    }

    // ---------------------------------------------------------------- 内部工具

    private Map<String, String> headers(String cookie, String referer) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Referer", referer);
        headers.put("Accept", "application/json, text/plain, */*");
        if (cookie != null && !cookie.isBlank()) {
            headers.put("Cookie", cookie.trim());
        }
        return headers;
    }

    /**
     * 酷我两种返回格式都可能出现，取决于参数：
     *
     * - 带 {@code mobi=1&vipver=1} → **标准 JSON**（双引号 + 带引号 key），直接解析
     * - 不带 → JS 对象字面量（单引号 + 裸 key），要过 {@link #jsToJson}
     *
     * 必须先试标准 JSON 再降级。反过来做过的后果：
     * 状态机把歌名里的单引号（如 Don't）当成字符串起始符，整段结构错位，
     * fastjson 只会甩一句看不出所以然的 "syntax error : f"。
     */
    private static JSONObject parseFlexible(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String text = raw.trim();
        if (text.startsWith("{")) {
            try {
                JSONObject direct = JSON.parseObject(text);
                if (direct != null) {
                    return direct;
                }
            } catch (RuntimeException ignored) {
                // 落下去走 JS 字面量转换
            }
        }
        try {
            return JSON.parseObject(jsToJson(text));
        } catch (RuntimeException ex) {
            throw new IllegalStateException("酷我返回了无法解析的数据", ex);
        }
    }

    /**
     * 酷我 r.s 返回 {@code {'TOTAL':'10','abslist':[{'NAME':'晴天'}]}}：
     * 字符串用单引号、key 不加引号，标准 JSON 解析器直接报错。
     *
     * 必须用状态机而不是正则替换。踩过的坑：直接
     * {@code replaceAll("([{,])\\s*(\\w+)\\s*:")} 会把字符串**内部**的内容也改掉 ——
     * 酷我的 MINFO 字段长这样 {@code 'level:p,bitrate:320,format:mp3'}，
     * 里头的 {@code ,bitrate:} 被补成 {@code ,"bitrate":} 后引号就错配了，
     * fastjson 抛的是一句莫名其妙的 "syntax error : f"。
     */
    static String jsToJson(String raw) {
        if (raw == null || raw.isBlank()) {
            return "{}";
        }
        int n = raw.length();
        StringBuilder out = new StringBuilder(n + 64);
        boolean expectKey = false;
        int i = 0;
        while (i < n) {
            char c = raw.charAt(i);
            if (c == '\'') {
                out.append('"');
                i++;
                while (i < n) {
                    char d = raw.charAt(i);
                    if (d == '\\' && i + 1 < n) {
                        char next = raw.charAt(i + 1);
                        if (next == '\'') {
                            out.append('\'');
                        } else {
                            out.append(d).append(next);
                        }
                        i += 2;
                        continue;
                    }
                    if (d == '\'') {
                        out.append('"');
                        i++;
                        break;
                    }
                    // 字符串里出现双引号必须转义，否则拼接出来的 JSON 会提前闭合
                    if (d == '"') {
                        out.append("\\\"");
                    } else {
                        out.append(d);
                    }
                    i++;
                }
                expectKey = false;
                continue;
            }
            if (c == '{' || c == ',') {
                out.append(c);
                expectKey = true;
                i++;
                continue;
            }
            if (expectKey) {
                if (Character.isWhitespace(c)) {
                    out.append(c);
                    i++;
                    continue;
                }
                if (Character.isLetter(c) || c == '_' || c == '$') {
                    int start = i;
                    while (i < n) {
                        char d = raw.charAt(i);
                        if (!(Character.isLetterOrDigit(d) || d == '_' || d == '$')) {
                            break;
                        }
                        i++;
                    }
                    String ident = raw.substring(start, i);
                    int look = i;
                    while (look < n && Character.isWhitespace(raw.charAt(look))) {
                        look++;
                    }
                    if (look < n && raw.charAt(look) == ':') {
                        out.append('"').append(ident).append('"');
                        i = look;
                    } else {
                        out.append(ident);
                    }
                    expectKey = false;
                    continue;
                }
                expectKey = false;
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    private static String ridOf(OnlineTrack track) {
        String rid = firstNonBlank(track.getHash(), track.getId());
        if (rid.isBlank()) {
            return "";
        }
        if (rid.startsWith("MUSIC_")) {
            return rid;
        }
        String digits = rid.replaceAll("[^0-9]", "");
        return digits.isBlank() ? "" : "MUSIC_" + digits;
    }

    /** anti.s 成功时只回一行 http(s) 地址；失败会回 "no copyright" / 空串 / 一层 HTML。 */
    private static boolean isPlayableUrl(String body) {
        return body != null
                && body.startsWith("http")
                && !body.contains("<")
                && body.length() > 20;
    }

    private static String qualityOf(String format) {
        return "mp3".equals(format) ? "exhigh" : "standard";
    }

    /** 酷我的 time 是秒（浮点），LRC 要 mm:ss.xx。 */
    private static String formatTime(String seconds) {
        double value;
        try {
            value = Double.parseDouble(seconds);
        } catch (NumberFormatException ex) {
            return "00:00.00";
        }
        long total = (long) (value * 100);
        long minutes = total / 6000;
        long secs = (total % 6000) / 100;
        long centis = total % 100;
        return String.format("%02d:%02d.%02d", minutes, secs, centis);
    }

    private static String coverPath(String shortPath) {
        if (shortPath == null || shortPath.isBlank()) {
            return "";
        }
        String path = shortPath.trim();
        if (path.startsWith("http")) {
            return path;
        }
        return COVER + path.replaceFirst("^/+", "");
    }

    /** 酷我用 &nbsp; 占位空格，还夹 &amp; 之类的实体，个别字段还会带字面的 \u0026。 */
    private static String clean(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        return raw.replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("\\u0026", "&")
                .replaceAll("\\s+", " ")
                .trim();
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

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
