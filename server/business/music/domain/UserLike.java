package com.ruoyi.music.domain;

import java.util.Date;

/**
 * 「我喜欢」的一条记录。
 *
 * 同时承担两个角色：
 * 1. 各音源喜欢状态的**本地权威副本** —— 平台同步失败时以这里为准，下一次同步再补推
 * 2. 推荐算法的口味来源 —— {@code artist / title} 会被 {@code RecommendService} 拿去当种子
 *
 * 键是 {@code (deviceId, source, trackId)}：App 没有登录体系，设备号就是用户身份，
 * 这一层在 {@code /app/music/like} 里由客户端带上。
 */
public class UserLike {

    private String deviceId;

    /** 平台标识：netease / kugou；本机收藏是 local / cloud。 */
    private String source;

    /** 平台内歌曲 id。 */
    private String id;

    private String title;

    private String artist;

    private String album;

    private Long durationMs;

    private String cover;

    /** 酷狗取址参数：只有酷狗用到，其它平台为空。没有它重装后喜欢的歌播不了。 */
    private String hash;

    private String albumId;

    private String albumAudioId;

    private Boolean liked;

    private Date updateTime;

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

    public Boolean getLiked() {
        return liked;
    }

    public void setLiked(Boolean liked) {
        this.liked = liked;
    }

    public Date getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(Date updateTime) {
        this.updateTime = updateTime;
    }
}
