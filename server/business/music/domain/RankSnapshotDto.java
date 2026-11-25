package com.ruoyi.music.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * 一次榜单查询的结果。
 *
 * 带上 {@code eventCount} 与 {@code windowStart}：榜单页要能说清"这是哪段时间的数据"，
 * 否则用户看到一张空榜会以为是功能坏了，看到一个数字也不知道它意味着什么。
 */
public class RankSnapshotDto {

    /** hot / rising。 */
    private String type;

    /** 统计窗口起点，`yyyy-MM-dd HH:mm`。 */
    private String windowStart;

    /** 本次查询时刻。客户端可以显示"刚刚更新"。 */
    private String updatedAt;

    /** 窗口内收到的播放事件条数。 */
    private long eventCount;

    private List<RankTrackDto> tracks = new ArrayList<>();

    /** 查不了时的原因（表没建 / 库挂了）。成功时为空。 */
    private String message;

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getWindowStart() {
        return windowStart;
    }

    public void setWindowStart(String windowStart) {
        this.windowStart = windowStart;
    }

    public String getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(String updatedAt) {
        this.updatedAt = updatedAt;
    }

    public long getEventCount() {
        return eventCount;
    }

    public void setEventCount(long eventCount) {
        this.eventCount = eventCount;
    }

    public List<RankTrackDto> getTracks() {
        return tracks;
    }

    public void setTracks(List<RankTrackDto> tracks) {
        this.tracks = tracks;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }
}
