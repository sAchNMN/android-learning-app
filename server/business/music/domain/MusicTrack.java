package com.ruoyi.music.domain;

/**
 * 云端曲目。
 *
 * 只存元信息 + 相对路径，不存绝对地址：换域名、挂 CDN 都不用动库里的数据。
 * `filePath` 指向若依 profile 目录下的文件，由 `/profile/**` 静态映射对外提供。
 */
public class MusicTrack {

    private Long id;

    private String title;

    private String artist;

    private String album;

    /** 毫秒。客户端全程用毫秒，单位只在入库时换算一次。 */
    private Long durationMs;

    /** 相对路径，例如 /profile/music/01.mp3，客户端按 baseUrl 拼绝对地址。 */
    private String filePath;

    private String cover;

    private Integer sortNo;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
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

    public String getFilePath() {
        return filePath;
    }

    public void setFilePath(String filePath) {
        this.filePath = filePath;
    }

    public String getCover() {
        return cover;
    }

    public void setCover(String cover) {
        this.cover = cover;
    }

    public Integer getSortNo() {
        return sortNo;
    }

    public void setSortNo(Integer sortNo) {
        this.sortNo = sortNo;
    }
}
