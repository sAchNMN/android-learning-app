package com.ruoyi.takeout.mapper;

import com.ruoyi.takeout.domain.TakeoutAddress;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 收货地址读写。
 *
 * 所有查询与更新都带 user_id 条件，越权防护靠 SQL 本身，
 * 不在 Java 里「查出来再比对」，那种写法迟早漏。
 */
public interface TakeoutAddressMapper {

    String COLS = "address_id as addressId, user_id as userId, receiver, phone, province, city, district, "
            + "detail, tag, is_default as isDefault, "
            + "date_format(create_time, '%Y-%m-%d %H:%i:%s') as createTime, "
            + "date_format(update_time, '%Y-%m-%d %H:%i:%s') as updateTime";

    /** 默认地址排第一，其余按更新时间倒序。 */
    @Select("select " + COLS + " from takeout_address where user_id = #{userId} and del_flag = '0' "
            + "order by is_default desc, update_time desc, address_id desc")
    List<TakeoutAddress> selectByUser(@Param("userId") Long userId);

    @Select("select " + COLS + " from takeout_address "
            + "where address_id = #{addressId} and user_id = #{userId} and del_flag = '0'")
    TakeoutAddress selectById(@Param("addressId") Long addressId, @Param("userId") Long userId);

    @Select("select " + COLS + " from takeout_address "
            + "where user_id = #{userId} and del_flag = '0' and is_default = '1' limit 1")
    TakeoutAddress selectDefault(@Param("userId") Long userId);

    @Select("select " + COLS + " from takeout_address where user_id = #{userId} and del_flag = '0' "
            + "order by update_time desc, address_id desc limit 1")
    TakeoutAddress selectLatest(@Param("userId") Long userId);

    @Select("select count(1) from takeout_address where user_id = #{userId} and del_flag = '0'")
    int countByUser(@Param("userId") Long userId);

    @Insert("insert into takeout_address(user_id, receiver, phone, province, city, district, detail, tag, "
            + "is_default, del_flag, create_time, update_time) values("
            + "#{userId}, #{receiver}, #{phone}, #{province}, #{city}, #{district}, #{detail}, #{tag}, "
            + "#{isDefault}, '0', now(), now())")
    @Options(useGeneratedKeys = true, keyProperty = "addressId", keyColumn = "address_id")
    int insert(TakeoutAddress address);

    /** 全字段更新。客户端每次提交的都是完整表单，不做差异更新。 */
    @Update("update takeout_address set receiver = #{receiver}, phone = #{phone}, province = #{province}, "
            + "city = #{city}, district = #{district}, detail = #{detail}, tag = #{tag}, "
            + "is_default = #{isDefault}, update_time = now() "
            + "where address_id = #{addressId} and user_id = #{userId} and del_flag = '0'")
    int update(TakeoutAddress address);

    @Update("update takeout_address set del_flag = '2', update_time = now() "
            + "where address_id = #{addressId} and user_id = #{userId} and del_flag = '0'")
    int delete(@Param("addressId") Long addressId, @Param("userId") Long userId);

    /** 设默认前先清掉该用户其它默认。 */
    @Update("update takeout_address set is_default = '0' where user_id = #{userId} "
            + "and del_flag = '0' and is_default = '1'")
    int clearDefault(@Param("userId") Long userId);

    @Update("update takeout_address set is_default = '1', update_time = now() "
            + "where address_id = #{addressId} and user_id = #{userId} and del_flag = '0'")
    int setDefault(@Param("addressId") Long addressId, @Param("userId") Long userId);
}
