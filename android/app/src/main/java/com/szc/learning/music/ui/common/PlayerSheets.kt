package com.szc.learning.music.ui.common

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.szc.learning.R
import com.szc.learning.databinding.DialogQueueBinding
import com.szc.learning.music.ui.MusicViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * 播放队列弹层：全屏播放页、播放 tab、迷你播放器共用同一份。
 *
 * 可编辑：长按整行拖动排序，左滑移除，右上角清空（保留正在播的那首）。
 * 队列和播放页的 ViewPager 是同一批歌，所以每次改动都走 ViewModel ——
 * 它同时改播放器时间线和 feed，页面那边才不会翻出错位的页。
 *
 * 正在播的那首不允许移除：删掉它，音乐要么当场停，要么跳到下一首，
 * 而播放页此刻还拿着旧曲目算位置，会从第一首重新起播。留着最省事，
 * 「清空」也遵守同一条规则。
 */
fun showQueueSheet(activity: FragmentActivity, viewModel: MusicViewModel) {
    val dialog = BottomSheetDialog(activity)
    val sheet = DialogQueueBinding.inflate(activity.layoutInflater)
    dialog.setContentView(sheet.root)

    fun renderCount(count: Int) {
        sheet.queueCount.text = activity.getString(R.string.queue_count, count)
        // 只剩一首时清空没有意义，按钮置灰而不是让用户点了没反应
        val clearable = count > 1
        sheet.queueClear.isEnabled = clearable
        sheet.queueClear.alpha = if (clearable) 1f else DISABLED_ALPHA
    }

    val adapter = QueueAdapter(
        onPick = { index ->
            viewModel.playAt(index)
            dialog.dismiss()
        },
        onMove = { from, to -> viewModel.moveInQueue(from, to) },
        // 返回 false（删的是正在播的那首）时 adapter 会把行还原，不再弹提示：
        // BottomSheet 是独立窗口，HudToast 挂在 Activity 上会被它盖住，用户看不到
        onRemove = { index -> viewModel.removeFromQueue(index) },
        onCountChanged = ::renderCount,
    )

    sheet.queueList.layoutManager = LinearLayoutManager(activity)
    sheet.queueList.adapter = adapter
    adapter.submit(viewModel.queue.value)
    renderCount(adapter.itemCount)

    touchHelper(adapter) { adapter.submit(viewModel.queue.value) }
        .attachToRecyclerView(sheet.queueList)

    sheet.queueClear.setOnClickListener {
        if (adapter.itemCount <= 1) return@setOnClickListener
        MaterialAlertDialogBuilder(activity)
            .setMessage(R.string.queue_clear_confirm)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.queue_clear) { _, _ ->
                viewModel.clearQueue()
                adapter.submit(viewModel.queue.value)
            }
            .show()
    }

    // 跟着播放状态走：换歌换高亮，推荐续流会在队尾追加，列表要跟上
    var observeJob: Job? = null
    dialog.setOnShowListener {
        observeJob = activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.playerState.collect { adapter.setActive(it.track?.key) } }
                // post 一帧再刷：队列可能在 RecyclerView 滚动/布局过程中被改（推荐续流），
                // 那时候直接 notifyDataSetChanged 会崩
                launch {
                    viewModel.queue.collect { list -> sheet.queueList.post { adapter.submit(list) } }
                }
            }
        }
        adapter.setActive(viewModel.playerState.value.track?.key)
    }
    dialog.setOnDismissListener { observeJob?.cancel() }

    dialog.show()
}

/**
 * 拖动排序 + 左滑移除。
 *
 * 拖动的落点要同步返回，所以先改 adapter 的本地列表、再回调 ViewModel，
 * 顺序不能反 —— ItemTouchHelper 不接受「稍后告诉你结果」。
 * 两边在同一帧里改完，队列 collect 回调到达时内容已经一致，不会整表重建；
 * 否则被拖着的行会因为重建弹回原位。
 *
 * @param onDragFinished 拖动结束时用真实队列校正一次：拖动期间被丢掉的
 *        追加（推荐续流）在这时候补上
 */
private fun touchHelper(
    adapter: QueueAdapter,
    onDragFinished: () -> Unit,
): ItemTouchHelper = ItemTouchHelper(
    object : ItemTouchHelper.SimpleCallback(
        ItemTouchHelper.UP or ItemTouchHelper.DOWN,
        ItemTouchHelper.START or ItemTouchHelper.END,
    ) {

        /** 左滑露出的底色。用强调色会被读成「选中」，红色才读得出「删除」。 */
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#E5484D")
        }

        override fun onMove(
            recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder,
            target: RecyclerView.ViewHolder,
        ): Boolean {
            val from = viewHolder.bindingAdapterPosition
            val to = target.bindingAdapterPosition
            if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) return false
            return adapter.moveItem(from, to)
        }

        override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
            val position = viewHolder.bindingAdapterPosition
            if (position != RecyclerView.NO_POSITION) adapter.removeAt(position)
        }

        override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
            super.onSelectedChanged(viewHolder, actionState)
            if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) adapter.dragging = true
        }

        override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
            super.clearView(recyclerView, viewHolder)
            adapter.dragging = false
            // 必须 post 到下一帧：clearView 还在 RecyclerView 的布局/滚动计算里，
            // 这里直接 notifyDataSetChanged 会抛 "Cannot call this method while
            // RecyclerView is computing a layout or scrolling"
            recyclerView.post(onDragFinished)
        }

        /**
         * 只在被拉开的那一侧画红底，不整行铺色：
         * 行背景是透明的，整行铺色会透到封面和文字底下，看起来像选中态。
         */
        override fun onChildDraw(
            canvas: Canvas,
            recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder,
            dX: Float,
            dY: Float,
            actionState: Int,
            isCurrentlyActive: Boolean,
        ) {
            val item = viewHolder.itemView
            if (actionState == ItemTouchHelper.ACTION_STATE_SWIPE && dX != 0f) {
                paint.alpha = (abs(dX) / item.width * MAX_SWIPE_ALPHA).toInt()
                    .coerceIn(0, MAX_SWIPE_ALPHA)
                val left = if (dX < 0) item.right + dX else item.left.toFloat()
                val right = if (dX < 0) item.right.toFloat() else item.left + dX
                canvas.drawRect(left, item.top.toFloat(), right, item.bottom.toFloat(), paint)
            }
            super.onChildDraw(
                canvas, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive,
            )
        }
    },
)

private const val DISABLED_ALPHA = 0.32f

/** 拉满时的红底不透明度。全不透明太扎眼，220/255 已经够看出是删除区。 */
private const val MAX_SWIPE_ALPHA = 220
