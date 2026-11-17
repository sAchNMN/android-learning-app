package com.ruoyi.takeout.mapper;

import com.ruoyi.takeout.domain.TakeoutShop;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 店铺查询。SQL 全部显式别名，不依赖 mapUnderscoreToCamelCase 配置。
 *
 * 分页没上 PageHelper：它的 startPage() 是「拦截下一条 SQL」，
 * 与 count 查询、动态排序组合时容易错位。这里手写 limit，多一行代码但行为确定。
 */
public interface TakeoutShopMapper {

    String COLS = "shop_id as shopId, shop_name as shopName, description, image, category, "
            + "category_id as categoryId, address, phone, delivery_fee as deliveryFee, "
            + "min_amount as minAmount, delivery_time as deliveryTime, open_time as openTime, "
            + "rating, month_sales as monthSales, status";

    /** selectPage 与 countPage 共用的过滤条件，避免两处写歪。 */
    String PAGE_WHERE = " from takeout_shop where del_flag = '0' and status = '0'"
            + "<if test=\"categoryId != null\"> and category_id = #{categoryId}</if>"
            + "<if test=\"keyword != null\"> and shop_name like concat('%', #{keyword}, '%')</if>";

    @Select("select " + COLS + " from takeout_shop where status = '0' and del_flag = '0' order by shop_id")
    List<TakeoutShop> selectOpenShops();

    @Select("select " + COLS + " from takeout_shop where shop_id = #{shopId} and del_flag = '0'")
    TakeoutShop selectById(@Param("shopId") Long shopId);

    /**
     * 分页查询。
     *
     * [sort] 已由 Service 白名单校验过，这里只负责映射成 ORDER BY，
     * 不做参数拼接，注入无从谈起。
     */
    @Select("<script>select " + COLS + PAGE_WHERE
            + " order by <choose>"
            + "<when test=\"'rating'.equals(sort)\">rating desc, month_sales desc</when>"
            + "<when test=\"'fast'.equals(sort)\">delivery_time asc, rating desc</when>"
            + "<when test=\"'cheap'.equals(sort)\">min_amount asc, rating desc</when>"
            + "<otherwise>month_sales desc, rating desc</otherwise>"
            + "</choose>, shop_id asc limit #{offset}, #{pageSize}</script>")
    List<TakeoutShop> selectPage(@Param("categoryId") Long categoryId,
                                 @Param("keyword") String keyword,
                                 @Param("sort") String sort,
                                 @Param("offset") int offset,
                                 @Param("pageSize") int pageSize);

    @Select("<script>select count(1)" + PAGE_WHERE + "</script>")
    int countPage(@Param("categoryId") Long categoryId, @Param("keyword") String keyword);
}
