package com.ruoyi.takeout.domain;

import java.util.List;

/** 订单详情：订单 + 明细。 */
public class OrderDetail {

    private TakeoutOrder order;
    private List<TakeoutOrderItem> items;

    public OrderDetail() {
    }

    public OrderDetail(TakeoutOrder order, List<TakeoutOrderItem> items) {
        this.order = order;
        this.items = items;
    }

    public TakeoutOrder getOrder() { return order; }
    public void setOrder(TakeoutOrder order) { this.order = order; }

    public List<TakeoutOrderItem> getItems() { return items; }
    public void setItems(List<TakeoutOrderItem> items) { this.items = items; }
}
