package com.ruoyi.music.mapper;

import com.ruoyi.music.domain.UserLike;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 「我喜欢」的读写。
 *
 * 表里保留 {@code liked=0} 的行而不是物理删除：取消喜欢也是一次口味信号，
 * 直接删掉就查不到「他明确不喜欢这首」了。
 */
public interface MusicUserLikeMapper {

    /** 我喜欢的曲目，最近操作的在前。 */
    @Select("select device_id as deviceId, source, track_id as id, title, artist, album, "
            + "duration_ms as durationMs, cover, hash, album_id as albumId, "
            + "album_audio_id as albumAudioId, liked, update_time as updateTime "
            + "from biz_music_user_like where device_id = #{deviceId} and liked = 1 "
            + "order by update_time desc limit #{limit}")
    List<UserLike> selectLiked(@Param("deviceId") String deviceId, @Param("limit") int limit);

    /** 任一方向的近期操作（用于「最近喜欢/取消」这类口味信号）。 */
    @Select("select device_id as deviceId, source, track_id as id, title, artist, album, "
            + "duration_ms as durationMs, cover, hash, album_id as albumId, "
            + "album_audio_id as albumAudioId, liked, update_time as updateTime "
            + "from biz_music_user_like where device_id = #{deviceId} "
            + "order by update_time desc limit #{limit}")
    List<UserLike> selectRecent(@Param("deviceId") String deviceId, @Param("limit") int limit);

    /** 口味种子：喜欢得最多的歌手。
     *
     * 只取 {@code liked=1}，按「同歌手喜欢首数」降序，最近喜欢的优先，
     * 让推荐跟着最近的口味走而不是被历史堆积绑架。
     */
    @Select("select artist from biz_music_user_like "
            + "where device_id = #{deviceId} and liked = 1 and artist is not null and artist <> '' "
            + "group by artist order by count(*) desc, max(update_time) desc limit #{limit}")
    List<String> selectTasteArtists(@Param("deviceId") String deviceId, @Param("limit") int limit);

    /** 已经喜欢的「歌名」，推荐时要排掉，避免推回来自己已经收藏的歌。 */
    @Select("select title from biz_music_user_like "
            + "where device_id = #{deviceId} and liked = 1 and title is not null and title <> '' "
            + "order by update_time desc limit #{limit}")
    List<String> selectLikedTitles(@Param("deviceId") String deviceId, @Param("limit") int limit);

    @Insert("insert into biz_music_user_like "
            + "(device_id, source, track_id, title, artist, album, duration_ms, cover, hash, "
            + "album_id, album_audio_id, liked, update_time) "
            + "values (#{deviceId}, #{source}, #{id}, #{title}, #{artist}, #{album}, "
            + "#{durationMs}, #{cover}, #{hash}, #{albumId}, #{albumAudioId}, #{liked}, now()) "
            + "on duplicate key update title = values(title), artist = values(artist), "
            + "album = values(album), duration_ms = values(duration_ms), cover = values(cover), "
            + "hash = values(hash), album_id = values(album_id), "
            + "album_audio_id = values(album_audio_id), liked = values(liked), update_time = now()")
    int upsert(UserLike like);
}
