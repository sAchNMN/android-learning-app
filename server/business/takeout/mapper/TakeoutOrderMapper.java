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
            + "receiver, phone, address, remark, cancel_reason as cancelReason, "
            + "review_status as reviewStatus, "
            + "date_format(expect_time, '%Y-%m-%d %H:%i:%s') as expectTime, "
            + "date_format(pay_time, '%Y-%m-%d %H:%i:%s') as payTime, "
            + "date_format(deliver_time, '%Y-%m-%d %H:%i:%s') as deliverTime, "
            + "date_format(receive_time, '%Y-%m-%d %H:%i:%s') as receiveTime, "
            + "date_format(finish_time, '%Y-%m-%d %H:%i:%s') as finishTime, "
            + "date_format(cancel_time, '%Y-%m-%d %H:%i:%s') as cancelTime, "
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

    /**
     * 分页查询。[statuses] 为空表示全部。
     *
     * 状态集合由 Service 从 tab 映射而来，客户端传的 tab 永远进不了 SQL。
     */
    @Select("<script>select " + COLS + " from takeout_order where user_id = #{userId}"
            + "<if test='statuses != null and statuses.size() > 0'> and status in "
            + "<foreach collection='statuses' item='s' open='(' separator=',' close=')'>#{s}</foreach>"
            + "</if> order by order_id desc limit #{offset}, #{pageSize}</script>")
    List<TakeoutOrder> selectPage(@Param("userId") Long userId,
                                  @Param("statuses") List<String> statuses,
                                  @Param("offset") int offset,
                                  @Param("pageSize") int pageSize);

    @Select("<script>select count(1) from takeout_order where user_id = #{userId}"
            + "<if test='statuses != null and statuses.size() > 0'> and status in "
            + "<foreach collection='statuses' item='s' open='(' separator=',' close=')'>#{s}</foreach>"
            + "</if></script>")
    int countPage(@Param("userId") Long userId, @Param("statuses") List<String> statuses);

    @Select("select " + COLS + " from takeout_order where order_id = #{orderId}")
    TakeoutOrder selectById(@Param("orderId") Long orderId);

    /** 只允许支付自己的、待支付的、未超时的订单，靠 where 条件保证幂等。 */
    @Update("update takeout_order set status = '1', pay_time = now(), update_time = now() "
            + "where order_id = #{orderId} and user_id = #{userId} and status = '0' "
            + "and create_time > date_sub(now(), interval 15 minute)")
    int markPaid(@Param("orderId") Long orderId, @Param("userId") Long userId);

    /**
     * 待支付超时取消（惰性）。
     *
     * 不跑定时任务：用户在查列表或详情时顺手判一下，影响行数 > 0 说明刚被取消。
     */
    @Update("update takeout_order set status = '4', cancel_reason = '超时未支付，系统自动取消', "
            + "cancel_time = now(), update_time = now() "
            + "where order_id = #{orderId} and status = '0' "
            + "and create_time < date_sub(now(), interval 15 minute)")
    int expireCancel(@Param("orderId") Long orderId);

    /**
     * 模拟商家接单。
     *
     * 演示期用：支付 1 分钟后自动把订单推到「配送中」，否则订单永远卡在已支付，
     * 用户没法验确认收货。后台接单功能上线后删掉这段，改由商家操作。
     */
    @Update("update takeout_order set status = '2', deliver_time = now(), update_time = now() "
            + "where order_id = #{orderId} and status = '1' "
            + "and pay_time < date_sub(now(), interval 1 minute)")
    int markDelivered(@Param("orderId") Long orderId);

    /** 取消：待支付，或已支付但商家未接单。 */
    @Update("update takeout_order set status = '4', cancel_reason = #{reason}, "
            + "cancel_time = now(), update_time = now() "
            + "where order_id = #{orderId} and user_id = #{userId} "
            + "and status in ('0', '1') and deliver_time is null")
    int cancel(@Param("orderId") Long orderId,
               @Param("userId") Long userId,
               @Param("reason") String reason);

    /** 确认收货：只有配送中可确认。 */
    @Update("update takeout_order set status = '3', receive_time = now(), finish_time = now(), "
            + "update_time = now() "
            + "where order_id = #{orderId} and user_id = #{userId} and status = '2'")
    int confirm(@Param("orderId") Long orderId, @Param("userId") Long userId);

    /**
     * 标记已评价。
     *
     * 用 `review_status = '0'` 做 CAS，影响行数为 0 说明已经评过，
     * 与 takeout_review 的唯一键形成双保险：并发下最多一条评价落库。
     */
    @Update("update takeout_order set review_status = '1', update_time = now() "
            + "where order_id = #{orderId} and user_id = #{userId} and review_status = '0'")
    int markReviewed(@Param("orderId") Long orderId, @Param("userId") Long userId);
}
