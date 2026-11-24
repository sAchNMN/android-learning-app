package com.ruoyi.music.mapper;

import com.ruoyi.music.domain.CommentRow;
import com.ruoyi.music.domain.MusicComment;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 评论与评论点赞的读写。
 *
 * 点赞用「唯一键 + INSERT IGNORE」而不是先查再写：
 * 连点两下时先查后写会两个请求都读到「没赞过」，然后其中一条撞唯一键报错。
 * IGNORE 让重复点赞变成一次空操作，返回影响行数就能判断状态是否真的变了。
 */
public interface MusicCommentMapper {

    /**
     * 某首歌的评论列表，带「当前设备是否赞过」。
     *
     * {@code left join ... and l.device_id = #{deviceId}} 这里是关键：
     * 条件必须写在 join 里，写到 where 里就等于只返回自己赞过的评论。
     */
    @Select("select c.id, c.device_id as deviceId, c.device_name as deviceName, c.content, "
            + "c.like_count as likeCount, "
            + "date_format(c.create_time, '%Y-%m-%d %H:%i:%s') as createTime, "
            + "case when l.id is null then 0 else 1 end as likedFlag "
            + "from biz_music_comment c "
            + "left join biz_music_comment_like l "
            + "on l.comment_id = c.id and l.device_id = #{deviceId} "
            + "where c.song_key = #{songKey} and c.status = 1 "
            + "order by c.create_time desc, c.id desc "
            + "limit #{limit} offset #{offset}")
    List<CommentRow> selectBySong(@Param("songKey") String songKey,
                                  @Param("deviceId") String deviceId,
                                  @Param("limit") int limit,
                                  @Param("offset") int offset);

    @Select("select count(*) from biz_music_comment where song_key = #{songKey} and status = 1")
    int countBySong(@Param("songKey") String songKey);

    @Insert("insert into biz_music_comment "
            + "(song_key, track_key, track_title, track_artist, source_key, device_id, device_name, "
            + "content, like_count, status, create_time) "
            + "values (#{songKey}, #{trackKey}, #{trackTitle}, #{trackArtist}, #{sourceKey}, "
            + "#{deviceId}, #{deviceName}, #{content}, 0, 1, now())")
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    int insert(MusicComment comment);

    @Select("select id, song_key as songKey, device_id as deviceId, content "
            + "from biz_music_comment where id = #{id} and status = 1")
    MusicComment selectById(@Param("id") long id);

    /** 只允许删自己的：device_id 是 where 的一部分，不是先查再比。 */
    @Delete("delete from biz_music_comment where id = #{id} and device_id = #{deviceId}")
    int deleteOwn(@Param("id") long id, @Param("deviceId") String deviceId);

    /** 频控：某设备最近 N 秒内发了几条。 */
    @Select("select count(*) from biz_music_comment "
            + "where device_id = #{deviceId} and create_time > date_sub(now(), interval #{seconds} second)")
    int countRecentByDevice(@Param("deviceId") String deviceId, @Param("seconds") int seconds);

    @Insert("insert ignore into biz_music_comment_like (comment_id, device_id, create_time) "
            + "values (#{commentId}, #{deviceId}, now())")
    int insertLike(@Param("commentId") long commentId, @Param("deviceId") String deviceId);

    @Delete("delete from biz_music_comment_like where comment_id = #{commentId} and device_id = #{deviceId}")
    int deleteLike(@Param("commentId") long commentId, @Param("deviceId") String deviceId);

    /** 计数不下探到负数：并发取消时可能多减一次，用 greatest 兜住。 */
    @Update("update biz_music_comment set like_count = like_count + 1 where id = #{commentId}")
    int increaseLike(@Param("commentId") long commentId);

    @Update("update biz_music_comment set like_count = greatest(like_count - 1, 0) where id = #{commentId}")
    int decreaseLike(@Param("commentId") long commentId);

    @Select("select like_count from biz_music_comment where id = #{commentId}")
    Integer selectLikeCount(@Param("commentId") long commentId);
}
