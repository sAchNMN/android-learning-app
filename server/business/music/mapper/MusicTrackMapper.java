package com.ruoyi.music.mapper;

import com.ruoyi.music.domain.MusicTrack;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** 云端曲库。 */
public interface MusicTrackMapper {

    /** 别名直接对齐客户端 DTO：url 用的就是 file_path，省得在服务端再包一层。 */
    @Select("select id, title, artist, album, duration_ms as durationMs, "
            + "file_path as url, cover, sort_no as sortNo "
            + "from biz_music_track where status = '0' and del_flag = '0' "
            + "order by sort_no, id")
    List<MusicTrack> selectAll();
}
