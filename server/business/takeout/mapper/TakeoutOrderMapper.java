package com.ruoyi.takeout.mapper;

import com.ruoyi.takeout.domain.TakeoutOrder;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 订单读写。时间字段在 SQL 里格式化，保证 JSON 出来是字符串。
 *
 * 状态变更全部用带 `and status = 原状态` 的 UPDATE（CAS），
 * 靠影响行数判断成败，不做「先查再改」。
 */
public interface TakeoutOrderMapper {

    String COLS = "order_id as orderId, order_no as orderNo, user_id as userId, shop_id as shopId, "
            + "shop_name as shopName, address_id as addressId, total_amount as totalAmount, "
            + "delivery_fee as deliveryFee, pay_amount as payAmount, item_count as itemCount, status, "
            + "receiver, phone, address, remark, "
            + "date_format(expect_time, '%Y-%m-%d %H:%i:%s') as expectTime, "
            + "date_format(pay_time, '%Y-%m-%d %H:%i:%s') as payTime, "
            + "date_format(finish_time, '%Y-%m-%d %H:%i:%s') as finishTime, "
            + "date_format(create_time, '%Y-%m-%d %H:%i:%s') as createTime";

    @Insert("insert into takeout_order(order_no, user_id, shop_id, shop_name, address_id, total_amount, "
            + "delivery_fee, pay_amount, item_count, status, receiver, phone, address, expect_time, remark, "
            + "create_time) values("
            + "#{orderNo}, #{userId}, #{shopId}, #{shopName}, #{addressId}, #{totalAmount}, "
            + "#{deliveryFee}, #{payAmount}, #{itemCount}, #{status}, #{receiver}, #{phone}, #{address}, "
            + "str_to_date(#{expectTime}, '%Y-%m-%d %H:%i:%s'), #{remark}, now())")
    @Options(useGeneratedKeys = true, keyProperty = "orderId", keyColumn = "order_id")
    int insert(TakeoutOrder order);

    @Select("select " + COLS + " from takeout_order where user_id = #{userId} order by order_id desc")
    List<TakeoutOrder> selectByUser(@Param("userId") Long userId);

    @Select("select " + COLS + " from takeout_order where order_id = #{orderId}")
    TakeoutOrder selectById(@Param("orderId") Long orderId);

    /** 只允许支付自己的、待支付的、未超时的订单，靠 where 条件保证幂等。 */
    @Update("update takeout_order set status = '1', pay_time = now(), update_time = now() "
            + "where order_id = #{orderId} and user_id = #{userId} and status = '0' "
            + "and create_time > date_sub(now(), interval 15 minute)")
    int markPaid(@Param("orderId") Long orderId, @Param("userId") Long userId);
}
