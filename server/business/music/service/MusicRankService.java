package com.ruoyi.music.service;

import com.ruoyi.music.domain.ListenEventBody;
import com.ruoyi.music.domain.ListenEventRow;
import com.ruoyi.music.domain.RankScore;
import com.ruoyi.music.domain.RankSnapshotDto;
import com.ruoyi.music.domain.RankTrackDto;
import com.ruoyi.music.mapper.MusicEventMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 榜单：把播放行为折成热度，再按热度排序。
 *
 * 三件事刻意分开，缺一个榜单就会被刷歪：
 *
 * 1. **记流水，不算总分**。每次播放落一行，「这个月第几名」是查出来的。
 *    落一张榜单表就要处理补算、回滚、跨天归档，流水永远不会写错。
 * 2. **跳过要记负分**。只记「播放 +1」，任何一首歌只要被点开就往上爬，
 *    榜单最后反映的是"谁被点得多"，不是"谁被听完"。跳过 −2、听完 +3、
 *    红心 +5，才是「热度」而不是「曝光」。
 * 3. **归一化在服务端做一处**。直接用 {@link MusicCommentService#songKey}：
 *    评论区已经把它定义成「歌名|主歌手」了。两处各写一套正则，
 *    迟早在全角括号或 feat. 上对不齐，同一首歌就会以两个身份各占一个名次。
 */
@Service
public class MusicRankService {

    private static final Logger log = Logger.getLogger(MusicRankService.class.getName());

    public static final String TYPE_HOT = "hot";
    public static final String TYPE_RISING = "rising";

    private static final String SOURCE_LOCAL = "local";

    /** 统计窗口：滚动 24 小时。不是「今天 0 点起」——零点整张榜会瞬间清零。 */
    private static final long WINDOW_MS = 24L * 60 * 60 * 1000;

    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 100;

    /** 同一设备对同一首歌的同一类事件，60 秒内只计一次。 */
    private static final long DEBOUNCE_MS = 60_000L;

    /** 单设备单曲每天最多贡献 30 次正向事件：循环播放能上榜，但上不了王座。 */
    private static final int DAILY_MAX = 30;

    /** 榜单缓存 30 秒。热门榜被反复打开，没必要每次都跑聚合。 */
    private static final long CACHE_MS = 30_000L;

    private static final long SCHEMA_RETRY_MS = 60_000L;

    /** 各类事件对热度的贡献。play 只给 1 分：点开不算数，听完才算。 */
    private static final Map<String, Integer> WEIGHTS;

    static {
        Map<String, Integer> weights = new HashMap<>();
        weights.put("play", 1);
        weights.put("finish", 3);
        weights.put("skip", -2);
        weights.put("like", 5);
        WEIGHTS = Collections.unmodifiableMap(weights);
    }

    @Autowired(required = false)
    private MusicEventMapper mapper;

    /** 防抖：device|songKey|event → 上次计时时刻。 */
    private final Map<String, Long> lastEventAt = new ConcurrentHashMap<>();

    /** 每日额度：日期|device|songKey → 已计次数。 */
    private final Map<String, Integer> dailyCount = new ConcurrentHashMap<>();

    private final Map<String, CachedRank> cache = new ConcurrentHashMap<>();

    /** 建表脚本没执行时，别让每条事件都去撞一次 SQLException。 */
    private volatile long schemaBrokenAt = 0L;

    public boolean available() {
        return mapper != null && !schemaBroken();
    }

    // ---------------------------------------------------------------- 上报

    /**
     * 记一条播放事件。
     *
     * 返回值只用于排查问题，客户端不展示 —— 埋点失败不该弹窗打断听歌。
     * 但**不能静默吞掉原因**，否则"榜单一直是空的"会变成一个查不出来的问题。
     */
    public Map<String, Object> report(ListenEventBody body) {
        if (body == null) {
            return reject("空请求");
        }
        String device = trim(body.getDeviceId());
        String event = trim(body.getEvent()).toLowerCase(Locale.ROOT);
        Integer weight = WEIGHTS.get(event);
        if (device.isEmpty()) {
            return reject("缺少 deviceId");
        }
        if (weight == null) {
            return reject("未知事件：" + event);
        }
        String songKey = MusicCommentService.songKey(body.getTitle(), body.getArtist());
        if (songKey.isEmpty()) {
            return reject("缺少歌名");
        }
        String source = trim(body.getSource()).toLowerCase(Locale.ROOT);
        if (SOURCE_LOCAL.equals(source)) {
            // 本机文件的地址只在那台设备上有效，进榜别人点了只会是一首报错的歌
            return reject("本机曲目不参与榜单");
        }
        if (!available()) {
            return reject("榜单未就绪，先执行 sql/11_music_listen_event.sql");
        }
        if (!accept(device, songKey, event, weight)) {
            return reject("上报过于频繁，已忽略");
        }

        ListenEventRow row = new ListenEventRow();
        row.setDeviceId(device);
        row.setSongKey(songKey);
        row.setSource(source.isEmpty() ? "unknown" : source);
        row.setTrackId(trim(body.getTrackId()));
        row.setTitle(trim(body.getTitle()));
        row.setArtist(trim(body.getArtist()));
        row.setCover(trim(body.getCover()));
        row.setDurationMs(Math.max(0L, body.getDurationMs()));
        row.setEvent(event);
        row.setPositionMs(Math.max(0L, body.getPositionMs()));
        row.setWeight(weight);

        try {
            mapper.insert(row);
        } catch (RuntimeException e) {
            noteSchemaFailure(e);
            return reject("写入失败：" + e.getMessage());
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("accepted", true);
        result.put("songKey", songKey);
        result.put("weight", weight);
        return result;
    }

    /**
     * 防抖 + 每日额度。
     *
     * 防抖按「设备 + 歌 + 事件类型」分键：先 play 后 finish 是两条正常事件，
     * 不能被同一条规则挡掉；而同一首歌反复 play 只算一次。
     */
    private boolean accept(String device, String songKey, String event, int weight) {
        long now = System.currentTimeMillis();
        String debounceKey = device + '\u0001' + songKey + '\u0001' + event;
        Long last = lastEventAt.get(debounceKey);
        if (last != null && now - last < DEBOUNCE_MS) {
            return false;
        }
        lastEventAt.put(debounceKey, now);

        // 负向事件不占额度：一首歌被跳一百次，也不该把"记负分"这件事限流掉
        if (weight <= 0) {
            return true;
        }
        String dayKey = day(now) + '\u0001' + device + '\u0001' + songKey;
        Integer used = dailyCount.get(dayKey);
        int count = used == null ? 0 : used;
        if (count >= DAILY_MAX) {
            return false;
        }
        dailyCount.put(dayKey, count + 1);

        pruneIfNeeded(now);
        return true;
    }

    /** 内存里的两个 Map 会随天数一直长，过线就把今天之外的清掉。 */
    private void pruneIfNeeded(long now) {
        if (lastEventAt.size() < 5000 && dailyCount.size() < 5000) {
            return;
        }
        long cutoff = now - 2 * WINDOW_MS;
        lastEventAt.entrySet().removeIf(entry -> entry.getValue() < cutoff);
        String today = day(now);
        dailyCount.keySet().removeIf(key -> !key.startsWith(today));
    }

    // ---------------------------------------------------------------- 查询

    public RankSnapshotDto list(String type, int limit) {
        String kind = TYPE_RISING.equalsIgnoreCase(trim(type)) ? TYPE_RISING : TYPE_HOT;
        int size = limit <= 0 ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);
        long now = System.currentTimeMillis();

        CachedRank cached = cache.get(kind + ':' + size);
        if (cached != null && now - cached.at < CACHE_MS) {
            return cached.snapshot;
        }

        RankSnapshotDto snapshot = new RankSnapshotDto();
        snapshot.setType(kind);
        snapshot.setWindowStart(format(now - WINDOW_MS));
        snapshot.setUpdatedAt(format(now));

        if (!available()) {
            // 不缓存：建表之后要能立刻看到结果，而不是等 30 秒缓存过期
            snapshot.setMessage("榜单未就绪，先执行 sql/11_music_listen_event.sql");
            return snapshot;
        }

        try {
            boolean rising = TYPE_RISING.equals(kind);
            List<RankScore> scores = rising
                    ? mapper.selectRising(new Date(now - WINDOW_MS), new Date(now - 2 * WINDOW_MS), size)
                    : mapper.selectHot(new Date(now - WINDOW_MS), size);
            snapshot.setTracks(build(scores, rising));
            snapshot.setEventCount(mapper.countSince(new Date(now - WINDOW_MS)));
        } catch (RuntimeException e) {
            log.log(Level.WARNING, "榜单查询失败", e);
            snapshot.setMessage("榜单查询失败：" + e.getMessage());
            return snapshot;
        }

        cache.put(kind + ':' + size, new CachedRank(now, snapshot));
        return snapshot;
    }

    /**
     * 把得分和曲目信息拼成榜单行。
     *
     * 拼不上的（流水里有、元信息查不到）直接跳过：榜单上一行点开就报错，
     * 比少一行难看得多。
     */
    private List<RankTrackDto> build(List<RankScore> scores, boolean rising) {
        if (scores == null || scores.isEmpty()) {
            return new ArrayList<>();
        }
        List<String> keys = new ArrayList<>(scores.size());
        for (RankScore score : scores) {
            if (score.getSongKey() != null && !score.getSongKey().isEmpty()) {
                keys.add(score.getSongKey());
            }
        }
        Map<String, RankTrackDto> meta = new HashMap<>();
        if (!keys.isEmpty()) {
            List<RankTrackDto> rows = mapper.selectMeta(keys);
            if (rows != null) {
                for (RankTrackDto row : rows) {
                    meta.put(row.getSongKey(), row);
                }
            }
        }

        List<RankTrackDto> out = new ArrayList<>(scores.size());
        int rank = 1;
        for (RankScore score : scores) {
            RankTrackDto item = meta.get(score.getSongKey());
            if (item == null) {
                continue;
            }
            item.setRank(rank++);
            item.setSongKey(score.getSongKey());
            item.setScore(score.getScore());
            item.setDelta(rising ? score.getScore() - score.getBase() : 0L);
            out.add(item);
        }
        return out;
    }

    // ---------------------------------------------------------------- 工具

    private void noteSchemaFailure(RuntimeException e) {
        schemaBrokenAt = System.currentTimeMillis();
        log.log(Level.WARNING, "写入播放事件失败，60 秒内不再重试", e);
    }

    private boolean schemaBroken() {
        long at = schemaBrokenAt;
        return at > 0 && System.currentTimeMillis() - at < SCHEMA_RETRY_MS;
    }

    private static Map<String, Object> reject(String reason) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("accepted", false);
        result.put("message", reason);
        return result;
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private static String day(long timestamp) {
        return new SimpleDateFormat("yyyyMMdd", Locale.ROOT).format(new Date(timestamp));
    }

    private static String format(long timestamp) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).format(new Date(timestamp));
    }

    private static final class CachedRank {

        private final long at;

        private final RankSnapshotDto snapshot;

        private CachedRank(long at, RankSnapshotDto snapshot) {
            this.at = at;
            this.snapshot = snapshot;
        }
    }
}
