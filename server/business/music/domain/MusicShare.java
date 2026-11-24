package com.ruoyi.music.domain;

/**
 * 歌单分享码。
 *
 * {@code payloadJson} 是客户端提交的曲目数组**原文**，后端不解析内部字段。
 * 原因见 {@code sql/09_music_share.sql} 的注释：跨音源曲目带平台私有参数，
 * 后端一旦理解这套结构，客户端每加字段就要改两边，而这件事没有收益。
 */
public class MusicShare {

    private Long id;

    /** 6 位短码，字符集已排除 0/O/1/I/L。 */
    private String code;

    /** 生成者 deviceId。App 没有登录体系，设备号就是身份。 */
    private String ownerDevice;

    private String title;

    /** 实际存下的曲目数（可能被 {@code MAX_TRACKS} 截断）。 */
    private Integer trackCount;

    private String payloadJson;

    private Integer viewCount;

    private String createTime;

    /** 过期时间，null 表示不过期。查询 SQL 里已经过滤掉过期的行。 */
    private String expireTime;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getOwnerDevice() {
        return ownerDevice;
    }

    public void setOwnerDevice(String ownerDevice) {
        this.ownerDevice = ownerDevice;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public Integer getTrackCount() {
        return trackCount;
    }

    public void setTrackCount(Integer trackCount) {
        this.trackCount = trackCount;
    }

    public String getPayloadJson() {
        return payloadJson;
    }

    public void setPayloadJson(String payloadJson) {
        this.payloadJson = payloadJson;
    }

    public Integer getViewCount() {
        return viewCount;
    }

    public void setViewCount(Integer viewCount) {
        this.viewCount = viewCount;
    }

    public String getCreateTime() {
        return createTime;
    }

    public void setCreateTime(String createTime) {
        this.createTime = createTime;
    }

    public String getExpireTime() {
        return expireTime;
    }

    public void setExpireTime(String expireTime) {
        this.expireTime = expireTime;
    }
}
