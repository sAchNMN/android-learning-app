package com.ruoyi.music.source;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONException;
import com.alibaba.fastjson2.JSONObject;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Map;

/**
 * 平台 HTTP 请求的最小工具。
 *
 * 只用 JDK 自带的 {@link HttpClient}，不额外引 Retrofit/OkHttp：
 * 后端已经有 fastjson2，解析和请求各用现成的就够了，没必要为两个平台加依赖。
 *
 * 所有厂商请求都强制带桌面 Chrome UA —— 移动端 UA 会拿到另一套字段名，
 * 这正是 Mineradio 里 kugou-api.js 固定 UA 的原因。
 */
final class HttpJson {

    static final String DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(6))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private HttpJson() {
    }

    static JSONObject getJson(String url, Map<String, String> headers, int timeoutSeconds) {
        HttpResult result = fetch(url, headers, timeoutSeconds, null);
        if (result.statusCode >= 400) {
            throw new SourceHttpException("平台返回 HTTP " + result.statusCode, result.statusCode);
        }
        try {
            JSONObject json = JSON.parseObject(result.body);
            if (json == null) {
                throw new SourceHttpException("平台返回空数据", result.statusCode);
            }
            return json;
        } catch (JSONException ex) {
            throw new SourceHttpException("平台返回了无法解析的数据", result.statusCode);
        }
    }

    /**
     * 表单 POST。
     *
     * 网易云的「喜欢 / 取消喜欢」是老 web 接口，只认 POST + form 编码 + csrf_token，
     * 用 GET 打会返回 404，所以单独补一个方法，而不是把 GET 硬改成万能方法。
     */
    static JSONObject postForm(String url, String body, Map<String, String> headers, int timeoutSeconds) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(Math.max(2, timeoutSeconds)))
                .header("User-Agent", DESKTOP_UA)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body == null ? "" : body, StandardCharsets.UTF_8));
        if (headers != null) {
            headers.forEach((key, value) -> {
                // Content-Type 已经设过，重复设置会被 JDK 拒绝
                if (key != null && value != null && !value.isBlank() && !"Content-Type".equalsIgnoreCase(key)) {
                    builder.header(key, value);
                }
            });
        }
        HttpResult result = send(builder.build(), url);
        if (result.statusCode >= 400) {
            throw new SourceHttpException("平台返回 HTTP " + result.statusCode, result.statusCode);
        }
        try {
            JSONObject json = JSON.parseObject(result.body);
            if (json == null) {
                throw new SourceHttpException("平台返回空数据", result.statusCode);
            }
            return json;
        } catch (JSONException ex) {
            throw new SourceHttpException("平台返回了无法解析的数据", result.statusCode);
        }
    }

    /**
     * 取一个可能 302 到 CDN 的地址，只读前两个字节确认「真的能播」。
     *
     * 网易云的 outer 链接对无版权/会员曲目会返回 404 或 HTML，
     * 所以不能只看 302，必须看最终状态码和 Content-Type。
     */
    static HttpResult probe(String url, Map<String, String> headers, int timeoutSeconds) {
        return fetch(url, headers, timeoutSeconds, "bytes=0-1");
    }

    private static HttpResult fetch(String url, Map<String, String> headers, int timeoutSeconds, String range) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(Math.max(2, timeoutSeconds)))
                .header("User-Agent", DESKTOP_UA)
                .GET();
        if (range != null) {
            builder.header("Range", range);
        }
        if (headers != null) {
            headers.forEach((key, value) -> {
                if (key != null && value != null && !value.isBlank()) {
                    builder.header(key, value);
                }
            });
        }
        return send(builder.build(), url);
    }

    private static HttpResult send(HttpRequest request, String url) {
        try {
            HttpResponse<String> response = CLIENT.send(
                    request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            HttpResult result = new HttpResult();
            result.statusCode = response.statusCode();
            result.body = response.body() == null ? "" : response.body();
            result.finalUrl = response.uri() == null ? url : response.uri().toString();
            result.contentType = response.headers().firstValue("content-type").orElse("");
            return result;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new SourceHttpException("请求被中断", 0);
        } catch (Exception ex) {
            throw new SourceHttpException("请求平台失败：" + ex.getMessage(), 0);
        }
    }

    static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    static String md5(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                out.append(String.format("%02x", b));
            }
            return out.toString();
        } catch (Exception ex) {
            throw new IllegalStateException("MD5 不可用", ex);
        }
    }

    /** 酷狗搜索结果的歌手/歌名里带 {@code <em>}，还会混入 HTML 实体。 */
    static String stripTags(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        return value
                .replaceAll("<[^>]+>", "")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .trim();
    }

    static final class HttpResult {
        int statusCode;
        String body = "";
        String finalUrl = "";
        String contentType = "";
    }

    /** 平台请求失败。statusCode 为 0 表示网络层错误。 */
    static final class SourceHttpException extends RuntimeException {
        final int statusCode;

        SourceHttpException(String message, int statusCode) {
            super(message);
            this.statusCode = statusCode;
        }
    }
}