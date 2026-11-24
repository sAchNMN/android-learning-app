package com.ruoyi.takeout.domain;

import java.util.List;

/** 下单请求体。金额一律不传，服务端算。 */
public class OrderCreateBody {

    /** 客户端幂等令牌，进结算页生成一次，失败重试复用。 */
    private String clientToken;

    private Long shopId;
    private List<Item> items;

    /** 地址簿 id。不传则用默认地址。 */
    private Long addressId;

    /** 期望送达：asap 或 expectOptions 里的 value。 */
    private String expectTime;

    private String remark;

    /** 兼容旧版本客户端的明文字段，有 addressId 时以地址簿为准。 */
    private String receiver;
    private String phone;
    private String address;

    public String getClientToken() { return clientToken; }
    public void setClientToken(String clientToken) { this.clientToken = clientToken; }

    public Long getShopId() { return shopId; }
    public void setShopId(Long shopId) { this.shopId = shopId; }

    public List<Item> getItems() { return items; }
    public void setItems(List<Item> items) { this.items = items; }

    public Long getAddressId() { return addressId; }
    public void setAddressId(Long addressId) { this.addressId = addressId; }

    public String getExpectTime() { return expectTime; }
    public void setExpectTime(String expectTime) { this.expectTime = expectTime; }

    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }

    public String getReceiver() { return receiver; }
    public void setReceiver(String receiver) { this.receiver = receiver; }

    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }

    public String getAddress() { return address; }
    public void setAddress(String address) { this.address = address; }

    /** 购物车里的一项：菜品 + 数量 + 选中的规格选项。 */
    public static class Item {
        private Long dishId;
        private Integer quantity;
        private List<Long> optionIds;

        public Long getDishId() { return dishId; }
        public void setDishId(Long dishId) { this.dishId = dishId; }

        public Integer getQuantity() { return quantity; }
        public void setQuantity(Integer quantity) { this.quantity = quantity; }

        public List<Long> getOptionIds() { return optionIds; }
        public void setOptionIds(List<Long> optionIds) { this.optionIds = optionIds; }
    }
}
