package com.ruoyi.takeout.domain;

import java.util.List;

/** 下单请求体。 */
public class OrderCreateBody {

    private Long shopId;
    private List<Item> items;
    private String receiver;
    private String phone;
    private String address;
    private String remark;

    public Long getShopId() { return shopId; }
    public void setShopId(Long shopId) { this.shopId = shopId; }

    public List<Item> getItems() { return items; }
    public void setItems(List<Item> items) { this.items = items; }

    public String getReceiver() { return receiver; }
    public void setReceiver(String receiver) { this.receiver = receiver; }

    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }

    public String getAddress() { return address; }
    public void setAddress(String address) { this.address = address; }

    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }

    /** 购物车里的一项。 */
    public static class Item {
        private Long dishId;
        private Integer quantity;

        public Long getDishId() { return dishId; }
        public void setDishId(Long dishId) { this.dishId = dishId; }

        public Integer getQuantity() { return quantity; }
        public void setQuantity(Integer quantity) { this.quantity = quantity; }
    }
}
