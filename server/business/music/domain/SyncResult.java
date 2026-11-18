package com.ruoyi.music.domain;

/**
 * 单平台同步结果。
 *
 * 平台侧失败**不是**接口失败：收藏已经落在本地库里，
 * 这里只描述「平台这一步做了没有」，客户端据此提示
 * 「已收藏（网易云已同步）」或「已收藏（网易云未登录，仅本机记录）」。
 */
public class SyncResult {

    private boolean ok;

    private String source;

    private String message;

    public static SyncResult ok(String source) {
        SyncResult result = new SyncResult();
        result.ok = true;
        result.source = source;
        result.message = "已同步";
        return result;
    }

    public static SyncResult fail(String source, String message) {
        SyncResult result = new SyncResult();
        result.ok = false;
        result.source = source;
        result.message = message;
        return result;
    }

    public boolean isOk() {
        return ok;
    }

    public void setOk(boolean ok) {
        this.ok = ok;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }
}
