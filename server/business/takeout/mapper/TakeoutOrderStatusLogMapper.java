package com.ruoyi.takeout.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;

/**
 * 订单状态流转日志。
 *
 * 只给后台审计用。订单详情的时间轴读 takeout_order 上的时间戳字段，
 * 不读这张表 —— 同一状态可能被后台反复改，流水会出现重复节点。
 */
public interface TakeoutOrderStatusLogMapper {

    @Insert("insert into takeout_order_status_log(order_id, from_status, to_status, operator_type, "
            + "operator_id, remark, create_time) values(#{orderId}, #{fromStatus}, #{toStatus}, "
            + "#{operatorType}, #{operatorId}, #{remark}, now())")
    int insert(@Param("orderId") Long orderId,
               @Param("fromStatus") String fromStatus,
               @Param("toStatus") String toStatus,
               @Param("operatorType") String operatorType,
               @Param("operatorId") Long operatorId,
               @Param("remark") String remark);
}
