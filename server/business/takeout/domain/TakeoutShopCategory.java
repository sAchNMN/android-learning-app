package com.ruoyi.takeout.domain;

/** 店铺分类（首页金刚区 / 店铺列表筛选）。 */
public class TakeoutShopCategory {

    private Long categoryId;
    private String categoryName;
    private String icon;
    private Integer sortNo;

    public Long getCategoryId() { return categoryId; }
    public void setCategoryId(Long categoryId) { this.categoryId = categoryId; }

    public String getCategoryName() { return categoryName; }
    public void setCategoryName(String categoryName) { this.categoryName = categoryName; }

    public String getIcon() { return icon; }
    public void setIcon(String icon) { this.icon = icon; }

    public Integer getSortNo() { return sortNo; }
    public void setSortNo(Integer sortNo) { this.sortNo = sortNo; }
}
