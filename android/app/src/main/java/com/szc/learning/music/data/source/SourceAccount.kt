package com.szc.learning.music.data.source

import com.szc.learning.core.common.music.MusicTrack
import com.szc.learning.core.network.music.UserPlaylistInfo

/**
 * 登录后能读到的账号资料。
 *
 * 平台之间能拿到的字段差别很大，所以这里不用 nullable 堆一堆可选字段，
 * 而是**拿不到就留空**，UI 按空值决定这一行显不显示：
 *
 * - 网易云：昵称、头像、会员类型、等级、累计听歌、关注/粉丝、注册时间、
 *   最近登录、歌单数量，以及歌单本身（`/api/nuser/account/get` +
 *   `/api/v1/user/detail/{uid}` + `/api/user/playlist`）。
 * - 酷狗：网页版没有公开的用户资料接口（网关要求签名，`mobilecdn` 直接
 *   `Access Deny`），但网页登录写进去的 `KuGoo=` Cookie 本身带了 `NickName`
 *   和 `Pic`（头像），本地解出来即可，**零请求**。等级/关注这些它一个都不给，
 *   所以 [statsFromPlatform] 保持 false，页面不会摆四个 0。
 *
 * [fetchedAt] 是拉取时间戳，UI 用它说明「数据是几分钟前的」，也用来算缓存是否过期。
 */
data class SourceAccount(
    val sourceKey: String,
    val userId: String,
    val nickname: String = "",
    val avatarUrl: String = "",
    /** 网易云的会员类型原值：0 普通 / 10 音乐包 / 11 黑胶 VIP。文案在 UI 层映射。 */
    val vipType: Int = 0,
    /** 听歌等级。酷狗没有，为 0。 */
    val level: Int = 0,
    /** 累计听歌数。 */
    val listenSongs: Long = 0,
    val follows: Int = 0,
    val followeds: Int = 0,
    val playlistCount: Int = 0,
    val createTimeMs: Long = 0,
    val lastLoginMs: Long = 0,
    val signature: String = "",
    /** 1 男 / 2 女 / 0 未填。 */
    val gender: Int = 0,
    /** 生日时间戳。平台允许用户乱填（比如填成注册当天），UI 只做展示不做推断。 */
    val birthdayMs: Long = 0,
    val playlists: List<UserPlaylistInfo> = emptyList(),
    /** 平台侧的听歌排行。网易云取 `weekData`，为空时退到 `allData`。酷狗没有公开接口。 */
    val recentTracks: List<MusicTrack> = emptyList(),
    /** [recentTracks] 的播放次数，key 是 `MusicTrack.key`。排行没有次数就没有意义。 */
    val recentPlayCounts: Map<String, Long> = emptyMap(),
    /** 听歌排行是否为一周窗口。false 表示这份是全量排行，标题要改口径。 */
    val recentWeekly: Boolean = false,
    /**
     * 平台是否真的下发了听歌统计（等级 / 累计听歌 / 关注粉丝）。
     *
     * 和 [hasProfile] 分开判断：酷狗能从 Cookie 本地解出昵称和头像，头部有内容，
     * 但等级、关注数这些它一个都给不了。合成一个标志会让酷狗页显示四个 0。
     */
    val statsFromPlatform: Boolean = false,
    /** 平台差异说明的补充位，正常为空；文案由 UI 决定。 */
    val note: String = "",
    val fetchedAt: Long = System.currentTimeMillis(),
) {
    /** 有没有可显示的头部资料（昵称或头像）。 */
    val hasProfile: Boolean get() = nickname.isNotBlank() || avatarUrl.isNotBlank()
}
