package com.ruoyi.takeout.controller;

import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.takeout.domain.TakeoutAddress;
import com.ruoyi.takeout.service.TakeoutAddressService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;

/**
 * App 端收货地址簿（L3 地址列表 / D5 地址编辑）。
 *
 * 与 /app/shop/** 一样，用户身份只从 token 取。
 * 任何请求体里出现 userId 都视为错误，直接忽略 Service 里 set 的值来自 token。
 */
@RestController
@RequestMapping("/app/address")
public class AppAddressController {

    @Autowired
    private TakeoutAddressService addressService;

    @GetMapping("/list")
    public AjaxResult list() {
        return AjaxResult.success(addressService.list(SecurityUtils.getUserId()));
    }

    @GetMapping("/{addressId}")
    public AjaxResult detail(@PathVariable("addressId") Long addressId) {
        return AjaxResult.success(addressService.detail(addressId, SecurityUtils.getUserId()));
    }

    @PostMapping
    public AjaxResult add(@RequestBody TakeoutAddress body) {
        Long addressId = addressService.add(SecurityUtils.getUserId(), body);
        return AjaxResult.success(Collections.singletonMap("addressId", addressId));
    }

    @PutMapping
    public AjaxResult update(@RequestBody TakeoutAddress body) {
        addressService.update(SecurityUtils.getUserId(), body);
        return AjaxResult.success("修改成功");
    }

    @DeleteMapping("/{addressId}")
    public AjaxResult remove(@PathVariable("addressId") Long addressId) {
        addressService.delete(addressId, SecurityUtils.getUserId());
        return AjaxResult.success("删除成功");
    }

    @PostMapping("/{addressId}/default")
    public AjaxResult setDefault(@PathVariable("addressId") Long addressId) {
        addressService.setDefault(addressId, SecurityUtils.getUserId());
        return AjaxResult.success("设置成功");
    }
}
