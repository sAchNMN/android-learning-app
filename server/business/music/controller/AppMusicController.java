package com.ruoyi.music.controller;

import com.ruoyi.common.annotation.Anonymous;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.music.domain.MusicSourceDto;
import com.ruoyi.music.domain.OnlinePlay;
import com.ruoyi.music.domain.SearchResponse;
import com.ruoyi.music.domain.SourceUpdateBody;
import com.ruoyi.music.service.MusicService;
import com.ruoyi.music.source.MusicSourceService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

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
                             @RequestParam(required = false) String sources) {
        try {
            SearchResponse response = sourceService.search(keyword, limit, sources);
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
                           @RequestParam(defaultValue = "true") boolean fallback) {
        try {
            OnlinePlay play = sourceService.play(source, id, title, artist, album,
                    durationMs, hash, albumId, albumAudioId, quality, fallback);
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
                            @RequestParam(required = false) String artist) {
        return AjaxResult.success((Object) sourceService.lyric(source, id, hash, albumId, albumAudioId,
                durationMs, title, artist));
    }

    @GetMapping("/sources")
    public AjaxResult sources() {
        List<MusicSourceDto> list = sourceService.listSources();
        return AjaxResult.success(list);
    }

    /**
     * 更新音源开关 / 优先级 / Cookie。字段都可空，只传要改的那一项。
     * Cookie 传空串表示清除。
     */
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
}