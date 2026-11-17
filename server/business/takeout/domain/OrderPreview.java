package com.ruoyi.takeout.domain;

import java.math.BigDecimal;
import java.util.List;

/**
 * 结算预览（D2 进入时调用）。
 *
 * 所有金额与校验结果都在这里给出，客户端只负责展示：
 * 价格、起送差额、配送费任何一项都不允许客户端自己算。
 */
public class OrderPreview {

    private Long shopId;
    private String shopName;
    private List<PreviewItem> items;
    private BigDecimal totalAmount;
    private BigDecimal deliveryFee;
    private BigDecimal payAmount;
    /** 店铺起送价。 */
    private BigDecimal minAmount;
    /** 还差多少起送，0 表示已达。 > 0 时仍然返回 200，让结算页能提示。 */
    private BigDecimal shortOfMin;
    /** 未传 addressId 时为默认地址，用户没有地址则为 null。 */
    private TakeoutAddress defaultAddress;
    /** 配送时间档位，第一项固定为「尽快送达」。 */
    private List<ExpectOption> expectOptions;

    public Long getShopId() { return shopId; }
    public void setShopId(Long shopId) { this.shopId = shopId; }

    public String getShopName() { return shopName; }
    public void setShopName(String shopName) { this.shopName = shopName; }

    public List<PreviewItem> getItems() { return items; }
    public void setItems(List<PreviewItem> items) { this.items = items; }

    public BigDecimal getTotalAmount() { return totalAmount; }
    public void setTotalAmount(BigDecimal totalAmount) { this.totalAmount = totalAmount; }

    public BigDecimal getDeliveryFee() { return deliveryFee; }
    public void setDeliveryFee(BigDecimal deliveryFee) { this.deliveryFee = deliveryFee; }

    public BigDecimal getPayAmount() { return payAmount; }
    public void setPayAmount(BigDecimal payAmount) { this.payAmount = payAmount; }

    public BigDecimal getMinAmount() { return minAmount; }
    public void setMinAmount(BigDecimal minAmount) { this.minAmount = minAmount; }

    public BigDecimal getShortOfMin() { return shortOfMin; }
    public void setShortOfMin(BigDecimal shortOfMin) { this.shortOfMin = shortOfMin; }

    public TakeoutAddress getDefaultAddress() { return defaultAddress; }
    public void setDefaultAddress(TakeoutAddress defaultAddress) { this.defaultAddress = defaultAddress; }

    public List<ExpectOption> getExpectOptions() { return expectOptions; }
    public void setExpectOptions(List<ExpectOption> expectOptions) { this.expectOptions = expectOptions; }

    /** 预览里的一行。单价已含规格加价。 */
    public static class PreviewItem {
        private Long dishId;
        private String dishName;
        private String specText;
        private BigDecimal price;
        private Integer quantity;
        private BigDecimal subtotal;

        public PreviewItem() {
        }

        public PreviewItem(Long dishId, String dishName, String specText,
                           BigDecimal price, Integer quantity, BigDecimal subtotal) {
            this.dishId = dishId;
            this.dishName = dishName;
            this.specText = specText;
            this.price = price;
            this.quantity = quantity;
            this.subtotal = subtotal;
        }

        public Long getDishId() { return dishId; }
        public void setDishId(Long dishId) { this.dishId = dishId; }

        public String getDishName() { return dishName; }
        public void setDishName(String dishName) { this.dishName = dishName; }

        public String getSpecText() { return specText; }
        public void setSpecText(String specText) { this.specText = specText; }

        public BigDecimal getPrice() { return price; }
        public void setPrice(BigDecimal price) { this.price = price; }

        public Integer getQuantity() { return quantity; }
        public void setQuantity(Integer quantity) { this.quantity = quantity; }

        public BigDecimal getSubtotal() { return subtotal; }
        public void setSubtotal(BigDecimal subtotal) { this.subtotal = subtotal; }
    }

    /** 配送时间档位。value 回传给 create 的 expectTime。 */
    public static class ExpectOption {
        private String value;
        private String label;
        private String expectTime;

        public ExpectOption() {
        }

        public ExpectOption(String value, String label, String expectTime) {
            this.value = value;
            this.label = label;
            this.expectTime = expectTime;
        }

        public String getValue() { return value; }
        public void setValue(String value) { this.value = value; }

        public String getLabel() { return label; }
        public void setLabel(String label) { this.label = label; }

        public String getExpectTime() { return expectTime; }
        public void setExpectTime(String expectTime) { this.expectTime = expectTime; }
    }
}
