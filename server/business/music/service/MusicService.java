package com.ruoyi.music.service;

import com.ruoyi.music.domain.MusicTrack;
import com.ruoyi.music.mapper.MusicTrackMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

/** 云端曲库读写。目前只有"取全量"一个用例。 */
@Service
public class MusicService {

    @Autowired
    private MusicTrackMapper trackMapper;

    public List<MusicTrack> listAll() {
        return trackMapper.selectAll();
    }
}
