package com.ruoyi.takeout.domain;

/** 取消订单请求体。reason 可为空，服务端给默认文案。 */
public class OrderCancelBody {

    private String reason;

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
}
