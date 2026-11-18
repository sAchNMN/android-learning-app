package com.ruoyi.music.domain;

/**
 * 喜欢 / 取消喜欢的请求体。
 *
 * {@code liked} 缺省为 true —— 客户端只会发「点了红心」这一种语义，
 * 取消喜欢才显式传 false。
 */
public class LikeBody {

    private String deviceId;

    private String source;

    private String id;

    private String title;

    private String artist;

    private String album;

    private Long durationMs;

    private String cover;

    private String hash;

    private String albumId;

    private String albumAudioId;

    private Boolean liked;

    public String getDeviceId() {
        return deviceId;
    }

    public void setDeviceId(String deviceId) {
        this.deviceId = deviceId;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
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

    public String getAlbum() {
        return album;
    }

    public void setAlbum(String album) {
        this.album = album;
    }

    public Long getDurationMs() {
        return durationMs;
    }

    public void setDurationMs(Long durationMs) {
        this.durationMs = durationMs;
    }

    public String getCover() {
        return cover;
    }

    public void setCover(String cover) {
        this.cover = cover;
    }

    public Boolean getLiked() {
        return liked;
    }

    public String getHash() {
        return hash;
    }

    public void setHash(String hash) {
        this.hash = hash;
    }

    public String getAlbumId() {
        return albumId;
    }

    public void setAlbumId(String albumId) {
        this.albumId = albumId;
    }

    public String getAlbumAudioId() {
        return albumAudioId;
    }

    public void setAlbumAudioId(String albumAudioId) {
        this.albumAudioId = albumAudioId;
    }

    public void setLiked(Boolean liked) {
        this.liked = liked;
    }
}
