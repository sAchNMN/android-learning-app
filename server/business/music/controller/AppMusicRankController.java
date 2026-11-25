package com.ruoyi.music.controller;

import com.ruoyi.common.annotation.Anonymous;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.music.domain.ListenEventBody;
import com.ruoyi.music.service.MusicRankService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 榜单接口：一个写（播放事件）、一个读（聚合结果）。
 *
 * 匿名可访问 —— App 没有账号体系，`deviceId` 就是身份。
 * 这也意味着榜单**没有可信的用户边界**：能做的是让刷榜变得不划算
 * （防抖、每日额度、负分），而不是假装能认证。
 */
@Anonymous
@RestController
@RequestMapping("/app/music/rank")
public class AppMusicRankController {

    @Autowired
    private MusicRankService rankService;

    /**
     * 上报一条播放事件。
     *
     * 返回 `accepted` 与原因。客户端不看这个结果（埋点失败不该打断听歌），
     * 但它让"为什么榜是空的"可以在日志里查出来。
     */
    @PostMapping("/event")
    public AjaxResult report(@RequestBody ListenEventBody body) {
        return AjaxResult.success(rankService.report(body));
    }

    /**
     * 榜单。
     *
     * @param type  hot（热歌榜，默认）/ rising（飙升榜）
     * @param limit 条数，默认 50，上限 100
     */
    @GetMapping
    public AjaxResult list(
            @RequestParam(value = "type", required = false) String type,
            @RequestParam(value = "limit", required = false) Integer limit) {
        return AjaxResult.success(rankService.list(type, limit == null ? 0 : limit));
    }
}
