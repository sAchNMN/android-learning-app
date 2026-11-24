package com.szc.learning.music.ui.comments

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.szc.learning.R
import com.szc.learning.core.network.music.MusicComment
import com.szc.learning.databinding.ItemCommentBinding
import com.szc.learning.ui.theme.themeColor
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 评论列表。
 *
 * 点赞按钮是**独立点击区**，整项不响应单击 —— 评论正文要能被选中复制
 * （`textIsSelectable`），整项可点会和长按选词打架。
 * 长按整项用于删除自己的评论。
 */
class CommentAdapter(
    private val onLike: (MusicComment) -> Unit,
    private val onLongPress: (MusicComment) -> Unit,
) : ListAdapter<MusicComment, CommentAdapter.Holder>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemCommentBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class Holder(private val binding: ItemCommentBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(comment: MusicComment) {
            val context = binding.root.context
            binding.commentName.text = comment.deviceName.ifBlank {
                context.getString(R.string.comment_anonymous)
            }
            binding.commentTime.text = relativeTime(comment.createTime)
            binding.commentContent.text = comment.content

            // 0 赞不显示数字，只留一个空心 —— 一排「0」比留白更吵
            binding.commentLikeCount.text =
                if (comment.likeCount > 0) comment.likeCount.toString() else ""
            binding.commentLike.setImageResource(
                if (comment.liked) R.drawable.ic_heart_filled else R.drawable.ic_heart,
            )
            // 颜色跟着主题走（auLike / auInkTertiary），不写死色值，深浅色都不用改
            binding.commentLike.setColorFilter(
                binding.root.themeColor(
                    if (comment.liked) R.attr.auLike else R.attr.auInkTertiary,
                ),
            )
            binding.commentLike.setOnClickListener { onLike(comment) }
            binding.root.setOnLongClickListener {
                if (comment.own) {
                    onLongPress(comment)
                    true
                } else {
                    false
                }
            }
        }
    }

    private companion object {

        val DIFF = object : DiffUtil.ItemCallback<MusicComment>() {
            override fun areItemsTheSame(oldItem: MusicComment, newItem: MusicComment) =
                oldItem.id == newItem.id

            override fun areContentsTheSame(oldItem: MusicComment, newItem: MusicComment) =
                oldItem == newItem
        }

        /**
         * 「2026-11-18 15:09:03」→「刚刚 / 12 分钟前 / 3 小时前 / 11-18」。
         *
         * 解析失败就把原文吐回去 —— 时间格式变了也该能看见，而不是显示空白。
         */
        fun relativeTime(raw: String?): String {
            if (raw.isNullOrBlank()) return "刚刚"
            val parsed: Date = runCatching {
                SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).parse(raw)
            }.getOrNull() ?: return raw
            val diff = System.currentTimeMillis() - parsed.time
            return when {
                diff < 60_000L -> "刚刚"
                diff < 3_600_000L -> "${diff / 60_000L} 分钟前"
                diff < 86_400_000L -> "${diff / 3_600_000L} 小时前"
                else -> SimpleDateFormat("MM-dd", Locale.getDefault()).format(parsed)
            }
        }
    }
}
