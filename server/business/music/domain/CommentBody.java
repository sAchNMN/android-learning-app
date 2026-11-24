package com.ruoyi.music.domain;

/**
 * 发表评论的请求体。
 *
 * 客户端传**原始**的歌名和歌手，归一化（去标点、去括号后缀、取主歌手）由服务端做。
 * 两端各写一套正则的话，迟早在全角括号或 `feat.` 上对不齐，然后同一首歌出现两批评论。
 */
public class CommentBody {

    private String deviceId;

    /** 昵称，可空；空则由前端显示「匿名听众」。 */
    private String deviceName;

    private String title;

    private String artist;

    /** netease / kugou / kuwo / qq / cloud / local。 */
    private String sourceKey;

    /** 平台曲目 id，只用于排查，不参与查询。 */
    private String trackId;

    private String content;

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

    public String getSourceKey() {
        return sourceKey;
    }

    public void setSourceKey(String sourceKey) {
        this.sourceKey = sourceKey;
    }

    public String getTrackId() {
        return trackId;
    }

    public void setTrackId(String trackId) {
        this.trackId = trackId;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }
}
