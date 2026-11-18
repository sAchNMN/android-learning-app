package com.ruoyi.music.domain;

/**
 * 音源配置更新请求。
 *
 * 三个字段都可空：只传 enabled 就是开关，只传 cookie 就是更新登录态。
 * cookie 传空串表示清除。
 */
public class SourceUpdateBody {

    private Boolean enabled;

    private Integer priority;

    private String cookie;

    public Boolean getEnabled() {
        return enabled;
    }

    public void setEnabled(Boolean enabled) {
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