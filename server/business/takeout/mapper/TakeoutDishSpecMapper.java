package com.ruoyi.takeout.mapper;

import com.ruoyi.takeout.domain.TakeoutDishSpec;
import com.ruoyi.takeout.domain.TakeoutDishSpecOption;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 菜品规格查询。
 *
 * 分两次查（组一次、选项一次），在 Service 里组装，不发 N+1 也不写嵌套 resultMap：
 * 一个店铺最多几十个菜品，两条 SQL 足够，且比 join + collection 好调试。
 *
 * required / multi / is_default 在库里是 char(1)，SQL 里转成 0/1 由 MyBatis 映射成 Boolean。
 * 注意 required 的语义是「必选」，所以库里 '0' 才是必选。
 */
public interface TakeoutDishSpecMapper {

    String SPEC_COLS = "spec_id as specId, dish_id as dishId, spec_name as specName, "
            + "(required = '0') as required, (multi = '0') as multi, sort_no as sortNo";

    @Select("<script>select " + SPEC_COLS + " from takeout_dish_spec "
            + "where del_flag = '0' and dish_id in "
            + "<foreach collection='dishIds' item='id' open='(' separator=',' close=')'>#{id}</foreach>"
            + " order by dish_id, sort_no, spec_id</script>")
    List<TakeoutDishSpec> selectByDishIds(@Param("dishIds") List<Long> dishIds);

    @Select("<script>select option_id as optionId, spec_id as specId, option_name as optionName, "
            + "price_delta as priceDelta, (is_default = '1') as isDefault "
            + "from takeout_dish_spec_option where del_flag = '0' and status = '0' and spec_id in "
            + "<foreach collection='specIds' item='id' open='(' separator=',' close=')'>#{id}</foreach>"
            + " order by spec_id, sort_no, option_id</script>")
    List<TakeoutDishSpecOption> selectOptionsBySpecIds(@Param("specIds") List<Long> specIds);
}
