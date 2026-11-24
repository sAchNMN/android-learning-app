package com.ruoyi.takeout.domain;

import java.util.List;

/**
 * 店铺详情（D1）。
 *
 * P2 起把 dishes 换成 groups：客户端左右联动需要稳定的分类顺序，
 * 平铺列表自己分组既慢又容易出错。
 */
public class ShopDetail {

    private TakeoutShop shop;
    /** 当前用户是否收藏了该店。 */
    private boolean favorited;
    private List<DishGroup> groups;

    public ShopDetail() {
    }

    public ShopDetail(TakeoutShop shop, boolean favorited, List<DishGroup> groups) {
        this.shop = shop;
        this.favorited = favorited;
        this.groups = groups;
    }

    public TakeoutShop getShop() { return shop; }
    public void setShop(TakeoutShop shop) { this.shop = shop; }

    public boolean isFavorited() { return favorited; }
    public void setFavorited(boolean favorited) { this.favorited = favorited; }

    public List<DishGroup> getGroups() { return groups; }
    public void setGroups(List<DishGroup> groups) { this.groups = groups; }
}
