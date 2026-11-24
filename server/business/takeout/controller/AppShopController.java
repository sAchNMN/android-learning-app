package com.ruoyi.takeout.controller;

import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.takeout.service.TakeoutShopService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * App 端店铺域接口：分类与分页列表。
 *
 * 与 AppTakeoutController 里的 /app/shop/list、/app/shop/{shopId} 并列存在。
 * /app/shop/page 与 /app/shop/{shopId} 不会冲突：Spring 的路径匹配里
 * 精确段优先于路径变量。
 */
@RestController
@RequestMapping("/app/shop")
public class AppShopController {

    @Autowired
    private TakeoutShopService shopService;

    /** 分类列表，按 sort_no 排序。 */
    @GetMapping("/category/list")
    public AjaxResult categories() {
        return AjaxResult.success(shopService.categories());
    }

    /**
     * 分页列表。
     *
     * @param categoryId 不传或 0 表示全部分类
     * @param keyword    店铺名模糊匹配
     * @param sort       default / rating / fast / cheap，其它值按 default 处理
     */
    @GetMapping("/page")
    public AjaxResult page(@RequestParam(value = "categoryId", required = false) Long categoryId,
                           @RequestParam(value = "keyword", required = false) String keyword,
                           @RequestParam(value = "sort", required = false) String sort,
                           @RequestParam(value = "pageNum", required = false) Integer pageNum,
                           @RequestParam(value = "pageSize", required = false) Integer pageSize) {
        return AjaxResult.success(
                shopService.page(categoryId, keyword, sort, pageNum, pageSize));
    }
}
