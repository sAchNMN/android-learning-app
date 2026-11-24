package com.ruoyi.music.source;

import com.ruoyi.music.domain.OnlinePlay;
import com.ruoyi.music.domain.OnlineTrack;
import com.ruoyi.music.domain.SyncResult;
import com.ruoyi.music.domain.UserPlaylist;

import java.util.ArrayList;
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

    // ------------------------------------------------------------ 账号能力（可选）
    //
    // 下面四个方法都有默认实现：只做「搜索 + 取址」的平台不用改一行代码。
    // 支持个人账号的平台（目前是网易云）覆写它们，其余平台自然退化成
    // 「主站记录 + 提示不支持」，聚合层不需要到处判空。

    /** 平台是否有「个人歌单 / 我喜欢」这类需要登录态的能力。 */
    default boolean supportsAccount() {
        return false;
    }

    /**
     * 平台侧推荐流：无偏好时的兜底推荐。
     *
     * 与 {@code search} 的区别是它不需要关键词 —— 平台自己按热度/新歌给一批，
     * 这样冷启动（新用户没有任何收藏）也有内容可看。
     */
    default List<OnlineTrack> dailyRecommend(int limit, String cookie) {
        return new ArrayList<>();
    }

    /**
     * 同步「喜欢 / 取消喜欢」到平台。
     *
     * 不抛异常：失败用 {@link SyncResult} 表达，聚合层会把多条结果一起回给客户端。
     */
    default SyncResult like(String trackId, boolean liked, String cookie) {
        return SyncResult.fail(key(), label() + " 暂不支持同步喜欢，已记录在本机与后台");
    }

    /** 账号里的歌单列表（自己建的 + 收藏的）。 */
    default List<UserPlaylist> myPlaylists(String cookie) {
        return new ArrayList<>();
    }

    /** 拉某个歌单的曲目。 */
    default List<OnlineTrack> playlistTracks(String playlistId, int limit, String cookie) {
        return new ArrayList<>();
    }
}