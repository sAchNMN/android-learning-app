package com.ruoyi.music.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * 评论分页结果。
 *
 * {@code total} 是这首歌的评论总数（不随分页变化），{@code hasMore} 是给
 * 「加载更多」用的直接信号 —— 让前端自己算 `offset + size < total` 属于
 * 把服务端的实现细节泄漏出去。
 */
public class CommentPage {

    private Integer total;

    private Boolean hasMore;

    private List<CommentDto> items = new ArrayList<>();

    public Integer getTotal() {
        return total;
    }

    public void setTotal(Integer total) {
        this.total = total;
    }

    public Boolean getHasMore() {
        return hasMore;
    }

    public void setHasMore(Boolean hasMore) {
        this.hasMore = hasMore;
    }

    public List<CommentDto> getItems() {
        return items;
    }

    public void setItems(List<CommentDto> items) {
        this.items = items;
    }
}
