package com.ruoyi.music.domain;

/**
 * 聚合查询的中间结果：一首歌在一段时间窗口内的得分。
 *
 * {@code base} 只有飙升榜才用（上一窗口的得分），热歌榜永远是 0。
 */
public class RankScore {

    private String songKey;

    private long score;

    private long base;

    public String getSongKey() {
        return songKey;
    }

    public void setSongKey(String songKey) {
        this.songKey = songKey;
    }

    public long getScore() {
        return score;
    }

    public void setScore(long score) {
        this.score = score;
    }

    public long getBase() {
        return base;
    }

    public void setBase(long base) {
        this.base = base;
    }
}
