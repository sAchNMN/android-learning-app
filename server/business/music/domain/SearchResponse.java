package com.ruoyi.music.domain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 聚合搜索响应。
 *
 * 单个平台挂掉不能拖垮整次搜索，所以错误放在 {@code errors} 里
 * （key 是平台展示名，value 是给用户看的原因），{@code tracks} 仍返回其它平台结果。
 */
public class SearchResponse {

    private String keyword;

    private List<OnlineTrack> tracks = new ArrayList<>();

    private Map<String, String> errors = new LinkedHashMap<>();

    public String getKeyword() {
        return keyword;
    }

    public void setKeyword(String keyword) {
        this.keyword = keyword;
    }

    public List<OnlineTrack> getTracks() {
        return tracks;
    }

    public void setTracks(List<OnlineTrack> tracks) {
        this.tracks = tracks;
    }

    public Map<String, String> getErrors() {
        return errors;
    }

    public void setErrors(Map<String, String> errors) {
        this.errors = errors;
    }
}