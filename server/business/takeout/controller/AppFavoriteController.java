package com.ruoyi.takeout.controller;

import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.takeout.service.TakeoutShopService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 店铺收藏。
 *
 * 两个接口都幂等：重复收藏 / 取消未收藏的店都返回成功，
 * 客户端不需要先查状态再决定调哪个。
 */
@RestController
@RequestMapping("/app/favorite")
public class AppFavoriteController {

    @Autowired
    private TakeoutShopService shopService;

    @PostMapping("/{shopId}")
    public AjaxResult add(@PathVariable("shopId") Long shopId) {
        shopService.favorite(shopId, SecurityUtils.getUserId());
        return AjaxResult.success("收藏成功");
    }

    @DeleteMapping("/{shopId}")
    public AjaxResult remove(@PathVariable("shopId") Long shopId) {
        shopService.unfavorite(shopId, SecurityUtils.getUserId());
        return AjaxResult.success("已取消收藏");
    }
}
