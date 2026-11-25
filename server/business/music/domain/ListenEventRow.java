package com.ruoyi.music.domain;

/**
 * 一条播放事件落库后的行。
 *
 * 与 [ListenEventBody] 分开：入参没有归一化后的 {@code songKey} 和权重 {@code weight}，
 * 那两个字段是服务端算出来的，客户端传什么都不该影响它们 ——
 * 尤其是 weight，让客户端决定自己值多少分，等于把榜单交出去。
 */
public class ListenEventRow {

    private String deviceId;

    private String songKey;

    private String source;

    private String trackId;

    private String title;

    private String artist;

    private String cover;

    private long durationMs;

    private String event;

    private long positionMs;

    private int weight;

    public String getDeviceId() {
        return deviceId;
    }

    public void setDeviceId(String deviceId) {
        this.deviceId = deviceId;
    }

    public String getSongKey() {
        return songKey;
    }

    public void setSongKey(String songKey) {
        this.songKey = songKey;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getTrackId() {
        return trackId;
    }

    public void setTrackId(String trackId) {
        this.trackId = trackId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getArtist() {
        return artist;
    }

    public void setArtist(String artist) {
        this.artist = artist;
    }

    public String getCover() {
        return cover;
    }

    public void setCover(String cover) {
        this.cover = cover;
    }

    public long getDurationMs() {
        return durationMs;
    }

    public void setDurationMs(long durationMs) {
        this.durationMs = durationMs;
    }

    public String getEvent() {
        return event;
    }

    public void setEvent(String event) {
        this.event = event;
    }

    public long getPositionMs() {
        return positionMs;
    }

    public void setPositionMs(long positionMs) {
        this.positionMs = positionMs;
    }

    public int getWeight() {
        return weight;
    }

    public void setWeight(int weight) {
        this.weight = weight;
    }
}
