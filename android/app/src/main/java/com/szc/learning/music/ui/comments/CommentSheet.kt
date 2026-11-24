package com.szc.learning.music.ui.comments

import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.szc.learning.R
import com.szc.learning.core.common.music.MusicTrack
import com.szc.learning.core.common.toast
import com.szc.learning.core.network.music.MusicComment
import com.szc.learning.databinding.DialogCommentsBinding
import com.szc.learning.music.ui.CommentUiState
import com.szc.learning.music.ui.MusicViewModel
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 歌曲评论弹层。
 *
 * 生命周期跟着 BottomSheet 走：`dialog.setOnDismissListener { job.cancel() }`。
 * 不 cancel 的话，弹层关掉后 collect 还在跑，下次打开会叠一份新的收集者，
 * 一次点赞会触发两三次请求 —— 这类泄漏在真机上表现为「点赞数偶尔跳两下」。
 *
 * 状态全部来自 `MusicViewModel.comments`，这里不做任何本地增删：
 * 乐观更新、失败回滚都在 ViewModel 里，一处实现一处修。
 */
class CommentSheet(
    private val activity: FragmentActivity,
    private val viewModel: MusicViewModel,
    private val track: MusicTrack,
) {

    fun show() {
        val dialog = BottomSheetDialog(activity)
        val binding = DialogCommentsBinding.inflate(activity.layoutInflater)
        dialog.setContentView(binding.root)

        // 高度不再依赖 BottomSheetBehavior：
        // `setContentView` 会把根布局改写成 MATCH_PARENT × WRAP_CONTENT，
        // 而 isFitToContents / expandedOffset 在 show 前后设都会被重新测量覆盖掉，
        // 结果是列表的 `0dp + weight` 拿不到高度、整块内容挤成一条缝。
        // 直接给根布局一个最小高度，让 weight 有空间可分，行为可预测得多。
        binding.commentsRoot.minimumHeight =
            (activity.resources.displayMetrics.heightPixels * 0.72f).toInt()
        dialog.behavior.skipCollapsed = true
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

        val adapter = CommentAdapter(
            onLike = { viewModel.toggleCommentLike(it.id) },
            onLongPress = { confirmDelete(it) },
        )
        binding.commentsList.layoutManager = LinearLayoutManager(activity)
        binding.commentsList.adapter = adapter
        binding.commentsSong.text = listOf(track.title, track.artist)
            .filter { it.isNotBlank() }
            .joinToString(" · ")

        val job: Job = activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.comments.collect { render(binding, adapter, it) } }
                launch { viewModel.commentNotice.collect { activity.toast(it) } }
            }
        }
        dialog.setOnDismissListener { job.cancel() }

        binding.commentsClose.setOnClickListener { dialog.dismiss() }
        binding.commentsSend.setOnClickListener { send(binding) }
        binding.commentsInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                send(binding)
                true
            } else {
                false
            }
        }

        // 滑到离底部还有 3 条就预取下一页，别等滚到底再转圈
        binding.commentsList.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (dy <= 0) return
                val manager = recyclerView.layoutManager as? LinearLayoutManager ?: return
                if (manager.findLastVisibleItemPosition() >= adapter.itemCount - 3) {
                    viewModel.loadComments(track, reset = false)
                }
            }
        })

        dialog.show()
        viewModel.loadComments(track, reset = true)
    }

    private fun render(
        binding: DialogCommentsBinding,
        adapter: CommentAdapter,
        state: CommentUiState,
    ) {
        adapter.submitList(state.items)
        binding.commentsProgress.visibility =
            if (state.loading && state.items.isEmpty()) View.VISIBLE else View.GONE
        binding.commentsEmpty.visibility = if (state.isEmpty) View.VISIBLE else View.GONE
        // 只有「一条都没有」时才把错误占满中间；翻页失败时列表还在，错误不该盖住它
        binding.commentsError.visibility =
            if (!state.error.isNullOrBlank() && state.items.isEmpty()) View.VISIBLE else View.GONE
        binding.commentsError.text = state.error.orEmpty()
        binding.commentsTitle.text = if (state.total > 0) {
            activity.getString(R.string.comment_title_count, state.total)
        } else {
            activity.getString(R.string.comment_title)
        }
        binding.commentsSend.isEnabled = !state.posting
        binding.commentsSend.alpha = if (state.posting) 0.45f else 1f
    }

    private fun send(binding: DialogCommentsBinding) {
        val text = binding.commentsInput.text?.toString().orEmpty()
        viewModel.postComment(track, text) { error ->
            if (error == null) {
                binding.commentsInput.setText("")
                binding.commentsList.scrollToPosition(0)
                activity.toast(R.string.comment_posted)
            } else {
                activity.toast(error)
            }
        }
    }

    private fun confirmDelete(comment: MusicComment) {
        AlertDialog.Builder(activity)
            .setMessage(R.string.comment_delete_confirm)
            .setPositiveButton(R.string.comment_delete) { _, _ ->
                viewModel.deleteComment(comment.id)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
