package com.ruoyi.takeout.controller;

import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.takeout.service.TakeoutShopService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 首页聚合（T1）。 */
@RestController
@RequestMapping("/app/home")
public class AppHomeController {

    @Autowired
    private TakeoutShopService shopService;

    /** 分类 + 推荐店铺 + 热搜词，一次返回。 */
    @GetMapping("/index")
    public AjaxResult index() {
        return AjaxResult.success(shopService.homeIndex());
    }
}
