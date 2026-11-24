package com.ruoyi.takeout.domain;

import java.util.List;

/** 订单详情：订单 + 明细 + 评价状态 + 时间轴。 */
public class OrderDetail {

    private TakeoutOrder order;
    private List<TakeoutOrderItem> items;
    /** 是否已评价，决定详情页显示「去评价」还是「已评价」。 */
    private boolean reviewed;
    /** 只包含已发生的节点，最后一项即当前状态。 */
    private List<OrderTimelineNode> timeline;

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

    public boolean isReviewed() { return reviewed; }
    public void setReviewed(boolean reviewed) { this.reviewed = reviewed; }

    public List<OrderTimelineNode> getTimeline() { return timeline; }
    public void setTimeline(List<OrderTimelineNode> timeline) { this.timeline = timeline; }
}
