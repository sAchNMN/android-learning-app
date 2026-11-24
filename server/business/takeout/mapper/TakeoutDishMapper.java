package com.ruoyi.takeout.mapper;

import com.ruoyi.takeout.domain.DishSearchRow;
import com.ruoyi.takeout.domain.TakeoutDish;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 菜品查询。
 *
 * has_spec 在库里是 char(1)，SQL 里直接转成 0/1 让 MyBatis 映射成 Boolean，
 * 避免客户端拿到字符串 "1" 还要自己判断。
 */
public interface TakeoutDishMapper {

    String COLS = "dish_id as dishId, shop_id as shopId, dish_name as dishName, description, "
            + "price, category, sales, (has_spec = '1') as hasSpec, sort_no as sortNo, status";

    /** 店铺详情用：sort_no 决定组内顺序，分类之间的先后按组内最小值。 */
    @Select("select " + COLS + " from takeout_dish "
            + "where shop_id = #{shopId} and status = '0' and del_flag = '0' "
            + "order by sort_no, dish_id")
    List<TakeoutDish> selectByShop(@Param("shopId") Long shopId);

    @Select("select " + COLS + " from takeout_dish where dish_id = #{dishId} and del_flag = '0'")
    TakeoutDish selectById(@Param("dishId") Long dishId);

    /** 下单校验：一次查全，缺哪个菜品由调用方比对数量得出。 */
    @Select("<script>select " + COLS + " from takeout_dish "
            + "where del_flag = '0' and status = '0' and dish_id in "
            + "<foreach collection='dishIds' item='id' open='(' separator=',' close=')'>#{id}</foreach>"
            + "</script>")
    List<TakeoutDish> selectByIds(@Param("dishIds") List<Long> dishIds);

    @Update("update takeout_dish set sales = sales + #{qty} where dish_id = #{dishId}")
    int increaseSales(@Param("dishId") Long dishId, @Param("qty") int qty);

    /** 取消订单时扣回销量，用 greatest 兜底，别让销量变负数。 */
    @Update("update takeout_dish set sales = greatest(0, sales - #{qty}) where dish_id = #{dishId}")
    int decreaseSales(@Param("dishId") Long dishId, @Param("qty") int qty);

    // ========== 搜索（L2） ==========

    /** 菜品搜索带店铺名，因此映射到 DishSearchRow 而不是实体。 */
    String SEARCH_COLS = "d.dish_id as dishId, d.dish_name as dishName, d.shop_id as shopId, "
            + "s.shop_name as shopName, d.price, d.sales, (d.has_spec = '1') as hasSpec";

    String SEARCH_WHERE = " from takeout_dish d join takeout_shop s on d.shop_id = s.shop_id "
            + "where d.del_flag = '0' and d.status = '0' and s.del_flag = '0' and s.status = '0' "
            + "and d.dish_name like concat('%', #{keyword}, '%')";

    /** 搜索按销量排：用户找的菜，卖得多的通常更靠谱。 */
    @Select("<script>select " + SEARCH_COLS + SEARCH_WHERE
            + " order by d.sales desc, d.dish_id limit #{offset}, #{pageSize}</script>")
    List<DishSearchRow> searchPage(@Param("keyword") String keyword,
                                   @Param("offset") int offset,
                                   @Param("pageSize") int pageSize);

    @Select("<script>select count(1)" + SEARCH_WHERE + "</script>")
    int countSearch(@Param("keyword") String keyword);
}
