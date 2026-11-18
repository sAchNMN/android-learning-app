package com.ruoyi.music.source;

import com.ruoyi.music.domain.OnlinePlay;
import com.ruoyi.music.domain.OnlineTrack;

import java.util.List;

/**
 * 一个音乐平台的适配器。
 *
 * 与 Mineradio 的 provider 分层一一对应：
 * {@code qq-vip-api.js / kugou-api.js / server.js 里的 netease 分支}
 * 在这里各自是一个实现，聚合、换源、缓存由 {@link MusicSourceService} 统一负责。
 *
 * 约定：
 * - {@link #search} 允许抛异常，聚合层会把异常翻译成该平台的错误项，不拖垮整次搜索
 * - {@link #resolve} 不抛异常，失败返回 {@code playable=false} 的 {@link OnlinePlay}
 * - cookie 由调用方传入，适配器不自己读库、不缓存登录态
 */
public interface MusicSourceProvider {

    /** 平台标识，例如 netease / kugou。 */
    String key();

    /** 展示名，例如「网易云音乐」。 */
    String label();

    /**
     * 搜索歌曲。
     *
     * @param keyword 关键词
     * @param limit   期望条数
     * @param cookie  用户登录态，可为空串
     */
    List<OnlineTrack> search(String keyword, int limit, String cookie);

    /** 取播放地址。找不到时返回带 category/message 的失败结果。 */
    OnlinePlay resolve(OnlineTrack track, String cookie, String quality);

    /** 取歌词（LRC 文本），没有就返回空串。 */
    String lyric(OnlineTrack track, String cookie);
}