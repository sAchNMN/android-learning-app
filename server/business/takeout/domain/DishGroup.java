package com.ruoyi.takeout.domain;

import java.util.List;

/**
 * 店铺详情里的一个菜品分类分组。
 *
 * 分组顺序由 Service 按菜品顺序首次出现决定（LinkedHashMap），
 * 不用 GROUP BY：MySQL 不保证组的顺序，左右联动会跳。
 */
public class DishGroup {

    private String category;
    private List<TakeoutDish> dishes;

    public DishGroup() {
    }

    public DishGroup(String category, List<TakeoutDish> dishes) {
        this.category = category;
        this.dishes = dishes;
    }

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }

    public List<TakeoutDish> getDishes() { return dishes; }
    public void setDishes(List<TakeoutDish> dishes) { this.dishes = dishes; }
}
