package com.ruoyi.music.domain;

/**
 * 评论列表的数据库行。比 {@link CommentDto} 多两个字段：
 *
 * - {@code deviceId}：Service 用它算 {@code own}，之后**不会**放进响应
 * - {@code likedFlag}：`0/1`，MyBatis 从 `case when` 里拿到的原始值。
 *   直接用 Boolean 接收在部分 JDBC 驱动上会把 `0` 也当成 true，多一层转换更保险
 */
public class CommentRow {

    private Long id;

    private String deviceId;

    private String deviceName;

    private String content;

    private Integer likeCount;

    private String createTime;

    private Integer likedFlag;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getDeviceId() {
        return deviceId;
    }

    public void setDeviceId(String deviceId) {
        this.deviceId = deviceId;
    }

    public String getDeviceName() {
        return deviceName;
    }

    public void setDeviceName(String deviceName) {
        this.deviceName = deviceName;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public Integer getLikeCount() {
        return likeCount;
    }

    public void setLikeCount(Integer likeCount) {
        this.likeCount = likeCount;
    }

    public String getCreateTime() {
        return createTime;
    }

    public void setCreateTime(String createTime) {
        this.createTime = createTime;
    }

    public Integer getLikedFlag() {
        return likedFlag;
    }

    public void setLikedFlag(Integer likedFlag) {
        this.likedFlag = likedFlag;
    }
}
