package com.ruoyi.music.source;

import com.ruoyi.music.domain.MusicSourceConfig;
import com.ruoyi.music.domain.MusicSourceDto;
import com.ruoyi.music.domain.OnlinePlay;
import com.ruoyi.music.domain.OnlineTrack;
import com.ruoyi.music.domain.SearchResponse;
import com.ruoyi.music.domain.SourceUpdateBody;
import com.ruoyi.music.domain.SyncResult;
import com.ruoyi.music.domain.UserPlaylist;
import com.ruoyi.music.mapper.MusicSourceConfigMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 多音源聚合层，等价于 Mineradio 的本地 Node 网关。
 *
 * 职责只有四件事：
 * 1. 并行搜索各平台，按优先级轮询合并（避免一家刷屏）
 * 2. 单平台取址失败时，用「同名 + 同歌手 + 时长接近」到其它平台找可播版本
 * 3. 搜索结果 / 播放地址短期缓存，减少平台风控和重复请求
 * 4. 音源开关、优先级、Cookie 的读写
 *
 * 不做的事：不缓存音频本体、不代理音频流、不绕过会员 —— 只转发平台
 * 自己下发的播放地址，和 Mineradio 的边界保持一致。
 */
@Service
public class MusicSourceService {

    private static final Logger LOG = Logger.getLogger(MusicSourceService.class.getName());

    private static final int SEARCH_LIMIT_MAX = 30;
    private static final int FALLBACK_CANDIDATES = 2;
    private static final long PLAY_TTL_MS = 10 * 60 * 1000L;
    private static final long PLAY_FAIL_TTL_MS = 30 * 1000L;
    private static final long SEARCH_TTL_MS = 2 * 60 * 1000L;
    private static final int CACHE_MAX_ENTRIES = 400;

    private final List<MusicSourceProvider> providerList;
    private final Map<String, MusicSourceProvider> providers = new LinkedHashMap<>();
    private final Map<String, MusicSourceConfig> configs = new ConcurrentHashMap<>();
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newFixedThreadPool(4, runnable -> {
        Thread thread = new Thread(runnable, "music-source");
        thread.setDaemon(true);
        return thread;
    });
    private volatile long configsLoadedAt = 0L;

    @Autowired(required = false)
    private MusicSourceConfigMapper configMapper;

    public MusicSourceService(List<MusicSourceProvider> providerList) {
        this.providerList = providerList == null ? new ArrayList<>() : providerList;
        int priority = 10;
        for (MusicSourceProvider provider : this.providerList) {
            providers.put(provider.key(), provider);
            MusicSourceConfig config = new MusicSourceConfig();
            config.setSourceKey(provider.key());
            config.setLabel(provider.label());
            config.setEnabled(1);
            config.setPriority(priority);
            config.setCookie("");
            configs.put(provider.key(), config);
            priority += 10;
        }
    }

    // ---------------------------------------------------------------- 搜索

    public SearchResponse search(String keyword, int limit, String sources) {
        String query = keyword == null ? "" : keyword.trim();
        if (query.isEmpty()) {
            throw new IllegalArgumentException("搜索关键词不能为空");
        }
        ensureConfigs();
        List<MusicSourceConfig> selected = enabledConfigs(sources);
        if (selected.isEmpty()) {
            throw new IllegalStateException("没有启用的音源，请到音源管理里打开至少一个平台");
        }
        int size = Math.max(1, Math.min(limit, SEARCH_LIMIT_MAX));
        String cacheKey = "search|" + query.toLowerCase(Locale.ROOT) + "|" + size + "|" + keysOf(selected);
        SearchResponse cached = getCached(cacheKey, SearchResponse.class);
        if (cached != null) {
            return cached;
        }

        Map<String, Future<List<OnlineTrack>>> futures = new LinkedHashMap<>();
        for (MusicSourceConfig config : selected) {
            MusicSourceProvider provider = providers.get(config.getSourceKey());
            if (provider == null) {
                continue;
            }
            futures.put(config.getSourceKey(), executor.submit(
                    () -> provider.search(query, size, nullToEmpty(config.getCookie()))));
        }

        long deadline = System.currentTimeMillis() + 7000L;
        List<List<OnlineTrack>> buckets = new ArrayList<>();
        Map<String, String> errors = new LinkedHashMap<>();
        for (Map.Entry<String, Future<List<OnlineTrack>>> entry : futures.entrySet()) {
            MusicSourceProvider provider = providers.get(entry.getKey());
            String label = provider == null ? entry.getKey() : provider.label();
            try {
                long remaining = Math.max(500L, deadline - System.currentTimeMillis());
                List<OnlineTrack> list = entry.getValue().get(remaining, TimeUnit.MILLISECONDS);
                if (list != null && !list.isEmpty()) {
                    buckets.add(list);
                }
            } catch (Exception ex) {
                entry.getValue().cancel(true);
                errors.put(label, readableError(ex));
                LOG.log(Level.FINE, "search failed: " + label, ex);
            }
        }

        SearchResponse response = new SearchResponse();
        response.setKeyword(query);
        response.setErrors(errors);
        response.setTracks(interleave(buckets, size));
        putCache(cacheKey, response, SEARCH_TTL_MS);
        return response;
    }

    // ---------------------------------------------------------------- 取址 + 换源

    public OnlinePlay play(String source, String id, String title, String artist, String album,
                           Long durationMs, String hash, String albumId, String albumAudioId,
                           String quality, boolean fallback) {
        ensureConfigs();
        MusicSourceProvider provider = providers.get(source);
        if (provider == null) {
            return OnlinePlay.fail(source, id, "url_unavailable", "未知音源：" + source);
        }
        MusicSourceConfig config = configs.get(source);
        if (config == null || !config.isOn()) {
            return OnlinePlay.fail(source, id, "url_unavailable", provider.label() + " 已在音源管理中停用");
        }
        if (id == null || id.isBlank()) {
            return OnlinePlay.fail(source, id, "url_unavailable", "缺少歌曲 id");
        }

        OnlineTrack track = new OnlineTrack();
        track.setSource(source);
        track.setId(id);
        track.setTitle(title);
        track.setArtist(artist);
        track.setAlbum(album);
        track.setDurationMs(durationMs == null ? 0L : durationMs);
        track.setHash(hash);
        track.setAlbumId(albumId);
        track.setAlbumAudioId(albumAudioId);

        String cacheKey = "play|" + source + "|" + id + "|" + (quality == null ? "" : quality);
        OnlinePlay cached = getCached(cacheKey, OnlinePlay.class);
        if (cached != null) {
            return cached;
        }

        OnlinePlay result = provider.resolve(track, nullToEmpty(config.getCookie()), normalizeQuality(quality));
        if (Boolean.TRUE.equals(result.getPlayable())) {
            putCache(cacheKey, result, PLAY_TTL_MS);
            return result;
        }

        OnlinePlay switched = fallback ? tryFallback(track, quality, source) : null;
        if (switched != null) {
            putCache(cacheKey, switched, PLAY_TTL_MS);
            return switched;
        }
        putCache(cacheKey, result, PLAY_FAIL_TTL_MS);
        return result;
    }

    /**
     * 自动换源：拿歌名 + 主歌手到其它平台再搜一遍，只认「录音版本一致」的候选。
     *
     * 对应 Mineradio 的 sourceFallbackProviderReady / isSameTitleArtist：
     * 不只看歌名，还要歌手有交集、时长差在 8 秒内，避免把 Live / 伴奏当成原曲。
     */
    private OnlinePlay tryFallback(OnlineTrack original, String quality, String excludeSource) {
        if (original.getTitle() == null || original.getTitle().isBlank()) {
            return null;
        }
        String query = original.getTitle() + " " + mainArtist(original.getArtist());
        for (MusicSourceConfig config : enabledConfigs(null)) {
            if (config.getSourceKey().equals(excludeSource)) {
                continue;
            }
            MusicSourceProvider provider = providers.get(config.getSourceKey());
            if (provider == null) {
                continue;
            }
            List<OnlineTrack> candidates;
            try {
                candidates = provider.search(query, 8, nullToEmpty(config.getCookie()));
            } catch (RuntimeException ex) {
                continue;
            }
            List<OnlineTrack> matched = new ArrayList<>();
            for (OnlineTrack candidate : candidates) {
                if (sameRecording(original, candidate)) {
                    matched.add(candidate);
                }
            }
            matched.sort(Comparator.comparingInt(
                    candidate -> -similarity(original, candidate)));
            for (int i = 0; i < Math.min(FALLBACK_CANDIDATES, matched.size()); i++) {
                OnlineTrack candidate = matched.get(i);
                OnlinePlay attempted = provider.resolve(
                        candidate, nullToEmpty(config.getCookie()), normalizeQuality(quality));
                if (Boolean.TRUE.equals(attempted.getPlayable())) {
                    attempted.setSwitchedFrom(excludeSource);
                    attempted.setSwitchedTo(config.getSourceKey());
                    return attempted;
                }
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- 歌词

    public String lyric(String source, String id, String hash, String albumId, String albumAudioId,
                        Long durationMs, String title, String artist) {
        ensureConfigs();
        MusicSourceProvider provider = providers.get(source);
        if (provider == null) {
            return "";
        }
        MusicSourceConfig config = configs.get(source);
        OnlineTrack track = new OnlineTrack();
        track.setSource(source);
        track.setId(id);
        track.setHash(hash);
        track.setAlbumId(albumId);
        track.setAlbumAudioId(albumAudioId);
        track.setDurationMs(durationMs == null ? 0L : durationMs);
        track.setTitle(title);
        track.setArtist(artist);
        try {
            return provider.lyric(track, config == null ? "" : nullToEmpty(config.getCookie()));
        } catch (RuntimeException ex) {
            return "";
        }
    }

    // ---------------------------------------------------------------- 账号与发现

    /**
     * 各平台推荐流合并。
     *
     * 与 {@link #search} 的区别是**不需要关键词**：平台自己按热度/新歌给一批，
     * 新用户还没有任何收藏时，推荐页靠它把首屏填满。
     */
    public List<OnlineTrack> discover(int limit) {
        ensureConfigs();
        int size = Math.max(1, Math.min(limit, SEARCH_LIMIT_MAX));
        List<List<OnlineTrack>> buckets = new ArrayList<>();
        for (MusicSourceConfig config : enabledConfigs(null)) {
            MusicSourceProvider provider = providers.get(config.getSourceKey());
            if (provider == null) {
                continue;
            }
            try {
                List<OnlineTrack> list = provider.dailyRecommend(size, nullToEmpty(config.getCookie()));
                if (list != null && !list.isEmpty()) {
                    buckets.add(list);
                }
            } catch (RuntimeException ex) {
                LOG.log(Level.FINE, "推荐流获取失败：" + config.getSourceKey(), ex);
            }
        }
        return interleave(buckets, size);
    }

    /** 把「喜欢 / 取消喜欢」推给平台。平台不支持或未登录都只是失败回执，不抛异常。 */
    public SyncResult like(String source, String trackId, boolean liked) {
        ensureConfigs();
        MusicSourceProvider provider = providers.get(source);
        if (provider == null) {
            return SyncResult.fail(source, "未知音源：" + source);
        }
        MusicSourceConfig config = configs.get(source);
        if (config == null || !config.isOn()) {
            return SyncResult.fail(source, provider.label() + " 已在音源管理中停用");
        }
        if (!provider.supportsAccount()) {
            return SyncResult.fail(source, provider.label() + " 暂不支持同步喜欢，已记在本机与后台");
        }
        try {
            return provider.like(trackId, liked, nullToEmpty(config.getCookie()));
        } catch (RuntimeException ex) {
            return SyncResult.fail(source, provider.label() + " 同步失败：" + ex.getMessage());
        }
    }

    /** 所有已启用平台的个人歌单。没登录的平台直接跳过，不算错误。 */
    public List<UserPlaylist> userPlaylists() {
        ensureConfigs();
        List<UserPlaylist> out = new ArrayList<>();
        for (MusicSourceConfig config : enabledConfigs(null)) {
            MusicSourceProvider provider = providers.get(config.getSourceKey());
            if (provider == null || !provider.supportsAccount()) {
                continue;
            }
            try {
                List<UserPlaylist> list = provider.myPlaylists(nullToEmpty(config.getCookie()));
                if (list != null && !list.isEmpty()) {
                    out.addAll(list);
                }
            } catch (RuntimeException ex) {
                LOG.log(Level.FINE, "歌单同步失败：" + config.getSourceKey(), ex);
            }
        }
        return out;
    }

    public List<OnlineTrack> playlistTracks(String source, String playlistId, int limit) {
        ensureConfigs();
        MusicSourceProvider provider = providers.get(source);
        if (provider == null || playlistId == null || playlistId.isBlank()) {
            return new ArrayList<>();
        }
        MusicSourceConfig config = configs.get(source);
        try {
            List<OnlineTrack> list = provider.playlistTracks(
                    playlistId, limit, config == null ? "" : nullToEmpty(config.getCookie()));
            return list == null ? new ArrayList<>() : list;
        } catch (RuntimeException ex) {
            LOG.log(Level.FINE, "歌单曲目获取失败：" + source + "/" + playlistId, ex);
            return new ArrayList<>();
        }
    }

    /** 平台是否支持账号能力（客户端据此决定要不要显示「同步」按钮）。 */
    public boolean supportsAccount(String source) {
        MusicSourceProvider provider = providers.get(source);
        return provider != null && provider.supportsAccount();
    }

    // ---------------------------------------------------------------- 配置

    public List<MusicSourceDto> listSources() {
        ensureConfigs();
        List<MusicSourceConfig> ordered = new ArrayList<>(configs.values());
        ordered.sort(Comparator
                .comparingInt((MusicSourceConfig config) -> config.getPriority() == null ? 99 : config.getPriority())
                .thenComparing(MusicSourceConfig::getSourceKey));
        List<MusicSourceDto> result = new ArrayList<>();
        for (MusicSourceConfig config : ordered) {
            result.add(new MusicSourceDto(
                    config.getSourceKey(),
                    config.getLabel(),
                    config.isOn(),
                    config.getPriority() == null ? 99 : config.getPriority(),
                    config.getCookie() != null && !config.getCookie().isBlank()));
        }
        return result;
    }

    public void updateSource(String key, SourceUpdateBody body) {
        if (key == null || key.isBlank() || !providers.containsKey(key)) {
            throw new IllegalArgumentException("未知音源：" + key);
        }
        ensureConfigs();
        MusicSourceConfig config = configs.get(key);
        if (config == null) {
            throw new IllegalStateException("音源配置未初始化");
        }
        if (body.getEnabled() != null) {
            config.setEnabled(body.getEnabled() ? 1 : 0);
        }
        if (body.getPriority() != null) {
            config.setPriority(Math.max(0, body.getPriority()));
        }
        if (body.getCookie() != null) {
            String cookie = body.getCookie().trim();
            config.setCookie(cookie.isEmpty() ? null : cookie);
        }
        persist(config);
        // 开关 / Cookie 变了，旧缓存里可能还是「无地址」或别的账号态，直接清空最稳。
        cache.clear();
    }

    private void persist(MusicSourceConfig config) {
        if (configMapper == null) {
            return;
        }
        try {
            int updated = configMapper.update(config);
            if (updated == 0) {
                LOG.warning("音源配置未写入数据库（表里没有该行）：" + config.getSourceKey());
            }
        } catch (RuntimeException ex) {
            // 表没建也允许先跑：本次进程内生效，建表后重启即可持久化。
            LOG.log(Level.WARNING, "音源配置写入失败，本次仅内存生效", ex);
        }
    }

    private void ensureConfigs() {
        if (!configs.isEmpty() && System.currentTimeMillis() - configsLoadedAt < 60_000L) {
            return;
        }
        synchronized (this) {
            if (!configs.isEmpty() && System.currentTimeMillis() - configsLoadedAt < 60_000L) {
                return;
            }
            if (configMapper != null) {
                try {
                    for (MusicSourceConfig stored : configMapper.selectAll()) {
                        if (stored == null || stored.getSourceKey() == null) {
                            continue;
                        }
                        MusicSourceConfig current = configs.get(stored.getSourceKey());
                        if (current == null) {
                            continue;
                        }
                        current.setEnabled(stored.getEnabled() == null ? 1 : stored.getEnabled());
                        current.setPriority(stored.getPriority() == null ? current.getPriority() : stored.getPriority());
                        current.setCookie(stored.getCookie());
                    }
                } catch (RuntimeException ex) {
                    LOG.log(Level.FINE, "读取 biz_music_source 失败，使用默认音源配置", ex);
                }
            }
            configsLoadedAt = System.currentTimeMillis();
        }
    }

    private List<MusicSourceConfig> enabledConfigs(String sourcesCsv) {
        Set<String> filter = new LinkedHashSet<>();
        if (sourcesCsv != null && !sourcesCsv.isBlank()) {
            for (String part : sourcesCsv.split(",")) {
                if (!part.isBlank()) {
                    filter.add(part.trim());
                }
            }
        }
        List<MusicSourceConfig> list = new ArrayList<>();
        for (MusicSourceConfig config : configs.values()) {
            if (!config.isOn()) {
                continue;
            }
            if (!filter.isEmpty() && !filter.contains(config.getSourceKey())) {
                continue;
            }
            list.add(config);
        }
        list.sort(Comparator
                .comparingInt((MusicSourceConfig config) -> config.getPriority() == null ? 99 : config.getPriority())
                .thenComparing(MusicSourceConfig::getSourceKey));
        return list;
    }

    // ---------------------------------------------------------------- 匹配与工具

    static boolean sameRecording(OnlineTrack source, OnlineTrack candidate) {
        String sourceTitle = normalize(source.getTitle());
        String candidateTitle = normalize(candidate.getTitle());
        if (sourceTitle.isEmpty() || candidateTitle.isEmpty()) {
            return false;
        }
        boolean titleMatch = sourceTitle.equals(candidateTitle)
                || baseTitle(sourceTitle).equals(baseTitle(candidateTitle));
        if (!titleMatch) {
            return false;
        }
        long sourceDuration = source.getDurationMs() == null ? 0 : source.getDurationMs();
        long candidateDuration = candidate.getDurationMs() == null ? 0 : candidate.getDurationMs();
        if (sourceDuration > 0 && candidateDuration > 0
                && Math.abs(sourceDuration - candidateDuration) > 8000L) {
            return false;
        }
        Set<String> sourceArtists = artistTokens(source.getArtist());
        Set<String> candidateArtists = artistTokens(candidate.getArtist());
        if (!sourceArtists.isEmpty() && !candidateArtists.isEmpty()
                && Collections.disjoint(sourceArtists, candidateArtists)) {
            return false;
        }
        return true;
    }

    private static int similarity(OnlineTrack source, OnlineTrack candidate) {
        int score = normalize(source.getTitle()).equals(normalize(candidate.getTitle())) ? 4 : 2;
        Set<String> sourceArtists = artistTokens(source.getArtist());
        Set<String> candidateArtists = artistTokens(candidate.getArtist());
        if (!sourceArtists.isEmpty() && sourceArtists.equals(candidateArtists)) {
            score += 2;
        }
        long diff = Math.abs(
                (source.getDurationMs() == null ? 0 : source.getDurationMs())
                        - (candidate.getDurationMs() == null ? 0 : candidate.getDurationMs()));
        if (diff > 0 && diff < 3000L) {
            score += 1;
        }
        return score;
    }

    private static Set<String> artistTokens(String artist) {
        Set<String> tokens = new HashSet<>();
        if (artist == null || artist.isBlank()) {
            return tokens;
        }
        String cleaned = artist.toLowerCase(Locale.ROOT)
                .replace("feat.", "/").replace("featuring", "/").replace("&", "/");
        for (String part : cleaned.split("[/、,，;；]")) {
            String token = normalize(part);
            if (!token.isEmpty()) {
                tokens.add(token);
            }
        }
        return tokens;
    }

    static String normalize(String value) {
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

    private static String normalizeQuality(String quality) {
        if (quality == null || quality.isBlank()) {
            return "exhigh";
        }
        String value = quality.trim().toLowerCase(Locale.ROOT);
        return Set.of("standard", "high", "exhigh", "lossless").contains(value) ? value : "exhigh";
    }

    private static List<OnlineTrack> interleave(List<List<OnlineTrack>> buckets, int limit) {
        List<OnlineTrack> merged = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int index = 0;
        boolean added = true;
        while (merged.size() < limit && added) {
            added = false;
            for (List<OnlineTrack> bucket : buckets) {
                if (index >= bucket.size()) {
                    continue;
                }
                OnlineTrack track = bucket.get(index);
                String key = track.getSource() + "|" + track.getId();
                if (seen.add(key)) {
                    merged.add(track);
                    added = true;
                    if (merged.size() >= limit) {
                        break;
                    }
                }
            }
            index++;
        }
        return merged;
    }

    private static String keysOf(List<MusicSourceConfig> configs) {
        StringBuilder out = new StringBuilder();
        for (MusicSourceConfig config : configs) {
            if (out.length() > 0) {
                out.append(',');
            }
            out.append(config.getSourceKey());
        }
        return out.toString();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String readableError(Exception ex) {
        Throwable cause = ex;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        if (message == null || message.isBlank()) {
            message = cause.getClass().getSimpleName();
        }
        return "搜索失败：" + message;
    }

    @SuppressWarnings("unchecked")
    private <T> T getCached(String key, Class<T> type) {
        CacheEntry entry = cache.get(key);
        if (entry == null) {
            return null;
        }
        if (entry.expiresAt <= System.currentTimeMillis()) {
            cache.remove(key);
            return null;
        }
        return type.isInstance(entry.value) ? (T) entry.value : null;
    }

    private void putCache(String key, Object value, long ttlMs) {
        if (cache.size() > CACHE_MAX_ENTRIES) {
            cache.entrySet().removeIf(entry -> entry.getValue().expiresAt <= System.currentTimeMillis());
            if (cache.size() > CACHE_MAX_ENTRIES) {
                cache.clear();
            }
        }
        CacheEntry entry = new CacheEntry();
        entry.value = value;
        entry.expiresAt = System.currentTimeMillis() + ttlMs;
        cache.put(key, entry);
    }

    private static final class CacheEntry {
        Object value;
        long expiresAt;
    }
}