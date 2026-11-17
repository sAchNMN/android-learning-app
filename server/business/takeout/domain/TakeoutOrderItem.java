package com.ruoyi.takeout.domain;

import java.math.BigDecimal;

/** 订单明细。下单时对菜名和单价做快照，避免菜品改价影响历史订单。 */
public class TakeoutOrderItem {

    private Long itemId;
    private Long orderId;
    private Long dishId;
    private String dishName;
    /** 规格快照，如「大份/微辣」。菜品改规格不影响历史订单。 */
    private String specText;
    /** 选项 id 逗号串，给「再来一单」还原规格用，不展示。 */
    private String optionIds;
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

    public String getSpecText() { return specText; }
    public void setSpecText(String specText) { this.specText = specText; }

    public String getOptionIds() { return optionIds; }
    public void setOptionIds(String optionIds) { this.optionIds = optionIds; }

    public BigDecimal getPrice() { return price; }
    public void setPrice(BigDecimal price) { this.price = price; }

    public Integer getQuantity() { return quantity; }
    public void setQuantity(Integer quantity) { this.quantity = quantity; }

    public BigDecimal getSubtotal() { return subtotal; }
    public void setSubtotal(BigDecimal subtotal) { this.subtotal = subtotal; }
}
