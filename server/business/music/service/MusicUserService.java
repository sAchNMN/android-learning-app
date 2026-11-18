package com.ruoyi.music.service;

import com.ruoyi.music.domain.LikeBody;
import com.ruoyi.music.domain.LikeResult;
import com.ruoyi.music.domain.PlaylistDetail;
import com.ruoyi.music.domain.UserLike;
import com.ruoyi.music.domain.UserPlaylist;
import com.ruoyi.music.mapper.MusicUserLikeMapper;
import com.ruoyi.music.source.MusicSourceService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 「我的喜欢 / 我的歌单」的服务层。
 *
 * 一条不可动摇的规则：**后端库先写，平台同步后补**。
 * 平台同步会因为没登录、Cookie 过期、平台风控而失败，但用户点红心这个动作
 * 不能在网络抖动里丢掉；下次同步时再补齐。所以 {@link LikeResult} 里
 * {@code saved} 和 {@code syncs} 是分开的两件事。
 */
@Service
public class MusicUserService {

    private static final Logger LOG = Logger.getLogger(MusicUserService.class.getName());

    /** 本机来源，不需要也没法推给任何平台。 */
    private static final String SOURCE_LOCAL = "local";
    private static final String SOURCE_CLOUD = "cloud";

    @Autowired
    private MusicSourceService sourceService;

    @Autowired(required = false)
    private MusicUserLikeMapper likeMapper;

    public LikeResult like(LikeBody body) {
        LikeResult result = new LikeResult();
        boolean liked = body.getLiked() == null || body.getLiked();
        result.setLiked(liked);

        String deviceId = trim(body.getDeviceId());
        String source = trim(body.getSource()).toLowerCase();
        String trackId = trim(body.getId());
        if (source.isEmpty()) {
            source = SOURCE_LOCAL;
        }

        result.setSaved(persist(deviceId, source, trackId, body, liked));

        // 平台侧同步：本机来源跳过，音源平台推一次（失败只记回执）
        if (!SOURCE_LOCAL.equals(source) && !SOURCE_CLOUD.equals(source) && !trackId.isEmpty()) {
            result.getSyncs().add(sourceService.like(source, trackId, liked));
        }
        return result;
    }

    private boolean persist(String deviceId, String source, String trackId, LikeBody body, boolean liked) {
        if (likeMapper == null) {
            return false;
        }
        if (deviceId.isEmpty() || trackId.isEmpty()) {
            return false;
        }
        UserLike row = new UserLike();
        row.setDeviceId(deviceId);
        row.setSource(source);
        row.setId(trackId);
        row.setTitle(trim(body.getTitle()));
        row.setArtist(trim(body.getArtist()));
        row.setAlbum(trim(body.getAlbum()));
        row.setDurationMs(body.getDurationMs() == null ? 0L : body.getDurationMs());
        row.setCover(trim(body.getCover()));
        row.setHash(trim(body.getHash()));
        row.setAlbumId(trim(body.getAlbumId()));
        row.setAlbumAudioId(trim(body.getAlbumAudioId()));
        row.setLiked(liked);
        try {
            return likeMapper.upsert(row) > 0;
        } catch (RuntimeException ex) {
            // 表没建 / 库挂了：客户端会提示「本次只在本机生效」，不影响点红心的手感
            LOG.log(Level.WARNING, "喜欢记录写入失败", ex);
            return false;
        }
    }

    public List<UserLike> likes(String deviceId, int limit) {
        if (likeMapper == null || deviceId == null || deviceId.isBlank()) {
            return new ArrayList<>();
        }
        int size = Math.max(1, Math.min(limit, 500));
        try {
            List<UserLike> list = likeMapper.selectLiked(deviceId, size);
            return list == null ? new ArrayList<>() : list;
        } catch (RuntimeException ex) {
            LOG.log(Level.WARNING, "喜欢列表读取失败", ex);
            return new ArrayList<>();
        }
    }

    /** 合并所有已登录平台的个人歌单。 */
    public List<UserPlaylist> playlists() {
        return sourceService.userPlaylists();
    }

    public PlaylistDetail playlist(String source, String playlistId, String name, int limit) {
        PlaylistDetail detail = new PlaylistDetail();
        detail.setSource(source);
        detail.setId(playlistId);
        detail.setName(name == null ? "" : name);
        detail.setTracks(sourceService.playlistTracks(source, playlistId, limit));
        return detail;
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
