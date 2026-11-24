package com.ruoyi.music.domain;

/**
 * 一条歌曲评论。
 *
 * {@code songKey} 是归一化后的「歌名|主歌手」，是评论真正的归属键；
 * {@code trackKey} 只记录首次评论时用的是哪个平台的曲目，排查问题用，不参与查询。
 */
public class MusicComment {

    private Long id;

    private String songKey;

    private String trackKey;

    private String trackTitle;

    private String trackArtist;

    private String sourceKey;

    private String deviceId;

    private String deviceName;

    private String content;

    private Integer likeCount;

    private Integer status;

    private String createTime;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getSongKey() {
        return songKey;
    }

    public void setSongKey(String songKey) {
        this.songKey = songKey;
    }

    public String getTrackKey() {
        return trackKey;
    }

    public void setTrackKey(String trackKey) {
        this.trackKey = trackKey;
    }

    public String getTrackTitle() {
        return trackTitle;
    }

    public void setTrackTitle(String trackTitle) {
        this.trackTitle = trackTitle;
    }

    public String getTrackArtist() {
        return trackArtist;
    }

    public void setTrackArtist(String trackArtist) {
        this.trackArtist = trackArtist;
    }

    public String getSourceKey() {
        return sourceKey;
    }

    public void setSourceKey(String sourceKey) {
        this.sourceKey = sourceKey;
    }

    public String getDeviceId() {
        return deviceId;
    }

    public void setDeviceId(String deviceId) {
        this.deviceId = deviceId;
    }

    public String getDeviceName() {
        return deviceName;
    }

    public void setDeviceName(String deviceName) {
        this.deviceName = deviceName;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public Integer getLikeCount() {
        return likeCount;
    }

    public void setLikeCount(Integer likeCount) {
        this.likeCount = likeCount;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public String getCreateTime() {
        return createTime;
    }

    public void setCreateTime(String createTime) {
        this.createTime = createTime;
    }
}
