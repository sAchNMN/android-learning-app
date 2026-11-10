package com.ruoyi.takeout.domain;

import java.util.List;

/** 店铺详情：店铺信息 + 在售菜品。 */
public class ShopDetail {

    private TakeoutShop shop;
    private List<TakeoutDish> dishes;

    public ShopDetail() {
    }

    public ShopDetail(TakeoutShop shop, List<TakeoutDish> dishes) {
        this.shop = shop;
        this.dishes = dishes;
    }

    public TakeoutShop getShop() { return shop; }
    public void setShop(TakeoutShop shop) { this.shop = shop; }

    public List<TakeoutDish> getDishes() { return dishes; }
    public void setDishes(List<TakeoutDish> dishes) { this.dishes = dishes; }
}
