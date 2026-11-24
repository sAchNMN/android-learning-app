package com.ruoyi.takeout.controller;

import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.takeout.service.TakeoutShopService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 搜索（L2）：热搜词 + 搜索。
 *
 * 搜索本身不需要登录态参与过滤，但仍挂在 /app/** 下统一要求登录，
 * 与其它 App 接口保持一致。
 */
@RestController
@RequestMapping("/app/search")
public class AppSearchController {

    @Autowired
    private TakeoutShopService shopService;

    @GetMapping("/hot")
    public AjaxResult hot(@RequestParam(value = "limit", required = false) Integer limit) {
        return AjaxResult.success(shopService.hotKeywords(limit == null ? 10 : limit));
    }

    /**
     * @param type shop（默认）/ dish
     */
    @GetMapping("")
    public AjaxResult search(@RequestParam("keyword") String keyword,
                             @RequestParam(value = "type", required = false) String type,
                             @RequestParam(value = "pageNum", required = false) Integer pageNum,
                             @RequestParam(value = "pageSize", required = false) Integer pageSize) {
        if ("dish".equals(type)) {
            return AjaxResult.success(shopService.searchDishes(keyword, pageNum, pageSize));
        }
        return AjaxResult.success(shopService.searchShops(keyword, pageNum, pageSize));
    }
}
