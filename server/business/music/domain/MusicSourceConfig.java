package com.ruoyi.music.domain;

/**
 * 音源开关与账号态。对应表 {@code biz_music_source}。
 *
 * cookie 只存在服务端，接口永远不回传；客户端只看 {@code hasCookie}。
 * 这是 Mineradio「登录 Cookie 只落本机」原则在 B/S 结构下的等价做法。
 */
public class MusicSourceConfig {

    private String sourceKey;

    private String label;

    /** 1 启用，0 停用。用数字而不是布尔，避免 MySQL CHAR(1) 与 Java Boolean 的隐式转换坑。 */
    private Integer enabled = 1;

    /** 升序：数字小的优先展示、优先参与自动换源。 */
    private Integer priority = 10;

    /** 平台登录态，原样作为 Cookie 请求头的一部分使用。 */
    private String cookie;

    public boolean isOn() {
        return enabled != null && enabled == 1;
    }

    public String getSourceKey() {
        return sourceKey;
    }

    public void setSourceKey(String sourceKey) {
        this.sourceKey = sourceKey;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public Integer getEnabled() {
        return enabled;
    }

    public void setEnabled(Integer enabled) {
        this.enabled = enabled;
    }

    public Integer getPriority() {
        return priority;
    }

    public void setPriority(Integer priority) {
        this.priority = priority;
    }

    public String getCookie() {
        return cookie;
    }

    public void setCookie(String cookie) {
        this.cookie = cookie;
    }
}