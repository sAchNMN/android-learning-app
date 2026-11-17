package com.ruoyi.takeout.domain;

import java.util.Collections;
import java.util.List;

/**
 * 分页响应体。与若依后台的 TableDataInfo 字段一致（total + rows），
 * 但没有它的分页插件依赖，App 端用这个更轻。
 */
public class PageData<T> {

    private long total;
    private List<T> rows;

    public PageData() {
        this(0L, Collections.emptyList());
    }

    public PageData(long total, List<T> rows) {
        this.total = total;
        this.rows = rows;
    }

    public long getTotal() { return total; }
    public void setTotal(long total) { this.total = total; }

    public List<T> getRows() { return rows; }
    public void setRows(List<T> rows) { this.rows = rows; }
}
