package com.ruoyi.music.service;

import com.ruoyi.music.domain.OnlineTrack;
import com.ruoyi.music.domain.RecommendResponse;
import com.ruoyi.music.domain.SearchResponse;
import com.ruoyi.music.mapper.MusicUserLikeMapper;
import com.ruoyi.music.source.MusicSourceService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * 「猜你喜欢」。
 *
 * 算法很朴素，但每一步都有理由，不写成黑盒：
 *
 * 1. **口味种子**：收藏最多 / 最近收藏的歌手（前端传的优先，没传就从喜欢表里取）
 * 2. **按歌手搜歌**：用种子歌手当关键词走聚合搜索，天然拿到「同歌手 + 同风格」
 * 3. **补发现流**：不足条数时用各平台推荐流补齐，冷启动不至于空白
 * 4. **去重 + 排除已喜欢**：同一首歌在网易云/酷狗各有一份，只留一份；
 *    已经红心过的歌不再推回来
 * 5. **按天稳定洗牌**：同一天多次进入顺序一致（不闪），点「换一批」传新的 seed 才变
 *
 * 不做的事：不训练模型、不猜音频特征 —— 拿不到播放行为明细之前，
 * 任何「深度学习推荐」都是自欺欺人。
 */
@Service
public class RecommendService {

    /** 每个种子歌手取多少条，取多了会把别的口味挤掉。 */
    private static final int SEARCH_PER_ARTIST = 8;
    private static final int MAX_ARTISTS = 3;
    private static final int LIMIT_MIN = 6;
    private static final int LIMIT_MAX = 50;

    @Autowired
    private MusicSourceService sourceService;

    @Autowired(required = false)
    private MusicUserLikeMapper likeMapper;

    /**
     * @param deviceId  设备号，可为空；为空时只按前端传的 artists 推荐
     * @param artistsCsv 前端算好的口味歌手，逗号分隔
     * @param seed      洗牌种子，空则按天取
     */
    public RecommendResponse recommend(String deviceId, String artistsCsv, Long seed, int limit,
                                       Map<String, String> requestCookies) {
        int size = Math.max(LIMIT_MIN, Math.min(limit, LIMIT_MAX));
        RecommendResponse response = new RecommendResponse();

        Set<String> artists = new LinkedHashSet<>(splitCsv(artistsCsv, MAX_ARTISTS));
        String reason = artists.isEmpty() ? "discover" : "favorites";
        if (artists.isEmpty() && deviceId != null && !deviceId.isBlank() && likeMapper != null) {
            try {
                artists.addAll(likeMapper.selectTasteArtists(deviceId, MAX_ARTISTS));
                if (!artists.isEmpty()) {
                    reason = "likes";
                }
            } catch (RuntimeException ex) {
                // 表没建 / 库没连上都只是「没有口味」，不该让推荐整体失败
                artists.clear();
            }
        }

        List<String> likedTitles = likedTitles(deviceId);

        // 用 LinkedHashMap 天然去重且保序：键是「归一化歌名|主歌手」
        Map<String, OnlineTrack> picked = new LinkedHashMap<>();
        for (String artist : artists) {
            try {
                SearchResponse result = sourceService.search(artist, SEARCH_PER_ARTIST, null, requestCookies);
                for (OnlineTrack track : result.getTracks()) {
                    if (isLiked(track, likedTitles)) {
                        continue;
                    }
                    picked.putIfAbsent(dedupeKey(track), track);
                }
            } catch (RuntimeException ex) {
                // 单个歌手搜不到不影响其它种子
            }
        }

        if (picked.size() < size) {
            for (OnlineTrack track : sourceService.discover(size * 2, requestCookies)) {
                if (picked.size() >= size) {
                    break;
                }
                if (isLiked(track, likedTitles)) {
                    continue;
                }
                picked.putIfAbsent(dedupeKey(track), track);
            }
        }

        List<OnlineTrack> tracks = new ArrayList<>(picked.values());
        Collections.shuffle(tracks, new Random(seed == null ? LocalDate.now().toEpochDay() * 31 : seed));
        if (tracks.size() > size) {
            tracks = new ArrayList<>(tracks.subList(0, size));
        }

        response.setBasedOn(new ArrayList<>(artists));
        response.setReason(reason);
        response.setTracks(tracks);
        return response;
    }

    // ---------------------------------------------------------------- 工具

    private List<String> likedTitles(String deviceId) {
        if (deviceId == null || deviceId.isBlank() || likeMapper == null) {
            return new ArrayList<>();
        }
        try {
            List<String> titles = likeMapper.selectLikedTitles(deviceId, 50);
            return titles == null ? new ArrayList<>() : titles;
        } catch (RuntimeException ex) {
            return new ArrayList<>();
        }
    }

    private static boolean isLiked(OnlineTrack track, List<String> likedTitles) {
        if (likedTitles.isEmpty() || track.getTitle() == null) {
            return false;
        }
        String title = normalize(track.getTitle());
        for (String liked : likedTitles) {
            if (normalize(liked).equals(title)) {
                return true;
            }
        }
        return false;
    }

    /** 平台间去重的键：歌名 + 主歌手，忽略标点与「(Live)」这类后缀。 */
    private static String dedupeKey(OnlineTrack track) {
        return baseTitle(normalize(track.getTitle())) + "|" + normalize(mainArtist(track.getArtist()));
    }

    private static List<String> splitCsv(String csv, int max) {
        List<String> out = new ArrayList<>();
        if (csv == null || csv.isBlank()) {
            return out;
        }
        for (String part : csv.split("[,，]")) {
            String value = part.trim();
            if (!value.isEmpty() && !out.contains(value)) {
                out.add(value);
                if (out.size() >= max) {
                    break;
                }
            }
        }
        return out;
    }

    private static String mainArtist(String artist) {
        if (artist == null) {
            return "";
        }
        for (String part : artist.split("[/、,，&]")) {
            if (!part.isBlank()) {
                return part.trim();
            }
        }
        return artist.trim();
    }

    private static String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.toLowerCase(Locale.ROOT)
                .replaceAll("[\\s\\p{Punct}，。、！？：；“”‘’（）【】《》·—…]", "")
                .trim();
    }

    private static String baseTitle(String normalized) {
        return normalized.replaceAll("\\([^)]*\\)|（[^）]*）|\\[[^\\]]*\\]", "");
    }
}
