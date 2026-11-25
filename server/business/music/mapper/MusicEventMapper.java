package com.ruoyi.music.mapper;

import com.ruoyi.music.domain.ListenEventRow;
import com.ruoyi.music.domain.RankScore;
import com.ruoyi.music.domain.RankTrackDto;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Date;
import java.util.List;

/**
 * 播放事件流水的读写。
 *
 * 只有一个写方法、四个读方法，其中三个是聚合。刻意**不做增量物化表**：
 * 一张流水表加聚合查询，在课程项目的量级上最快、也最不容易错；
 * 真要上量再按小时物化，那时也已经有干净的流水可以补算。
 *
 * 聚合里 {@code group_concat} 一律不用 —— 它受 {@code group_concat_max_len}
 * （默认 1024 字节）截断，一首热歌几十条记录就会把曲目 id 截成半截，
 * 表现为"榜单里有一行点开就报错"。
 */
public interface MusicEventMapper {

    @Insert("insert into biz_music_listen_event "
            + "(device_id, song_key, source, track_id, title, artist, cover, duration_ms, "
            + "event, position_ms, weight) values (#{deviceId}, #{songKey}, #{source}, "
            + "#{trackId}, #{title}, #{artist}, #{cover}, #{durationMs}, #{event}, "
            + "#{positionMs}, #{weight})")
    int insert(ListenEventRow row);

    /**
     * 热歌榜：窗口内得分最高的歌。
     *
     * {@code having sum(weight) > 0} 是必要的：被反复跳过的歌得分是负的，
     * 不挡掉就会出现"榜单末尾是一堆没人听的歌"，比空榜还难看。
     */
    @Select("select song_key as songKey, sum(weight) as score "
            + "from biz_music_listen_event where create_time >= #{since} "
            + "group by song_key having sum(weight) > 0 "
            + "order by score desc limit #{limit}")
    List<RankScore> selectHot(@Param("since") Date since, @Param("limit") int limit);

    /**
     * 飙升榜：近 24 小时比前一个 24 小时涨得最多的歌。
     *
     * 用两个滚动窗口而不是"今天 0 点 vs 昨天"，是因为零点刚过时今天的窗口是空的，
     * 整张榜会瞬间归零 —— 晚上十一点半演示，和凌晨演示会看到两种结果。
     *
     * 只统计近 24 小时有过收听（{@code having score > 0}）的歌：
     * 一首已经没人听的歌，"从 40 分掉到 0 分"不该算飙升。
     */
    @Select("select song_key as songKey, "
            + "sum(case when create_time >= #{todayStart} then weight else 0 end) as score, "
            + "sum(case when create_time < #{todayStart} then weight else 0 end) as base "
            + "from biz_music_listen_event where create_time >= #{baseStart} "
            + "group by song_key having score > 0 "
            + "order by (score - base) desc, score desc limit #{limit}")
    List<RankScore> selectRising(
            @Param("todayStart") Date todayStart,
            @Param("baseStart") Date baseStart,
            @Param("limit") int limit);

    /**
     * 补齐展示字段：取每首歌**最近一条**事件上的曲目信息。
     *
     * 不用聚合里的 max(title)/max(source)：那是在两列上各自取最大，
     * 可能拼出「酷狗的 id + 网易云的平台名」，点播时必然失败。
     * 这里用 id 反查同一行，保证 source 和 trackId 来自同一条记录。
     */
    @Select({"<script>",
            "select song_key as songKey, source, track_id as trackId, title, artist, cover, "
                    + "duration_ms as durationMs from biz_music_listen_event "
                    + "where id in (select max(id) from biz_music_listen_event "
                    + "where song_key in ",
            "<foreach collection='keys' item='key' open='(' separator=',' close=')'>#{key}</foreach>",
            " group by song_key)",
            "</script>"})
    List<RankTrackDto> selectMeta(@Param("keys") List<String> keys);

    /** 榜单脚注用：窗口内一共收到多少条播放事件。 */
    @Select("select count(*) from biz_music_listen_event where create_time >= #{since}")
    long countSince(@Param("since") Date since);
}
