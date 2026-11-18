package com.ruoyi.music.domain;

import java.util.ArrayList;
import java.util.List;

/** 歌单详情：点开同步回来的歌单时拉一次。 */
public class PlaylistDetail {

    private String source;

    private String id;

    private String name;

    private String coverUrl;

    private List<OnlineTrack> tracks = new ArrayList<>();

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

    public List<OnlineTrack> getTracks() {
        return tracks;
    }

    public void setTracks(List<OnlineTrack> tracks) {
        this.tracks = tracks;
    }
}
