package com.ruoyi.music.domain;

/**
 * 榜单里的一行。
 *
 * 带着 {@code source + trackId} 而不是只给歌名：客户端点榜单要能直接还原出
 * 一首可播的曲目，回去再搜一遍"歌名 + 歌手"很可能搜到另一首同名翻唱。
 */
public class RankTrackDto {

    /** 名次，从 1 开始。 */
    private int rank;

    /** 归一化键，客户端用它判断「榜单里这首是不是正在播的那首」。 */
    private String songKey;

    private String title;

    private String artist;

    private String cover;

    private String source;

    private String trackId;

    private long durationMs;

    /** 热度分。热歌榜是近 24 小时得分，飙升榜是增量。 */
    private long score;

    /** 飙升榜的增量：近 24 小时得分 − 前一个 24 小时得分。热歌榜恒为 0。 */
    private long delta;

    public int getRank() {
        return rank;
    }

    public void setRank(int rank) {
        this.rank = rank;
    }

    public String getSongKey() {
        return songKey;
    }

    public void setSongKey(String songKey) {
        this.songKey = songKey;
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

    public long getDurationMs() {
        return durationMs;
    }

    public void setDurationMs(long durationMs) {
        this.durationMs = durationMs;
    }

    public long getScore() {
        return score;
    }

    public void setScore(long score) {
        this.score = score;
    }

    public long getDelta() {
        return delta;
    }

    public void setDelta(long delta) {
        this.delta = delta;
    }
}
