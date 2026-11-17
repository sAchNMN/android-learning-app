package com.ruoyi.takeout.mapper;

import com.ruoyi.takeout.domain.TakeoutShop;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 店铺收藏。
 *
 * 收藏与取消收藏都要幂等：重复收藏返回受影响行数 0，不报错。
 */
public interface TakeoutShopFavoriteMapper {

    /** 列表要带店铺信息，直接 join，避免「先查 id 再批量查店铺」两跳。 */
    String SHOP_COLS = "s.shop_id as shopId, s.shop_name as shopName, s.description, s.image, "
            + "s.category, s.category_id as categoryId, s.address, s.phone, "
            + "s.delivery_fee as deliveryFee, s.min_amount as minAmount, "
            + "s.delivery_time as deliveryTime, s.open_time as openTime, s.rating, "
            + "s.month_sales as monthSales, s.status";

    @Select("select count(1) from takeout_shop_favorite where user_id = #{userId} and shop_id = #{shopId}")
    int count(@Param("userId") Long userId, @Param("shopId") Long shopId);

    /** 已收藏时插入会撞唯一键，用 ignore 让重复收藏直接成功。 */
    @Insert("insert ignore into takeout_shop_favorite(user_id, shop_id, create_time) "
            + "values(#{userId}, #{shopId}, now())")
    int insert(@Param("userId") Long userId, @Param("shopId") Long shopId);

    @Delete("delete from takeout_shop_favorite where user_id = #{userId} and shop_id = #{shopId}")
    int delete(@Param("userId") Long userId, @Param("shopId") Long shopId);

    /** 收藏的店铺，按收藏时间倒序。店铺被删除的自动被 join 过滤掉。 */
    @Select("select " + SHOP_COLS + " from takeout_shop_favorite f "
            + "join takeout_shop s on f.shop_id = s.shop_id "
            + "where f.user_id = #{userId} and s.del_flag = '0' "
            + "order by f.create_time desc, f.fav_id desc limit #{offset}, #{pageSize}")
    List<TakeoutShop> selectShopPage(@Param("userId") Long userId,
                                     @Param("offset") int offset,
                                     @Param("pageSize") int pageSize);

    @Select("select count(1) from takeout_shop_favorite f "
            + "join takeout_shop s on f.shop_id = s.shop_id "
            + "where f.user_id = #{userId} and s.del_flag = '0'")
    int countShops(@Param("userId") Long userId);
}
