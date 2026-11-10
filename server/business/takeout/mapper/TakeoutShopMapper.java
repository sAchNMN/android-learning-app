package com.ruoyi.takeout.mapper;

import com.ruoyi.takeout.domain.TakeoutShop;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** 店铺查询。SQL 全部显式别名，不依赖 mapUnderscoreToCamelCase 配置。 */
public interface TakeoutShopMapper {

    String COLS = "shop_id as shopId, shop_name as shopName, description, category, address, phone, "
            + "delivery_fee as deliveryFee, min_amount as minAmount, delivery_time as deliveryTime, "
            + "rating, status";

    @Select("select " + COLS + " from takeout_shop where status = '0' and del_flag = '0' order by shop_id")
    List<TakeoutShop> selectOpenShops();

    @Select("select " + COLS + " from takeout_shop where shop_id = #{shopId} and del_flag = '0'")
    TakeoutShop selectById(@Param("shopId") Long shopId);
}
