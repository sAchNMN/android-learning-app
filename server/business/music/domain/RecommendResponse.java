package com.ruoyi.music.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * 推荐结果。
 *
 * {@code basedOn} 是这次用的口味种子（歌手名），客户端把它显示成
 * 「根据 周杰伦、陈奕迅 推荐」，用户才知道为什么看到这些歌。
 */
public class RecommendResponse {

    private List<String> basedOn = new ArrayList<>();

    private List<OnlineTrack> tracks = new ArrayList<>();

    /** 口味种子的来源：favorites（收藏）/ history（最近播放）/ discover（无偏好兜底）。 */
    private String reason = "discover";

    public List<String> getBasedOn() {
        return basedOn;
    }

    public void setBasedOn(List<String> basedOn) {
        this.basedOn = basedOn;
    }

    public List<OnlineTrack> getTracks() {
        return tracks;
    }

    public void setTracks(List<OnlineTrack> tracks) {
        this.tracks = tracks;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }
}
