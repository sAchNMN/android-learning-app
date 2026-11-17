package com.ruoyi.music.controller;

import com.ruoyi.common.annotation.Anonymous;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.music.service.MusicService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 云端曲库接口。
 *
 * 只有一个接口：全量列表。音频本身走若依自带的 `/profile/**` 静态映射，
 * 不自己写流式响应 —— Range 请求、断点续传、缓存都由 Spring 的 ResourceHttpRequestHandler 负责，
 * 自己用 ServletOutputStream 抄一遍只会丢掉这些能力。
 *
 * 曲库是公开数据，类上加 @Anonymous 由若依的 PermitAllUrlProperties 在启动时自动放行，
 * 不用去改 RuoYi-Vue 里被 gitignore 的 SecurityConfig（重新 clone 也不会丢）。
 */
@Anonymous
@RestController
@RequestMapping("/app/music")
public class AppMusicController {

    @Autowired
    private MusicService musicService;

    @GetMapping("/tracks")
    public AjaxResult tracks() {
        return AjaxResult.success(musicService.listAll());
    }
}
