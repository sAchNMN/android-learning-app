package com.ruoyi.music.domain;

/**
 * 评论列表项（对外结构）。
 *
 * **不回传 deviceId**：那是可以拿来冒充别人点赞/删评论的凭证。
 * 客户端只需要知道「是不是我自己发的」（{@code own}）。
 */
public class CommentDto {

    private Long id;

    /** 空串由前端显示为「匿名听众」。 */
    private String deviceName;

    private String content;

    private Integer likeCount;

    /** 当前 deviceId 是否赞过这条。 */
    private Boolean liked;

    /** 是不是当前 deviceId 发的，决定长按能不能删。 */
    private Boolean own;

    private String createTime;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
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

    public Boolean getLiked() {
        return liked;
    }

    public void setLiked(Boolean liked) {
        this.liked = liked;
    }

    public Boolean getOwn() {
        return own;
    }

    public void setOwn(Boolean own) {
        this.own = own;
    }

    public String getCreateTime() {
        return createTime;
    }

    public void setCreateTime(String createTime) {
        this.createTime = createTime;
    }
}
