package com.ruoyi.takeout.domain;

import java.math.BigDecimal;

/**
 * 菜品搜索结果行。
 *
 * 不是 TakeoutDish 实体：搜索要带上店铺名，直接映射成专门的行对象，
 * 避免往实体里塞一个跟表结构无关的字段。
 */
public class DishSearchRow {

    private Long dishId;
    private String dishName;
    private Long shopId;
    private String shopName;
    private BigDecimal price;
    private Integer sales;
    private Boolean hasSpec;

    public Long getDishId() { return dishId; }
    public void setDishId(Long dishId) { this.dishId = dishId; }

    public String getDishName() { return dishName; }
    public void setDishName(String dishName) { this.dishName = dishName; }

    public Long getShopId() { return shopId; }
    public void setShopId(Long shopId) { this.shopId = shopId; }

    public String getShopName() { return shopName; }
    public void setShopName(String shopName) { this.shopName = shopName; }

    public BigDecimal getPrice() { return price; }
    public void setPrice(BigDecimal price) { this.price = price; }

    public Integer getSales() { return sales; }
    public void setSales(Integer sales) { this.sales = sales; }

    public Boolean getHasSpec() { return hasSpec; }
    public void setHasSpec(Boolean hasSpec) { this.hasSpec = hasSpec; }
}
