package com.ruoyi.takeout.domain;

import java.math.BigDecimal;
import java.util.List;

/** 菜品。 */
public class TakeoutDish {

    private Long dishId;
    private Long shopId;
    private String dishName;
    private String description;
    private BigDecimal price;
    private String category;
    private Integer sales;
    /** 是否有规格。列表页靠它决定点「+」弹不弹规格窗，免得为每个菜品查一次规格表。 */
    private Boolean hasSpec;
    private Integer sortNo;
    private String status;

    /** 规格组，仅店铺详情接口填充，其它查询为 null。 */
    private List<TakeoutDishSpec> specs;

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

    public Boolean getHasSpec() { return hasSpec; }
    public void setHasSpec(Boolean hasSpec) { this.hasSpec = hasSpec; }

    public Integer getSortNo() { return sortNo; }
    public void setSortNo(Integer sortNo) { this.sortNo = sortNo; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public List<TakeoutDishSpec> getSpecs() { return specs; }
    public void setSpecs(List<TakeoutDishSpec> specs) { this.specs = specs; }
}
