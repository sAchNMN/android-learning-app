package com.ruoyi.music.mapper;

import com.ruoyi.music.domain.MusicSourceConfig;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 音源配置表。表结构见 {@code sql/06_music_source.sql}。
 *
 * 表不存在时服务层会退回内存默认值，所以这里不强行建表，
 * 保持和其它业务 mapper 一样的「注解 SQL、无 XML」风格。
 */
public interface MusicSourceConfigMapper {

    @Select("select source_key as sourceKey, label, enabled, priority, cookie "
            + "from biz_music_source order by priority asc, source_key asc")
    List<MusicSourceConfig> selectAll();

    @Update("update biz_music_source set enabled = #{enabled}, priority = #{priority}, "
            + "cookie = #{cookie}, update_time = now() where source_key = #{sourceKey}")
    int update(MusicSourceConfig config);
}