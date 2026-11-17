package com.ruoyi.takeout.domain;

import java.util.List;

/**
 * 首页聚合数据（T1）。
 *
 * 一次请求把首屏要的三块数据都带上，避免打开首页打三枪。
 */
public class HomeIndex {

    private List<TakeoutShopCategory> categories;
    /** 推荐店铺，按评分取前几家。 */
    private List<TakeoutShop> shops;
    private List<String> hotKeywords;

    public List<TakeoutShopCategory> getCategories() { return categories; }
    public void setCategories(List<TakeoutShopCategory> categories) { this.categories = categories; }

    public List<TakeoutShop> getShops() { return shops; }
    public void setShops(List<TakeoutShop> shops) { this.shops = shops; }

    public List<String> getHotKeywords() { return hotKeywords; }
    public void setHotKeywords(List<String> hotKeywords) { this.hotKeywords = hotKeywords; }
}
