package com.szc.learning.core.network.music

/**
 * 歌曲评论的 DTO 与领域模型。
 *
 * **DTO 里的 String 一律可空，转换时兜底**：Gson 走反射建对象，不走 Kotlin 构造器的
 * 默认参数。声明成非空 `String` 但服务端少返回一个字段时，拿到的是 null，
 * 然后在某个 `.isEmpty()` 里 NPE —— 这类崩溃在真机上极难定位。
 */
data class MusicComment(
    val id: Long,
    /** 空串由 UI 显示为「匿名听众」。 */
    val deviceName: String,
    val content: String,
    val likeCount: Int,
    val liked: Boolean,
    /** 是不是自己发的，决定长按能不能删。 */
    val own: Boolean,
    val createTime: String?,
)

data class CommentPage(
    val total: Int,
    val hasMore: Boolean,
    val items: List<MusicComment>,
)

/** 点赞结果：服务端回传最新计数，客户端不做加减。 */
data class CommentLikeResult(
    val liked: Boolean,
    val likeCount: Int,
)

// ---------------------------------------------------------------- 传输结构

data class CommentDto(
    val id: Long = 0,
    val deviceName: String? = null,
    val content: String? = null,
    val likeCount: Int = 0,
    val liked: Boolean = false,
    val own: Boolean = false,
    val createTime: String? = null,
)

data class CommentPageDto(
    val total: Int = 0,
    val hasMore: Boolean = false,
    val items: List<CommentDto>? = null,
)

data class CommentBodyDto(
    val deviceId: String,
    val deviceName: String,
    val title: String,
    val artist: String,
    val sourceKey: String,
    val trackId: String,
    val content: String,
)

data class CommentLikeBodyDto(
    val deviceId: String,
    val liked: Boolean,
)

data class CommentLikeResultDto(
    val liked: Boolean = false,
    val likeCount: Int = 0,
)

// ---------------------------------------------------------------- 转换

internal fun CommentDto.toComment(): MusicComment = MusicComment(
    id = id,
    deviceName = deviceName.orEmpty(),
    content = content.orEmpty(),
    likeCount = likeCount,
    liked = liked,
    own = own,
    createTime = createTime,
)

internal fun CommentPageDto.toPage(): CommentPage = CommentPage(
    total = total,
    hasMore = hasMore,
    items = items.orEmpty().map { it.toComment() },
)
