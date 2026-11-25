package com.ruoyi.music.domain;

/**
 * 一次播放行为的上报体。
 *
 * 客户端只在**曲目确实开始播放**后才报 `play`，在切歌 / 播完时补一条
 * `finish` 或 `skip`。后端不信任这个顺序，只按单条事件计分 ——
 * 少报一条最多是这首歌唱得不够响，不会让谁凭空上榜。
 *
 * `source = local` 的上报会被拒收：本机文件的地址只在那台设备上有效，
 * 进了榜别人点了就是一首报错的歌。
 */
public class ListenEventBody {

    /** 设备号，防刷维度的主体。缺失直接拒收。 */
    private String deviceId;

    /** netease / kugou / qq / cloud。 */
    private String source;

    /** 平台内歌曲 id：榜单点播要靠它还原成可播的曲目。 */
    private String trackId;

    private String title;

    private String artist;

    private String cover;

    private long durationMs;

    /** play / finish / skip / like。 */
    private String event;

    /** 上报时已播到第几毫秒。只用于排查误判，不参与计分。 */
    private long positionMs;

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
}
