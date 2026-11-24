package com.ruoyi.music.domain;

/**
 * 音源管理页展示项。刻意不包含 cookie 原文，只告诉客户端「是否已配置」。
 */
public class MusicSourceDto {

    private String key;

    private String label;

    private Boolean enabled;

    private Integer priority;

    private Boolean hasCookie;

    public MusicSourceDto() {
    }

    public MusicSourceDto(String key, String label, boolean enabled, int priority, boolean hasCookie) {
        this.key = key;
        this.label = label;
        this.enabled = enabled;
        this.priority = priority;
        this.hasCookie = hasCookie;
    }

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

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

    public Boolean getHasCookie() {
        return hasCookie;
    }

    public void setHasCookie(Boolean hasCookie) {
        this.hasCookie = hasCookie;
    }
}