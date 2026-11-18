package com.ruoyi.music.domain;

/**
 * 音源账号里的一个歌单。
 *
 * 只带「列表卡片需要」的字段，点进去再单独拉曲目（{@code /app/music/playlist}），
 * 否则一次同步会把几十个歌单的全部曲目都拖回来。
 */
public class UserPlaylist {

    private String source;

    private String id;

    private String name;

    private String coverUrl;

    private Integer trackCount;

    /** 是不是自己建的（false = 收藏的别人的歌单）。 */
    private Boolean own;

    public UserPlaylist() {
    }

    public UserPlaylist(String source, String id, String name, String coverUrl, Integer trackCount, Boolean own) {
        this.source = source;
        this.id = id;
        this.name = name;
        this.coverUrl = coverUrl;
        this.trackCount = trackCount;
        this.own = own;
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

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getCoverUrl() {
        return coverUrl;
    }

    public void setCoverUrl(String coverUrl) {
        this.coverUrl = coverUrl;
    }

    public Integer getTrackCount() {
        return trackCount;
    }

    public void setTrackCount(Integer trackCount) {
        this.trackCount = trackCount;
    }

    public Boolean getOwn() {
        return own;
    }

    public void setOwn(Boolean own) {
        this.own = own;
    }
}
