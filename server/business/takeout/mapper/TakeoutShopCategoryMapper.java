package com.ruoyi.takeout.mapper;

import com.ruoyi.takeout.domain.TakeoutShopCategory;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** 店铺分类。 */
public interface TakeoutShopCategoryMapper {

    @Select("select category_id as categoryId, category_name as categoryName, icon, sort_no as sortNo "
            + "from takeout_shop_category where status = '0' and del_flag = '0' "
            + "order by sort_no, category_id")
    List<TakeoutShopCategory> selectEnabled();
}
