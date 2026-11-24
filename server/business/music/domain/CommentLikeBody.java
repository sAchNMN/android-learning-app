package com.ruoyi.music.domain;

/**
 * 评论点赞的请求体。{@code liked} 为空按「点赞」处理（和 `/like` 的约定一致）。
 */
public class CommentLikeBody {

    private String deviceId;

    private Boolean liked;

    public String getDeviceId() {
        return deviceId;
    }

    public void setDeviceId(String deviceId) {
        this.deviceId = deviceId;
    }

    public Boolean getLiked() {
        return liked;
    }

    public void setLiked(Boolean liked) {
        this.liked = liked;
    }
}
