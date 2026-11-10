package com.ruoyi.takeout.domain;

import java.math.BigDecimal;

/** 菜品。 */
public class TakeoutDish {

    private Long dishId;
    private Long shopId;
    private String dishName;
    private String description;
    private BigDecimal price;
    private String category;
    private Integer sales;
    private String status;

    public Long getDishId() { return dishId; }
    public void setDishId(Long dishId) { this.dishId = dishId; }

    public Long getShopId() { return shopId; }
    public void setShopId(Long shopId) { this.shopId = shopId; }

    public String getDishName() { return dishName; }
    public void setDishName(String dishName) { this.dishName = dishName; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public BigDecimal getPrice() { return price; }
    public void setPrice(BigDecimal price) { this.price = price; }

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }

    public Integer getSales() { return sales; }
    public void setSales(Integer sales) { this.sales = sales; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
}
