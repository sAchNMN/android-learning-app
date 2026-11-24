package com.ruoyi.music.source;

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
 * QQ 音乐适配器。
 *
 * 三个接口都是网页版在用的老接口，不需要 weapi 签名：
 * - 搜索 {@code c.y.qq.com/soso/fcgi-bin/client_search_cp}（必须带 Referer: y.qq.com）
 * - 取址 {@code u.y.qq.com/cgi-bin/musicu.fcg}，module=vkey.GetVkeyServer，
 *        返回的 purl 拼 {@code https://dl.stream.qqmusic.qq.com/} 才是完整地址
 * - 歌词 {@code c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg}，Referer 必须是
 *        {@code y.qq.com/portal/player.html}，否则回 403
 *
 * 匿名态的限制是真实存在的：purl 为空 + result=104003 就是「这首歌要会员/要买」，
 * 这里如实翻译成 vip_required，不假装能播。免费歌（payplay=0）实测能拿到 purl，
 * 且 CDN 支持 Range，ExoPlayer 可以直接流式拉。
 *
 * 用户填了 Cookie（含 uin / qm_keyst）时会把 uin 带进取址请求，
 * 这是平台自己的登录态，服务端不做任何绕过。
 */
@Component
public class QqSourceProvider implements MusicSourceProvider {

    private static final String SEARCH_URL = "https://c.y.qq.com/soso/fcgi-bin/client_search_cp";
    private static final String VKEY_URL = "https://u.y.qq.com/cgi-bin/musicu.fcg";
    private static final String LYRIC_URL = "https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg";
    private static final String CDN = "https://dl.stream.qqmusic.qq.com/";
    private static final String COVER = "https://y.gtimg.cn/music/photo_new/T002R240x240M000";

    /** 取址要一个设备 guid，固定成常量而不是每次随机，免得平台当成异常设备。 */
    private static final String GUID = "3982123456";

    @Override
    public String key() {
        return "qq";
    }

    @Override
    public String label() {
        return "QQ音乐";
    }

    @Override
    public List<OnlineTrack> search(String keyword, int limit, String cookie) {
        int size = Math.max(1, Math.min(limit, 30));
        // 不要加 new_json=1：加上之后 albummid/albumname 会变成 album.mid / album.name
        // 的嵌套结构，singer 也换一套字段，这里按老扁平结构解析，字段更稳
        String url = SEARCH_URL + "?w=" + HttpJson.encode(keyword)
                + "&n=" + size + "&p=1&format=json&t=0";
        JSONObject json = HttpJson.getJson(url, headers(cookie, "https://y.qq.com/"), 8);
        JSONObject data = json.getJSONObject("data");
        JSONObject song = data == null ? null : data.getJSONObject("song");
        JSONArray list = song == null ? null : song.getJSONArray("list");
        if (list == null) {
            return new ArrayList<>();
        }

        List<OnlineTrack> tracks = new ArrayList<>();
        for (int i = 0; i < list.size() && tracks.size() < size; i++) {
            JSONObject item = list.getJSONObject(i);
            if (item == null) {
                continue;
            }
            String mid = HttpJson.stripTags(first(item, "songmid", "mid"));
            String title = HttpJson.stripTags(first(item, "songname", "title", "name"));
            if (mid.isBlank() || title.isBlank()) {
                continue;
            }
            String albumMid = HttpJson.stripTags(first(item, "albummid"));
            OnlineTrack track = new OnlineTrack();
            track.setSource(key());
            track.setId(mid);
            track.setTitle(title);
            track.setArtist(joinSingers(item));
            track.setAlbum(HttpJson.stripTags(first(item, "albumname")));
            track.setAlbumId(albumMid);
            track.setDurationMs(Math.max(0, item.getLongValue("interval")) * 1000);
            track.setCoverUrl(albumMid.isBlank() ? "" : COVER + albumMid + ".jpg");
            track.setPlayable(isFree(item));
            tracks.add(track);
        }
        return tracks;
    }

    @Override
    public OnlinePlay resolve(OnlineTrack track, String cookie, String quality) {
        String mid = HttpJson.stripTags(track.getId());
        if (mid.isBlank()) {
            return OnlinePlay.fail(key(), track.getId(), "url_unavailable", "缺少 QQ 音乐 songmid");
        }
        String uin = cookieValue(cookie, "uin", "wxuin", "p_uin");
        String loginUin = uin.isBlank() ? "0" : uin;
        try {
            String payload = "{\"req_0\":{\"module\":\"vkey.GetVkeyServer\",\"method\":\"CgiGetVkey\","
                    + "\"param\":{\"guid\":\"" + GUID + "\",\"songmid\":[\"" + mid + "\"],"
                    + "\"songtype\":[0],\"uin\":\"" + loginUin + "\",\"loginUin\":\"" + loginUin + "\","
                    + "\"hostUin\":0,\"platform\":\"yqq.json\",\"needNewCode\":0}}}";
            String url = VKEY_URL + "?data=" + HttpJson.encode(payload) + "&format=json";
            JSONObject json = HttpJson.getJson(url, headers(cookie, "https://y.qq.com/"), 8);
            JSONObject req = json.getJSONObject("req_0");
            JSONObject data = req == null ? null : req.getJSONObject("data");
            JSONArray infos = data == null ? null : data.getJSONArray("midurlinfo");
            JSONObject info = infos == null || infos.isEmpty() ? null : infos.getJSONObject(0);
            String purl = info == null ? "" : nullToEmpty(info.getString("purl")).trim();
            if (!purl.isBlank()) {
                String playUrl = purl.startsWith("http") ? purl : CDN + purl;
                String level = purl.contains(".flac") ? "lossless"
                        : (purl.contains("M800") || purl.contains("F000") ? "exhigh" : "standard");
                return OnlinePlay.ok(key(), track.getId(), playUrl, level);
            }
            return failure(track, info);
        } catch (RuntimeException ex) {
            return OnlinePlay.fail(key(), track.getId(), "url_unavailable",
                    "QQ音乐取址失败：" + ex.getMessage());
        }
    }

    /**
     * purl 为空时按 result 码给原因。
     * 104003 是「无版权 / 需要会员」，实测周杰伦这类全量 VIP 曲目都会落到这里。
     */
    private OnlinePlay failure(OnlineTrack track, JSONObject info) {
        int result = info == null ? 0 : info.getIntValue("result");
        String tips = info == null ? "" : nullToEmpty(info.getString("tips"));
        if (result == 104003) {
            return OnlinePlay.fail(key(), track.getId(), "vip_required",
                    tips.isBlank() ? "这首歌在 QQ音乐 需要会员，可在音源管理里填入自己的 Cookie" : tips);
        }
        if (result == 104004 || result == 104005) {
            return OnlinePlay.fail(key(), track.getId(), "copyright_unavailable", "QQ音乐 未上架这首歌");
        }
        if (result == 104001) {
            return OnlinePlay.fail(key(), track.getId(), "login_required", "QQ音乐 登录态失效，请更新 Cookie");
        }
        return OnlinePlay.fail(key(), track.getId(), "url_unavailable", "QQ音乐 没有返回播放地址");
    }

    @Override
    public String lyric(OnlineTrack track, String cookie) {
        String mid = HttpJson.stripTags(track.getId());
        if (mid.isBlank()) {
            return "";
        }
        try {
            String url = LYRIC_URL + "?songmid=" + HttpJson.encode(mid)
                    + "&format=json&nobase64=1&g_tk=5381";
            // Referer 必须是播放页，用首页会回 403
            JSONObject json = HttpJson.getJson(url, headers(cookie, "https://y.qq.com/portal/player.html"), 8);
            String lyric = json.getString("lyric");
            return lyric == null ? "" : lyric.trim();
        } catch (RuntimeException ex) {
            return "";
        }
    }

    // ---------------------------------------------------------------- 内部工具

    private Map<String, String> headers(String cookie, String referer) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Referer", referer);
        headers.put("Accept", "application/json, text/plain, */*");
        headers.put("Cookie", cookie == null ? "" : cookie.trim());
        return headers;
    }

    /** pay.payplay == 0 才是匿名可播；缺字段时按「可播」乐观处理，真实结果以取址为准。 */
    private static boolean isFree(JSONObject item) {
        JSONObject pay = item.getJSONObject("pay");
        if (pay == null) {
            return true;
        }
        return pay.getIntValue("payplay") == 0;
    }

    private static String joinSingers(JSONObject item) {
        JSONArray singers = item.getJSONArray("singer");
        if (singers == null) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < singers.size(); i++) {
            JSONObject singer = singers.getJSONObject(i);
            String name = singer == null ? "" : HttpJson.stripTags(singer.getString("name"));
            if (name.isBlank()) {
                continue;
            }
            if (out.length() > 0) {
                out.append(" / ");
            }
            out.append(name);
        }
        return out.toString();
    }

    private static String cookieValue(String cookie, String... keys) {
        if (cookie == null || cookie.isBlank()) {
            return "";
        }
        Map<String, String> jar = new LinkedHashMap<>();
        for (String part : cookie.split(";")) {
            int index = part.indexOf('=');
            if (index > 0) {
                jar.put(part.substring(0, index).trim(), part.substring(index + 1).trim());
            }
        }
        for (String key : keys) {
            String value = jar.get(key);
            if (value != null && !value.isBlank()) {
                // QQ 的 uin 在 Cookie 里是 o0123456789 这种带前缀的形式，取数字部分
                String digits = value.replaceAll("[^0-9]", "");
                return digits.isBlank() ? value : digits;
            }
        }
        return "";
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

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
