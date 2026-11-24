package com.ruoyi.takeout.mapper;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** 热搜词。搜索页与首页搜索框共用一份数据。 */
public interface TakeoutHotKeywordMapper {

    @Select("select keyword from takeout_hot_keyword where status = '0' "
            + "order by sort_no, keyword_id limit #{limit}")
    List<String> selectHot(@Param("limit") int limit);
}
