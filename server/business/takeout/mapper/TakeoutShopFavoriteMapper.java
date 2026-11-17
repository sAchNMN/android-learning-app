package com.ruoyi.takeout.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 店铺收藏。
 *
 * 收藏与取消收藏都要幂等：重复收藏返回受影响行数 0，不报错。
 */
public interface TakeoutShopFavoriteMapper {

    @Select("select count(1) from takeout_shop_favorite where user_id = #{userId} and shop_id = #{shopId}")
    int count(@Param("userId") Long userId, @Param("shopId") Long shopId);

    /** 已收藏时插入会撞唯一键，用 ignore 让重复收藏直接成功。 */
    @Insert("insert ignore into takeout_shop_favorite(user_id, shop_id, create_time) "
            + "values(#{userId}, #{shopId}, now())")
    int insert(@Param("userId") Long userId, @Param("shopId") Long shopId);

    @Delete("delete from takeout_shop_favorite where user_id = #{userId} and shop_id = #{shopId}")
    int delete(@Param("userId") Long userId, @Param("shopId") Long shopId);
}
