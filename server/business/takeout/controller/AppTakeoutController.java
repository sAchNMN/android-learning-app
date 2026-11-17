package com.ruoyi.takeout.controller;

import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.takeout.domain.OrderCancelBody;
import com.ruoyi.takeout.domain.OrderCreateBody;
import com.ruoyi.takeout.service.TakeoutService;
import com.ruoyi.takeout.service.TakeoutShopService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * App 端外卖接口。
 *
 * 统一挂 /app/** 前缀，与若依自带的管理端接口隔离。
 * 这些接口都要求登录（若依 SecurityConfig 默认 anyRequest().authenticated()），
 * 用户身份从 token 里取，不接受前端传 userId，避免越权查别人的订单。
 */
@RestController
@RequestMapping("/app")
public class AppTakeoutController {

    @Autowired
    private TakeoutService takeoutService;

    @Autowired
    private TakeoutShopService takeoutShopService;

    /** 营业中的店铺列表。已废弃，客户端改用 /app/shop/page。 */
    @GetMapping("/shop/list")
    public AjaxResult shopList() {
        return AjaxResult.success(takeoutShopService.listShops());
    }

    /** 店铺详情，含按分类分组的菜品、规格与收藏状态。 */
    @GetMapping("/shop/{shopId}")
    public AjaxResult shopDetail(@PathVariable("shopId") Long shopId) {
        return AjaxResult.success(
                takeoutShopService.shopDetail(shopId, SecurityUtils.getUserId()));
    }

    /** 结算预览：金额、起送差额、默认地址、配送时间档位。 */
    @PostMapping("/order/preview")
    public AjaxResult preview(@RequestBody OrderCreateBody body) {
        return AjaxResult.success(takeoutService.preview(SecurityUtils.getUserId(), body));
    }

    /** 下单。 */
    @PostMapping("/order/create")
    public AjaxResult createOrder(@RequestBody OrderCreateBody body) {
        return AjaxResult.success(takeoutService.createOrder(SecurityUtils.getUserId(), body));
    }

    /** 我的订单列表。 */
    @GetMapping("/order/list")
    public AjaxResult myOrders() {
        return AjaxResult.success(takeoutService.myOrders(SecurityUtils.getUserId()));
    }

    /** 订单详情。 */
    @GetMapping("/order/{orderId}")
    public AjaxResult orderDetail(@PathVariable("orderId") Long orderId) {
        return AjaxResult.success(takeoutService.orderDetail(orderId, SecurityUtils.getUserId()));
    }

    /** 模拟支付。 */
    @PostMapping("/order/{orderId}/pay")
    public AjaxResult pay(@PathVariable("orderId") Long orderId) {
        takeoutService.pay(orderId, SecurityUtils.getUserId());
        return AjaxResult.success("支付成功");
    }

    /** 取消订单。已支付但商家已接单的不能自助取消。 */
    @PostMapping("/order/{orderId}/cancel")
    public AjaxResult cancel(@PathVariable("orderId") Long orderId,
                             @RequestBody(required = false) OrderCancelBody body) {
        takeoutService.cancel(orderId, SecurityUtils.getUserId(),
                body == null ? null : body.getReason());
        return AjaxResult.success("已取消");
    }

    /** 确认收货。 */
    @PostMapping("/order/{orderId}/confirm")
    public AjaxResult confirm(@PathVariable("orderId") Long orderId) {
        takeoutService.confirm(orderId, SecurityUtils.getUserId());
        return AjaxResult.success("已确认收货");
    }
}
