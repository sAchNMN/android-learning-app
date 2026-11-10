package com.ruoyi.takeout.domain;

import java.math.BigDecimal;

/** 订单明细。下单时对菜名和单价做快照，避免菜品改价影响历史订单。 */
public class TakeoutOrderItem {

    private Long itemId;
    private Long orderId;
    private Long dishId;
    private String dishName;
    private BigDecimal price;
    private Integer quantity;
    private BigDecimal subtotal;

    public Long getItemId() { return itemId; }
    public void setItemId(Long itemId) { this.itemId = itemId; }

    public Long getOrderId() { return orderId; }
    public void setOrderId(Long orderId) { this.orderId = orderId; }

    public Long getDishId() { return dishId; }
    public void setDishId(Long dishId) { this.dishId = dishId; }

    public String getDishName() { return dishName; }
    public void setDishName(String dishName) { this.dishName = dishName; }

    public BigDecimal getPrice() { return price; }
    public void setPrice(BigDecimal price) { this.price = price; }

    public Integer getQuantity() { return quantity; }
    public void setQuantity(Integer quantity) { this.quantity = quantity; }

    public BigDecimal getSubtotal() { return subtotal; }
    public void setSubtotal(BigDecimal subtotal) { this.subtotal = subtotal; }
}
