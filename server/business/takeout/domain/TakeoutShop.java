package com.ruoyi.takeout.domain;

import java.math.BigDecimal;

/** 外卖店铺。 */
public class TakeoutShop {

    private Long shopId;
    private String shopName;
    private String description;
    private String image;
    private String category;
    private Long categoryId;
    private String address;
    private String phone;
    private BigDecimal deliveryFee;
    private BigDecimal minAmount;
    private Integer deliveryTime;
    /** 营业时段，如 09:00-21:30。仅展示，不参与营业判断。 */
    private String openTime;
    private BigDecimal rating;
    /** 月售。下单时自增，近似值，不按自然月归零。 */
    private Integer monthSales;
    private String status;

    public Long getShopId() { return shopId; }
    public void setShopId(Long shopId) { this.shopId = shopId; }

    public String getShopName() { return shopName; }
    public void setShopName(String shopName) { this.shopName = shopName; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getImage() { return image; }
    public void setImage(String image) { this.image = image; }

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }

    public Long getCategoryId() { return categoryId; }
    public void setCategoryId(Long categoryId) { this.categoryId = categoryId; }

    public String getAddress() { return address; }
    public void setAddress(String address) { this.address = address; }

    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }

    public BigDecimal getDeliveryFee() { return deliveryFee; }
    public void setDeliveryFee(BigDecimal deliveryFee) { this.deliveryFee = deliveryFee; }

    public BigDecimal getMinAmount() { return minAmount; }
    public void setMinAmount(BigDecimal minAmount) { this.minAmount = minAmount; }

    public Integer getDeliveryTime() { return deliveryTime; }
    public void setDeliveryTime(Integer deliveryTime) { this.deliveryTime = deliveryTime; }

    public String getOpenTime() { return openTime; }
    public void setOpenTime(String openTime) { this.openTime = openTime; }

    public BigDecimal getRating() { return rating; }
    public void setRating(BigDecimal rating) { this.rating = rating; }

    public Integer getMonthSales() { return monthSales; }
    public void setMonthSales(Integer monthSales) { this.monthSales = monthSales; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
}
