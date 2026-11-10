package com.ruoyi.takeout.mapper;

import com.ruoyi.takeout.domain.TakeoutOrderItem;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** 订单明细。 */
public interface TakeoutOrderItemMapper {

    @Insert("<script>"
            + "insert into takeout_order_item(order_id, dish_id, dish_name, price, quantity, subtotal) values "
            + "<foreach collection='items' item='i' separator=','>"
            + "(#{i.orderId}, #{i.dishId}, #{i.dishName}, #{i.price}, #{i.quantity}, #{i.subtotal})"
            + "</foreach>"
            + "</script>")
    int insertBatch(@Param("items") List<TakeoutOrderItem> items);

    @Select("select item_id as itemId, order_id as orderId, dish_id as dishId, dish_name as dishName, "
            + "price, quantity, subtotal from takeout_order_item "
            + "where order_id = #{orderId} order by item_id")
    List<TakeoutOrderItem> selectByOrder(@Param("orderId") Long orderId);
}
