package com.ruoyi.music.controller;

import com.ruoyi.common.annotation.Anonymous;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.music.domain.CommentBody;
import com.ruoyi.music.domain.CommentLikeBody;
import com.ruoyi.music.domain.LikeBody;
import com.ruoyi.music.domain.MusicShare;
import com.ruoyi.music.domain.MusicSourceDto;
import com.ruoyi.music.domain.OnlinePlay;
import com.ruoyi.music.domain.SearchResponse;
import com.ruoyi.music.domain.ShareBody;
import com.ruoyi.music.domain.SourceUpdateBody;
import com.ruoyi.music.service.MusicCommentService;
import com.ruoyi.music.service.MusicService;
import com.ruoyi.music.service.MusicShareService;
import com.ruoyi.music.service.MusicUserService;
import com.ruoyi.music.service.RecommendService;
import com.ruoyi.music.source.MusicSourceService;
import com.ruoyi.music.source.RequestCookies;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 云端曲库 + 在线音源接口。
 *
 * 分层和 Mineradio 一致：
 * - 曲库：`/tracks` 仍是上传到本站的 mp3
 * - 搜索：`/search` 聚合各平台，返回统一模型，不在客户端拼厂商字段
 * - 取址：`/play` 单平台取址，失败时服务端自动换源并告诉客户端换到了谁
 * - 管理：`/sources` 开关、优先级、Cookie
 *
 * 全部接口匿名可访问：App 本身不要求登录，音源 Cookie 是「平台登录态」而不是本站账号。
 * 因此 `/sources` 永远不返回 cookie 原文，只返回 hasCookie。
 */
@Anonymous
@RestController
@RequestMapping("/app/music")
public class AppMusicController {

    @Autowired
    private MusicService musicService;

    @Autowired
    private MusicSourceService sourceService;

    @Autowired
    private RecommendService recommendService;

    @Autowired
    private MusicUserService userService;

    @Autowired
    private MusicShareService shareService;

    @Autowired
    private MusicCommentService commentService;

    @GetMapping("/tracks")
    public AjaxResult tracks() {
        return AjaxResult.success(musicService.listAll());
    }

    /**
     * 聚合搜索。
     *
     * @param keyword 关键词
     * @param limit   每个平台最多取多少条，最终结果也会截断到这个数
     * @param sources 可选，逗号分隔的平台白名单，例如 `netease,kugou`
     */
    @GetMapping("/search")
    public AjaxResult search(@RequestParam String keyword,
                             @RequestParam(defaultValue = "20") int limit,
                             @RequestParam(required = false) String sources,
                             @RequestParam(required = false) String cookies) {
        try {
            SearchResponse response = sourceService.search(keyword, limit, sources, RequestCookies.parse(cookies));
            return AjaxResult.success(response);
        } catch (IllegalArgumentException | IllegalStateException ex) {
            return AjaxResult.error(ex.getMessage());
        } catch (RuntimeException ex) {
            return AjaxResult.error("在线搜索失败：" + ex.getMessage());
        }
    }

    /**
     * 取播放地址。失败返回 `playable=false` + `category`，不抛 500。
     *
     * `hash / albumId / albumAudioId` 只有酷狗需要；客户端把搜索时拿到的字段原样带回即可。
     */
    @GetMapping("/play")
    public AjaxResult play(@RequestParam String source,
                           @RequestParam String id,
                           @RequestParam(required = false) String title,
                           @RequestParam(required = false) String artist,
                           @RequestParam(required = false) String album,
                           @RequestParam(required = false) Long durationMs,
                           @RequestParam(required = false) String hash,
                           @RequestParam(required = false) String albumId,
                           @RequestParam(required = false) String albumAudioId,
                           @RequestParam(required = false) String quality,
                           @RequestParam(defaultValue = "true") boolean fallback,
                           @RequestParam(required = false) String cookies) {
        try {
            OnlinePlay play = sourceService.play(source, id, title, artist, album,
                    durationMs, hash, albumId, albumAudioId, quality, fallback, RequestCookies.parse(cookies));
            return AjaxResult.success(play);
        } catch (RuntimeException ex) {
            return AjaxResult.error("取播放地址失败：" + ex.getMessage());
        }
    }

    @GetMapping("/lyric")
    public AjaxResult lyric(@RequestParam String source,
                            @RequestParam String id,
                            @RequestParam(required = false) String hash,
                            @RequestParam(required = false) String albumId,
                            @RequestParam(required = false) String albumAudioId,
                            @RequestParam(required = false) Long durationMs,
                            @RequestParam(required = false) String title,
                            @RequestParam(required = false) String artist,
                            @RequestParam(required = false) String cookies) {
        return AjaxResult.success((Object) sourceService.lyric(source, id, hash, albumId, albumAudioId,
                durationMs, title, artist, RequestCookies.parse(cookies)));
    }

    @GetMapping("/sources")
    public AjaxResult sources() {
        List<MusicSourceDto> list = sourceService.listSources();
        return AjaxResult.success(list);
    }

    // ---------------------------------------------------------------- 推荐

    /**
     * 猜你喜欢。
     *
     * 口味种子由客户端算好传上来（收藏 + 最近播放里的歌手），
     * 客户端没有偏好时再从后台的喜欢记录里取 —— 两边都空就退回平台推荐流。
     *
     * @param seed 洗牌种子。不传按天固定，同一天多次进入顺序一致；点「换一批」传新值。
     */
    @GetMapping("/recommend")
    public AjaxResult recommend(@RequestParam(required = false) String deviceId,
                                @RequestParam(required = false) String artists,
                                @RequestParam(required = false) Long seed,
                                @RequestParam(defaultValue = "20") int limit,
                                @RequestParam(required = false) String cookies) {
        try {
            return AjaxResult.success(recommendService.recommend(
                    deviceId, artists, seed, limit, RequestCookies.parse(cookies)));
        } catch (RuntimeException ex) {
            return AjaxResult.error("推荐生成失败：" + ex.getMessage());
        }
    }

    // ---------------------------------------------------------------- 我喜欢

    /**
     * 喜欢 / 取消喜欢。
     *
     * 幂等：重复点同一个方向也返回成功。{@code saved} 表示后端库写入结果，
     * {@code syncs} 是各平台同步回执 —— 平台没登录不算失败。
     */
    @PostMapping("/like")
    public AjaxResult like(@RequestBody LikeBody body,
                           @RequestParam(required = false) String cookies) {
        if (body == null || body.getId() == null || body.getId().isBlank()) {
            return AjaxResult.error("缺少歌曲 id");
        }
        try {
            return AjaxResult.success(userService.like(body, RequestCookies.parse(cookies)));
        } catch (RuntimeException ex) {
            return AjaxResult.error("收藏失败：" + ex.getMessage());
        }
    }

    /** 我喜欢的曲目。App 重装后靠它把喜欢恢复回来。 */
    @GetMapping("/likes")
    public AjaxResult likes(@RequestParam String deviceId,
                            @RequestParam(defaultValue = "200") int limit) {
        return AjaxResult.success(userService.likes(deviceId, limit));
    }

    // ---------------------------------------------------------------- 个人歌单

    /** 合并各平台账号里的歌单。未登录的平台直接跳过。 */
    @GetMapping("/playlists")
    public AjaxResult playlists(@RequestParam(required = false) String cookies) {
        return AjaxResult.success(userService.playlists(RequestCookies.parse(cookies)));
    }

    /** 拉某个歌单的曲目；{@code name} 只是回显，客户端已经在卡片上有标题了。 */
    @GetMapping("/playlist")
    public AjaxResult playlist(@RequestParam String source,
                               @RequestParam String id,
                               @RequestParam(required = false) String name,
                               @RequestParam(defaultValue = "200") int limit,
                               @RequestParam(required = false) String cookies) {
        try {
            return AjaxResult.success(userService.playlist(source, id, name, limit, RequestCookies.parse(cookies)));
        } catch (RuntimeException ex) {
            return AjaxResult.error("歌单读取失败：" + ex.getMessage());
        }
    }

    /**
     * 更新音源开关 / 优先级 / Cookie。字段都可空，只传要改的那一项。
     * Cookie 传空串表示清除。
     *
     * @deprecated 客户端已改为本机存音源配置（`SourceConfigStore`），
     *             这个接口只保留给「首次初始化」和后台排查用。新功能不要再依赖它。
     */
    @Deprecated
    @PutMapping("/sources/{key}")
    public AjaxResult updateSource(@PathVariable String key, @RequestBody SourceUpdateBody body) {
        if (body == null) {
            return AjaxResult.error("请求体不能为空");
        }
        try {
            sourceService.updateSource(key, body);
            return AjaxResult.success("已保存");
        } catch (IllegalArgumentException | IllegalStateException ex) {
            return AjaxResult.error(ex.getMessage());
        } catch (RuntimeException ex) {
            return AjaxResult.error("保存失败：" + ex.getMessage());
        }
    }

    // ---------------------------------------------------------------- 歌单分享码

    /**
     * 生成歌单分享码。
     *
     * 这是整个项目里唯一必须依赖服务端的功能：短码要在中心分配，别的设备才查得到。
     * 收藏、推荐、音源配置都已经搬到客户端，只有这一件事客户端做不到。
     *
     * 曲目数组原样存、原样返回，后端不解析字段（跨音源曲目带平台私有参数）。
     */
    @PostMapping("/share")
    public AjaxResult createShare(@RequestBody ShareBody body) {
        try {
            return AjaxResult.success(MusicShareService.toResponse(shareService.create(body)));
        } catch (ServiceException ex) {
            return AjaxResult.error(ex.getMessage());
        } catch (RuntimeException ex) {
            return AjaxResult.error("分享码生成失败：" + ex.getMessage());
        }
    }

    /** 按码取歌单。码不存在或已过期都返回 404 语义，不给「是否曾经存在」的额外信息。 */
    @GetMapping("/share/{code}")
    public AjaxResult resolveShare(@PathVariable String code) {
        if (!shareService.available()) {
            return AjaxResult.error("分享功能未就绪：biz_music_share 表不存在");
        }
        MusicShare share = shareService.resolve(code);
        if (share == null) {
            return AjaxResult.error("分享码不存在或已过期");
        }
        return AjaxResult.success(MusicShareService.toResponse(share));
    }

    // ---------------------------------------------------------------- 歌曲评论

    /**
     * 某首歌的评论列表。
     *
     * 用 `title + artist` 而不是曲目 id 定位：同一首歌在网易云和酷我是两条记录，
     * 但评论应该共享。归一化在服务端做，客户端只管把原始字段传上来。
     */
    @GetMapping("/comments")
    public AjaxResult comments(@RequestParam(required = false) String title,
                               @RequestParam(required = false) String artist,
                               @RequestParam(required = false) String deviceId,
                               @RequestParam(defaultValue = "20") int limit,
                               @RequestParam(defaultValue = "0") int offset) {
        if (!commentService.available()) {
            return AjaxResult.error("评论功能未就绪：biz_music_comment 表不存在");
        }
        return AjaxResult.success(commentService.list(title, artist, deviceId, limit, offset));
    }

    /** 发表评论。内容长度与「同设备 60 秒 3 条」的频控在 Service 里。 */
    @PostMapping("/comments")
    public AjaxResult createComment(@RequestBody CommentBody body) {
        try {
            return AjaxResult.success(commentService.create(body));
        } catch (ServiceException ex) {
            return AjaxResult.error(ex.getMessage());
        }
    }

    /** 点赞 / 取消点赞。返回最新的点赞数，客户端不用自己加一减一。 */
    @PostMapping("/comments/{id}/like")
    public AjaxResult likeComment(@PathVariable long id, @RequestBody(required = false) CommentLikeBody body) {
        String deviceId = body == null ? null : body.getDeviceId();
        boolean liked = body == null || body.getLiked() == null || body.getLiked();
        try {
            int count = commentService.like(id, deviceId, liked);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("liked", liked);
            data.put("likeCount", count);
            return AjaxResult.success(data);
        } catch (ServiceException ex) {
            return AjaxResult.error(ex.getMessage());
        }
    }

    /** 删除自己的评论。别人的评论返回「只能删除自己的」，不透露它是否存在。 */
    @DeleteMapping("/comments/{id}")
    public AjaxResult deleteComment(@PathVariable long id, @RequestParam String deviceId) {
        try {
            return commentService.delete(id, deviceId)
                    ? AjaxResult.success("已删除")
                    : AjaxResult.error("只能删除自己的评论");
        } catch (ServiceException ex) {
            return AjaxResult.error(ex.getMessage());
        }
    }
}