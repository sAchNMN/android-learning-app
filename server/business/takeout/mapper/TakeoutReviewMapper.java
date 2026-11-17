package com.ruoyi.takeout.mapper;

import com.ruoyi.takeout.domain.ReviewRow;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 评价读写。
 *
 * 一单一评靠 `takeout_review` 的 `uk_review_order` 唯一键兜底，
 * 不依赖「先查再插」——并发下两个请求都能查到 0 条，查询挡不住重复。
 */
public interface TakeoutReviewMapper {

    @Insert("insert into takeout_review(order_id, shop_id, user_id, rating, taste_rating, "
            + "pack_rating, delivery_rating, tags, content, is_anonymous, del_flag, create_time) values("
            + "#{orderId}, #{shopId}, #{userId}, #{rating}, #{tasteRating}, #{packRating}, "
            + "#{deliveryRating}, #{tags}, #{content}, #{anonymous}, '0', now())")
    int insert(@Param("orderId") Long orderId,
               @Param("shopId") Long shopId,
               @Param("userId") Long userId,
               @Param("rating") int rating,
               @Param("tasteRating") Integer tasteRating,
               @Param("packRating") Integer packRating,
               @Param("deliveryRating") Integer deliveryRating,
               @Param("tags") String tags,
               @Param("content") String content,
               @Param("anonymous") String anonymous);

    @Select("select count(1) from takeout_review where order_id = #{orderId} and del_flag = '0'")
    int countByOrder(@Param("orderId") Long orderId);

    /** 店铺评价分页。匿名判断放 SQL，避免 Java 侧再拼一遍。 */
    @Select("select r.review_id as reviewId, "
            + "case when r.is_anonymous = '1' then '匿名用户' "
            + "else ifnull(u.nick_name, '用户') end as nickname, "
            + "r.rating, r.tags, r.content, r.reply, "
            + "date_format(r.create_time, '%Y-%m-%d %H:%i') as createTime "
            + "from takeout_review r left join sys_user u on u.user_id = r.user_id "
            + "where r.shop_id = #{shopId} and r.del_flag = '0' "
            + "order by r.review_id desc limit #{offset}, #{pageSize}")
    List<ReviewRow> selectPage(@Param("shopId") Long shopId,
                               @Param("offset") int offset,
                               @Param("pageSize") int pageSize);

    @Select("select count(1) from takeout_review where shop_id = #{shopId} and del_flag = '0'")
    int countByShop(@Param("shopId") Long shopId);
}
