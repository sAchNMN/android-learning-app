package com.ruoyi.music.domain;

/**
 * 在线音源搜索结果。
 *
 * 这是网易云、酷狗等厂商返回结果归一化后的统一模型，字段刻意只保留
 * 「客户端渲染 + 后续换源」需要的最小集合：
 *
 * - {@code source} / {@code id}：定位一首歌，播放时原样回传
 * - {@code hash} / {@code albumId} / {@code albumAudioId}：酷狗的取址参数，其它平台为空
 * - {@code playable}：平台侧是否标记为免费可播，仅作展示；真实可播性以取址结果为准
 *
 * 不把厂商原始 JSON 透给客户端：字段一多，前端就会被某一家绑架，
 * 换源逻辑也没法做到平台无关。
 */
public class OnlineTrack {

    /** 平台标识，与 MusicSourceProvider#key() 一致。 */
    private String source;

    /** 平台内歌曲 id（网易云是数字 id，酷狗是 fileHash）。 */
    private String id;

    private String title;

    private String artist;

    private String album;

    /** 毫秒，与客户端统一。 */
    private Long durationMs;

    private String coverUrl;

    /** 平台标记的可播状态：免费曲目 true，会员/付费曲目 false。 */
    private Boolean playable;

    /** 酷狗文件 hash，取播放地址必需。 */
    private String hash;

    /** 酷狗专辑 id。 */
    private String albumId;

    /** 酷狗 mixSongId / albumAudioId。 */
    private String albumAudioId;

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

    public String getCoverUrl() {
        return coverUrl;
    }

    public void setCoverUrl(String coverUrl) {
        this.coverUrl = coverUrl;
    }

    public Boolean getPlayable() {
        return playable;
    }

    public void setPlayable(Boolean playable) {
        this.playable = playable;
    }

    public String getHash() {
        return hash;
    }

    public void setHash(String hash) {
        this.hash = hash;
    }

    public String getAlbumId() {
        return albumId;
    }

    public void setAlbumId(String albumId) {
        this.albumId = albumId;
    }

    public String getAlbumAudioId() {
        return albumAudioId;
    }

    public void setAlbumAudioId(String albumAudioId) {
        this.albumAudioId = albumAudioId;
    }
}