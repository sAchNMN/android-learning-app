package com.ruoyi.music.source;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Collections;
import java.util.Map;

/**
 * 请求级音源 Cookie。
 *
 * App 把本机保存的平台登录态随每次在线请求带上来，服务端**只用于这一次调用**，
 * 不写数据库、不写日志、不进缓存 key 的明文。这样既解决了「登录后仍按游客态取歌」，
 * 又不违背「用户凭证不该存到第三方服务器」的初衷。
 *
 * 与库里 {@code biz_music_source_config.cookie} 的关系：请求带了就用请求的，
 * 没带才回退库里的历史配置（兼容后台管理页和管理端旧流程）。
 */
public final class RequestCookies {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Map<String, String> EMPTY = Collections.emptyMap();

    private RequestCookies() {
    }

    /**
     * 解析客户端传来的 JSON 对象，形如 {@code {"netease":"music_u=xxx; __csrf=yyy"}}。
     * 空串、坏 JSON、非字符串值一律当没带 —— Cookie 解析失败不该弄崩一次搜索。
     */
    public static Map<String, String> parse(String json) {
        if (json == null || json.isBlank()) {
            return EMPTY;
        }
        try {
            Map<String, String> parsed = MAPPER.readValue(json, new TypeReference<Map<String, String>>() {
            });
            return parsed == null ? EMPTY : parsed;
        } catch (Exception ex) {
            return EMPTY;
        }
    }

    /** 请求里带了就用请求的，没带才回退库里的旧配置。 */
    public static String forSource(Map<String, String> requestCookies, String sourceKey, String fallback) {
        if (requestCookies != null) {
            String value = requestCookies.get(sourceKey);
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return fallback == null ? "" : fallback;
    }

    /**
     * 缓存 key 用的指纹。只取哈希和长度，不把 Cookie 原文拼进 key ——
     * 缓存对象会长时间留在内存里，没必要让凭证跟着活那么久。
     *
     * 这个指纹必须进 key：否则「游客态搜过一次 → 用户登录后再搜同样的词」
     * 会命中旧缓存，用户看到的是没有 VIP 结果的老列表。
     */
    public static String fingerprint(Map<String, String> requestCookies) {
        if (requestCookies == null || requestCookies.isEmpty()) {
            return "-";
        }
        StringBuilder out = new StringBuilder();
        requestCookies.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    String value = entry.getValue();
                    out.append(entry.getKey()).append('=')
                            .append(value == null ? "null" : Integer.toHexString(value.hashCode())
                                    + ":" + value.length())
                            .append(';');
                });
        return out.toString();
    }
}
