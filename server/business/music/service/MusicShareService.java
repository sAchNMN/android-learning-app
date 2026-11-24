package com.ruoyi.music.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.music.domain.MusicShare;
import com.ruoyi.music.domain.ShareBody;
import com.ruoyi.music.mapper.MusicShareMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 歌单分享码。
 *
 * 这是本项目里**唯一一件客户端物理上做不到的事**：
 * 一台设备生成的码，要在另一台设备上能查到。收藏、推荐、音源配置都已经搬到客户端了，
 * 只有「中心化的短码分配」必须有一台公共服务器。
 *
 * 服务端只做四件事，都在客户端做不到或做不好的范畴：
 * 1. **短码唯一**：32^6 的空间，靠唯一索引 + 撞码重试保证，客户端无法保证全局唯一
 * 2. **条数与体积上限**：不然就成了免费网盘
 * 3. **过期管理**：过期过滤写在 SQL 里，避免「查出来再判断」时漏判
 * 4. **访问计数**：谁也不知道自己的歌单被导入了多少次
 *
 * 曲目内容一律原样透传，不解析、不校验字段 —— 理由见 {@link MusicShare} 的注释。
 */
@Service
public class MusicShareService {

    private static final Logger log = LoggerFactory.getLogger(MusicShareService.class);

    /** 单次分享最多存多少首。超出只截断不报错，用户不该因为歌单长而失败。 */
    private static final int MAX_TRACKS = 200;

    /** payload 字符数上限，约等于 512KB。正常 200 首远达不到。 */
    private static final int MAX_PAYLOAD_CHARS = 512 * 1024;

    private static final int EXPIRE_DAYS = 30;

    /** 撞码重试次数。32^6 ≈ 10.7 亿，实际几乎不会撞。 */
    private static final int MAX_CODE_ATTEMPTS = 5;

    private static final int CODE_LENGTH = 6;

    /** 去掉了 0/O/1/I/L —— 用户会口头念码、手抄码，这几个字符歧义最大。 */
    private static final char[] CODE_ALPHABET =
            "23456789ABCDEFGHJKMNPQRSTUVWXYZ".toCharArray();

    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * 表没建时 {@code mapper} 为 null，此时接口返回「分享功能未就绪」而不是 500。
     * 和 {@code RecommendService.likeMapper} 的处理方式一致。
     */
    @Autowired(required = false)
    private MusicShareMapper mapper;

    public boolean available() {
        return mapper != null;
    }

    /**
     * 生成分享码。
     *
     * @throws ServiceException 歌单为空 / 体积超限 / 表不存在 / 连续撞码
     */
    public MusicShare create(ShareBody body) {
        if (!available()) {
            throw new ServiceException("分享功能未就绪：biz_music_share 表不存在，请先执行 sql/09_music_share.sql");
        }
        if (body == null) {
            throw new ServiceException("请求体为空");
        }
        JSONArray tracks = body.getTracks();
        if (tracks == null || tracks.isEmpty()) {
            throw new ServiceException("歌单里没有曲目");
        }

        JSONArray payloadTracks = tracks.size() > MAX_TRACKS
                ? new JSONArray(new ArrayList<>(tracks.subList(0, MAX_TRACKS)))
                : tracks;
        String payload = payloadTracks.toJSONString();
        if (payload.length() > MAX_PAYLOAD_CHARS) {
            throw new ServiceException("歌单数据过大，请分多次分享");
        }

        String title = trimTo(body.getTitle(), 100, "未命名歌单");
        String deviceId = trimTo(body.getDeviceId(), 64, "");

        for (int attempt = 0; attempt < MAX_CODE_ATTEMPTS; attempt++) {
            String code = nextCode();
            try {
                mapper.insert(code, deviceId, title, payloadTracks.size(), payload, EXPIRE_DAYS);
                MusicShare saved = mapper.selectByCode(code);
                if (saved != null) {
                    return saved;
                }
            } catch (DuplicateKeyException ex) {
                // 撞码：换一个再试，不打扰用户
                log.debug("分享码 {} 已被占用，重试", code);
            }
        }
        throw new ServiceException("分享码生成失败，请重试");
    }

    /** 按码查歌单。返回 null 表示不存在或已过期 —— 对用户是同一件事。 */
    public MusicShare resolve(String code) {
        if (!available() || code == null || code.isBlank()) {
            return null;
        }
        MusicShare share = mapper.selectByCode(code.trim().toUpperCase(Locale.ROOT));
        if (share == null) {
            return null;
        }
        try {
            mapper.increaseView(share.getCode());
        } catch (RuntimeException ex) {
            // 计数失败不影响「能不能导入」，不抛
            log.debug("分享码 {} 计数失败：{}", share.getCode(), ex.getMessage());
        }
        return share;
    }

    // ---------------------------------------------------------------- 工具

    private static String nextCode() {
        StringBuilder code = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            code.append(CODE_ALPHABET[RANDOM.nextInt(CODE_ALPHABET.length)]);
        }
        return code.toString();
    }

    private static String trimTo(String value, int max, String fallback) {
        if (value == null) {
            return fallback;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return fallback;
        }
        return trimmed.length() > max ? trimmed.substring(0, max) : trimmed;
    }

    /** 供 Controller 组装响应时复用，避免字段名在两边各写一遍。 */
    public static Map<String, Object> toResponse(MusicShare share) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("code", share.getCode());
        data.put("title", share.getTitle());
        data.put("trackCount", share.getTrackCount());
        data.put("createTime", share.getCreateTime());
        data.put("expireTime", share.getExpireTime());
        data.put("viewCount", share.getViewCount());
        JSONArray tracks = new JSONArray();
        try {
            JSONArray parsed = JSON.parseArray(share.getPayloadJson());
            if (parsed != null) {
                tracks = parsed;
            }
        } catch (RuntimeException ex) {
            log.warn("分享码 {} 的曲目数据无法解析，返回空列表", share.getCode());
        }
        data.put("tracks", tracks);
        return data;
    }
}
