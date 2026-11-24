package com.ruoyi.music.domain;

import com.alibaba.fastjson2.JSONArray;

/**
 * 生成分享码的请求体。
 *
 * {@code tracks} 用 {@link JSONArray} 而不是强类型 List：客户端提交的就是
 * `MusicTrack` 的 JSON，字段名以客户端为准，这里只做「是不是数组、多大」这类校验。
 */
public class ShareBody {

    private String deviceId;

    private String title;

    private JSONArray tracks;

    public String getDeviceId() {
        return deviceId;
    }

    public void setDeviceId(String deviceId) {
        this.deviceId = deviceId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public JSONArray getTracks() {
        return tracks;
    }

    public void setTracks(JSONArray tracks) {
        this.tracks = tracks;
    }
}
