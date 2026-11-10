package com.ruoyi.takeout.mapper;

import com.ruoyi.takeout.domain.TakeoutDish;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/** 菜品查询。 */
public interface TakeoutDishMapper {

    String COLS = "dish_id as dishId, shop_id as shopId, dish_name as dishName, description, "
            + "price, category, sales, status";

    @Select("select " + COLS + " from takeout_dish "
            + "where shop_id = #{shopId} and status = '0' and del_flag = '0' order by dish_id")
    List<TakeoutDish> selectByShop(@Param("shopId") Long shopId);

    @Select("select " + COLS + " from takeout_dish where dish_id = #{dishId} and del_flag = '0'")
    TakeoutDish selectById(@Param("dishId") Long dishId);

    @Update("update takeout_dish set sales = sales + #{qty} where dish_id = #{dishId}")
    int increaseSales(@Param("dishId") Long dishId, @Param("qty") int qty);
}
