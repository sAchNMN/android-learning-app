package com.ruoyi.music.service;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.music.domain.CommentBody;
import com.ruoyi.music.domain.CommentDto;
import com.ruoyi.music.domain.CommentPage;
import com.ruoyi.music.domain.CommentRow;
import com.ruoyi.music.domain.MusicComment;
import com.ruoyi.music.mapper.MusicCommentMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 歌曲评论。
 *
 * 这是本项目里**第二个必须依赖服务端的模块**（第一个是分享码）：
 * 评论是多人共享的数据，单机的「评论」只能自言自语。
 *
 * 三个设计决定，都是有理由的：
 *
 * 1. **按 songKey 归属，不按平台曲目 id**。同一首歌在网易云和酷我各有一条记录，
 *    但用户评价的是「这首歌」而不是「这条曲目记录」，所以评论要能互相看见。
 * 2. **归一化只在服务端做一处**。客户端传原始 title/artist。两端各写一套正则，
 *    迟早在全角括号或 `feat.` 上对不齐，同一首歌就会分裂成两批评论。
 * 3. **点赞用唯一键 + INSERT IGNORE**，不是「先查再写」。连点两下时先查后写会
 *    两个请求都读到「没赞过」，然后一条撞唯一键直接 500。
 */
@Service
public class MusicCommentService {

    private static final Logger log = LoggerFactory.getLogger(MusicCommentService.class);

    private static final int MIN_CONTENT = 1;
    private static final int MAX_CONTENT = 500;
    private static final int MAX_SONG_KEY = 200;
    private static final int MAX_DEVICE_NAME = 32;
    private static final int MAX_DEVICE_ID = 64;

    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 50;

    /** 频控：同一设备 60 秒内最多发 3 条。防止一个客户端把整首歌刷成自己的弹幕。 */
    private static final int RATE_WINDOW_SECONDS = 60;
    private static final int RATE_MAX_COMMENTS = 3;

    private static final Pattern PUNCTUATION =
            Pattern.compile("[\\s\\p{Punct}，。、！？：；“”‘’（）【】《》·—…]");
    private static final Pattern ARTIST_SPLIT = Pattern.compile("[/、,，&]");

    @Autowired(required = false)
    private MusicCommentMapper mapper;

    public boolean available() {
        return mapper != null;
    }

    // ---------------------------------------------------------------- 查

    /**
     * 某首歌的评论分页。
     *
     * 歌名或歌手为空时返回空页而不是报错：曲库里确实有没写歌手的文件，
     * 让列表页报个红字说「歌手不能为空」毫无意义。
     */
    public CommentPage list(String title, String artist, String deviceId, int limit, int offset) {
        CommentPage page = new CommentPage();
        page.setTotal(0);
        page.setHasMore(false);
        if (!available()) {
            return page;
        }
        String key = songKey(title, artist);
        if (key.isEmpty()) {
            return page;
        }
        int size = Math.max(1, Math.min(limit <= 0 ? DEFAULT_LIMIT : limit, MAX_LIMIT));
        int skip = Math.max(0, offset);
        String device = trim(deviceId);

        try {
            List<CommentRow> rows = mapper.selectBySong(key, device, size, skip);
            int total = mapper.countBySong(key);
            List<CommentDto> items = new ArrayList<>();
            if (rows != null) {
                for (CommentRow row : rows) {
                    items.add(toDto(row, device));
                }
            }
            page.setItems(items);
            page.setTotal(total);
            // 用「已经取回的条数」判断而不是 offset+size < total：
            // 中间有人删评论时 total 会变，后者会凭空多出一次空翻页
            page.setHasMore(skip + items.size() < total);
            return page;
        } catch (RuntimeException ex) {
            log.warn("评论列表读取失败：{}", ex.getMessage());
            return page;
        }
    }

    // ---------------------------------------------------------------- 写

    public CommentDto create(CommentBody body) {
        if (!available()) {
            throw new ServiceException("评论功能未就绪：biz_music_comment 表不存在，请先执行 sql/10_music_comment.sql");
        }
        if (body == null) {
            throw new ServiceException("请求体为空");
        }
        String deviceId = trim(body.getDeviceId());
        if (deviceId.isEmpty()) {
            throw new ServiceException("缺少设备标识");
        }
        if (deviceId.length() > MAX_DEVICE_ID) {
            deviceId = deviceId.substring(0, MAX_DEVICE_ID);
        }

        String content = trim(body.getContent());
        if (content.length() < MIN_CONTENT) {
            throw new ServiceException("评论内容不能为空");
        }
        if (content.length() > MAX_CONTENT) {
            throw new ServiceException("评论最多 " + MAX_CONTENT + " 个字");
        }

        String key = songKey(body.getTitle(), body.getArtist());
        if (key.isEmpty()) {
            throw new ServiceException("缺少歌名，无法定位这首歌");
        }

        try {
            if (mapper.countRecentByDevice(deviceId, RATE_WINDOW_SECONDS) >= RATE_MAX_COMMENTS) {
                throw new ServiceException("评论太频繁了，休息一下再发");
            }

            MusicComment comment = new MusicComment();
            comment.setSongKey(key);
            comment.setTrackKey(trim(body.getSourceKey()) + ":" + trim(body.getTrackId()));
            comment.setTrackTitle(limit(trim(body.getTitle()), 200));
            comment.setTrackArtist(limit(trim(body.getArtist()), 200));
            comment.setSourceKey(trim(body.getSourceKey()));
            comment.setDeviceId(deviceId);
            comment.setDeviceName(limit(trim(body.getDeviceName()), MAX_DEVICE_NAME));
            comment.setContent(content);
            mapper.insert(comment);

            CommentDto dto = new CommentDto();
            dto.setId(comment.getId());
            dto.setDeviceName(comment.getDeviceName());
            dto.setContent(content);
            dto.setLikeCount(0);
            dto.setLiked(false);
            dto.setOwn(true);
            // 不回填时间：客户端拿到后直接插到列表顶部，显示「刚刚」由它决定；
            // 为了一个格式化字符串再查一次库不值当
            dto.setCreateTime(null);
            return dto;
        } catch (ServiceException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            log.warn("评论写入失败：{}", ex.getMessage());
            throw new ServiceException("评论失败：" + ex.getMessage());
        }
    }

    /**
     * 点赞 / 取消点赞。
     *
     * @return 最新的点赞数
     */
    public int like(long commentId, String deviceId, boolean liked) {
        if (!available()) {
            throw new ServiceException("评论功能未就绪：biz_music_comment 表不存在");
        }
        String device = trim(deviceId);
        if (device.isEmpty()) {
            throw new ServiceException("缺少设备标识");
        }
        MusicComment comment = mapper.selectById(commentId);
        if (comment == null) {
            throw new ServiceException("评论不存在或已删除");
        }
        try {
            if (liked) {
                // 只在真的新插入时才 +1，重复点赞不会把计数刷上去
                if (mapper.insertLike(commentId, device) > 0) {
                    mapper.increaseLike(commentId);
                }
            } else {
                if (mapper.deleteLike(commentId, device) > 0) {
                    mapper.decreaseLike(commentId);
                }
            }
            Integer count = mapper.selectLikeCount(commentId);
            return count == null ? 0 : count;
        } catch (RuntimeException ex) {
            log.warn("点赞失败 commentId={}：{}", commentId, ex.getMessage());
            throw new ServiceException("操作失败：" + ex.getMessage());
        }
    }

    /** 删除自己的评论。别人的评论这里会返回 false，不区分「不存在」和「不是你的」。 */
    public boolean delete(long commentId, String deviceId) {
        if (!available()) {
            throw new ServiceException("评论功能未就绪：biz_music_comment 表不存在");
        }
        String device = trim(deviceId);
        if (device.isEmpty()) {
            return false;
        }
        return mapper.deleteOwn(commentId, device) > 0;
    }

    // ---------------------------------------------------------------- 归一化

    /**
     * 歌曲归属键：`归一化歌名|归一化主歌手`。
     *
     * 归一化只做两件事：转小写、去掉所有标点与空白（含全角）。
     *
     * **括号里的内容参与匹配**，这是刻意的：`晴天 (Live)` 和 `晴天` 是两条不同的
     * 评论流。听 Live 的人想聊的是这一场的编曲和现场感，把他扔进原版评论区
     * 只会让两边都聊不到一起。同理，伴奏、Remix 版本各自独立。
     *
     * 只取第一个歌手：`周杰伦/杨瑞代` 和 `周杰伦` 应该是同一首歌。
     *
     * 与 `RecommendService` 的去重规则保持一致 —— 那边也把 `(Live)` 当成不同的歌，
     * 如果评论合并而推荐不合并，就会出现「同一首歌两个地方算两种东西」的割裂。
     */
    public static String songKey(String title, String artist) {
        String name = normalize(title);
        String singer = normalize(mainArtist(artist));
        if (name.isEmpty()) {
            return "";
        }
        String key = name + "|" + singer;
        return key.length() > MAX_SONG_KEY ? key.substring(0, MAX_SONG_KEY) : key;
    }

    private static String mainArtist(String artist) {
        if (artist == null) {
            return "";
        }
        for (String part : ARTIST_SPLIT.split(artist)) {
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
        return PUNCTUATION.matcher(value.toLowerCase(Locale.ROOT)).replaceAll("").trim();
    }

    // ---------------------------------------------------------------- 工具

    private static CommentDto toDto(CommentRow row, String deviceId) {
        CommentDto dto = new CommentDto();
        dto.setId(row.getId());
        dto.setDeviceName(row.getDeviceName() == null ? "" : row.getDeviceName());
        dto.setContent(row.getContent());
        dto.setLikeCount(row.getLikeCount() == null ? 0 : row.getLikeCount());
        dto.setLiked(row.getLikedFlag() != null && row.getLikedFlag() == 1);
        dto.setOwn(!deviceId.isEmpty() && deviceId.equals(row.getDeviceId()));
        dto.setCreateTime(row.getCreateTime());
        return dto;
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private static String limit(String value, int max) {
        return value.length() > max ? value.substring(0, max) : value;
    }
}
