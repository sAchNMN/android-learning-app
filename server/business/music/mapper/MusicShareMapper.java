package com.ruoyi.music.mapper;

import com.ruoyi.music.domain.MusicShare;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 分享码的读写。
 *
 * 过期过滤放在 SQL 里而不是 Java 里：少一次「查出来再判断」，
 * 也避免忘了判断导致过期码还能用。
 */
public interface MusicShareMapper {

    @Insert("insert into biz_music_share "
            + "(code, owner_device, title, track_count, payload_json, view_count, create_time, expire_time) "
            + "values (#{code}, #{ownerDevice}, #{title}, #{trackCount}, #{payloadJson}, 0, now(), "
            + "date_add(now(), interval #{expireDays} day))")
    int insert(@Param("code") String code,
               @Param("ownerDevice") String ownerDevice,
               @Param("title") String title,
               @Param("trackCount") int trackCount,
               @Param("payloadJson") String payloadJson,
               @Param("expireDays") int expireDays);

    /** 只返回未过期的行；「码不存在」和「已过期」对调用方是同一个结果。 */
    @Select("select id, code, owner_device as ownerDevice, title, track_count as trackCount, "
            + "payload_json as payloadJson, view_count as viewCount, "
            + "date_format(create_time, '%Y-%m-%d %H:%i:%s') as createTime, "
            + "date_format(expire_time, '%Y-%m-%d %H:%i:%s') as expireTime "
            + "from biz_music_share where code = #{code} "
            + "and (expire_time is null or expire_time > now()) limit 1")
    MusicShare selectByCode(@Param("code") String code);

    @Update("update biz_music_share set view_count = view_count + 1 where code = #{code}")
    int increaseView(@Param("code") String code);
}
