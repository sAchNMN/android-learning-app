package com.ruoyi.takeout.domain;

import java.math.BigDecimal;

/** 订单。status: 0待支付 1已支付 2配送中 3已完成 4已取消 */
public class TakeoutOrder {

    public static final String STATUS_UNPAID = "0";
    public static final String STATUS_PAID = "1";
    public static final String STATUS_DELIVERING = "2";
    public static final String STATUS_DONE = "3";
    public static final String STATUS_CANCELLED = "4";

    private Long orderId;
    private String orderNo;
    private Long userId;
    private Long shopId;
    private String shopName;
    /** 收货地址簿 id，可能为 null（老订单）。 */
    private Long addressId;
    private BigDecimal totalAmount;
    private BigDecimal deliveryFee;
    private BigDecimal payAmount;
    /** 商品总件数冗余，列表页展示用，下单时写一次。 */
    private Integer itemCount;
    private String status;
    private String receiver;
    private String phone;
    private String address;
    /** 期望送达时间，yyyy-MM-dd HH:mm:ss。 */
    private String expectTime;
    private String remark;
    private String payTime;
    private String finishTime;
    private String createTime;

    public Long getOrderId() { return orderId; }
    public void setOrderId(Long orderId) { this.orderId = orderId; }

    public String getOrderNo() { return orderNo; }
    public void setOrderNo(String orderNo) { this.orderNo = orderNo; }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

    public Long getShopId() { return shopId; }
    public void setShopId(Long shopId) { this.shopId = shopId; }

    public String getShopName() { return shopName; }
    public void setShopName(String shopName) { this.shopName = shopName; }

    public Long getAddressId() { return addressId; }
    public void setAddressId(Long addressId) { this.addressId = addressId; }

    public Integer getItemCount() { return itemCount; }
    public void setItemCount(Integer itemCount) { this.itemCount = itemCount; }

    public String getExpectTime() { return expectTime; }
    public void setExpectTime(String expectTime) { this.expectTime = expectTime; }

    public BigDecimal getTotalAmount() { return totalAmount; }
    public void setTotalAmount(BigDecimal totalAmount) { this.totalAmount = totalAmount; }

    public BigDecimal getDeliveryFee() { return deliveryFee; }
    public void setDeliveryFee(BigDecimal deliveryFee) { this.deliveryFee = deliveryFee; }

    public BigDecimal getPayAmount() { return payAmount; }
    public void setPayAmount(BigDecimal payAmount) { this.payAmount = payAmount; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getReceiver() { return receiver; }
    public void setReceiver(String receiver) { this.receiver = receiver; }

    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }

    public String getAddress() { return address; }
    public void setAddress(String address) { this.address = address; }

    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }

    public String getPayTime() { return payTime; }
    public void setPayTime(String payTime) { this.payTime = payTime; }

    public String getFinishTime() { return finishTime; }
    public void setFinishTime(String finishTime) { this.finishTime = finishTime; }

    public String getCreateTime() { return createTime; }
    public void setCreateTime(String createTime) { this.createTime = createTime; }
}
