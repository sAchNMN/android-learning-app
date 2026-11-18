package com.ruoyi.music.domain;

/**
 * 一次「取播放地址」的结果。
 *
 * 与 Mineradio 的 playbackRestriction 对齐：失败不是抛 500，而是
 * 200 + {@code playable=false} + {@code category}，客户端据此展示
 * 「需要登录 / 需要会员 / 版权不可播 / 自动换源中」等提示。
 *
 * category 取值：
 * - {@code login_required}        平台要求登录
 * - {@code vip_required}          需要会员
 * - {@code paid_required}         需要单曲/专辑购买
 * - {@code trial_only}            只返回试听片段
 * - {@code copyright_unavailable} 版权或地区限制
 * - {@code url_unavailable}       平台没返回地址（兜底）
 */
public class OnlinePlay {

    private String source;

    private String id;

    private String url;

    private Boolean playable = Boolean.FALSE;

    /** standard / exhigh / lossless，平台实际返回的档位。 */
    private String quality;

    private String category;

    private String message;

    /** 自动换源时：原来请求的平台。 */
    private String switchedFrom;

    /** 自动换源时：实际提供播放地址的平台。 */
    private String switchedTo;

    /** 是否只是试听片段。 */
    private Boolean trial = Boolean.FALSE;

    public static OnlinePlay ok(String source, String id, String url, String quality) {
        OnlinePlay play = new OnlinePlay();
        play.source = source;
        play.id = id;
        play.url = url;
        play.quality = quality;
        play.playable = Boolean.TRUE;
        return play;
    }

    public static OnlinePlay fail(String source, String id, String category, String message) {
        OnlinePlay play = new OnlinePlay();
        play.source = source;
        play.id = id;
        play.category = category;
        play.message = message;
        play.playable = Boolean.FALSE;
        return play;
    }

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

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public Boolean getPlayable() {
        return playable;
    }

    public void setPlayable(Boolean playable) {
        this.playable = playable;
    }

    public String getQuality() {
        return quality;
    }

    public void setQuality(String quality) {
        this.quality = quality;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getSwitchedFrom() {
        return switchedFrom;
    }

    public void setSwitchedFrom(String switchedFrom) {
        this.switchedFrom = switchedFrom;
    }

    public String getSwitchedTo() {
        return switchedTo;
    }

    public void setSwitchedTo(String switchedTo) {
        this.switchedTo = switchedTo;
    }

    public Boolean getTrial() {
        return trial;
    }

    public void setTrial(Boolean trial) {
        this.trial = trial;
    }
}