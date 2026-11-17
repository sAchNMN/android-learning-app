package com.ruoyi.takeout.domain;

/**
 * 订单时间轴节点。
 *
 * 由服务端从 takeout_order 的时间戳字段拼出，只包含**已经发生**的节点，
 * 不读 takeout_order_status_log —— 那张表是审计流水，同一状态可能重复。
 */
public class OrderTimelineNode {

    private String status;
    private String label;
    private String time;

    public OrderTimelineNode() {
    }

    public OrderTimelineNode(String status, String label, String time) {
        this.status = status;
        this.label = label;
        this.time = time;
    }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }

    public String getTime() { return time; }
    public void setTime(String time) { this.time = time; }
}
