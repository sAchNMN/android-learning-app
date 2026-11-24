package com.szc.learning.music.ui.common

import androidx.fragment.app.FragmentActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.szc.learning.databinding.DialogQueueBinding
import com.szc.learning.music.ui.MusicViewModel
import com.szc.learning.music.ui.track.TrackAdapter

/** 播放队列弹层：全屏播放页、播放 tab、迷你播放器共用同一份。 */
fun showQueueSheet(activity: FragmentActivity, viewModel: MusicViewModel) {
    val dialog = BottomSheetDialog(activity)
    val sheet = DialogQueueBinding.inflate(activity.layoutInflater)
    dialog.setContentView(sheet.root)

    val adapter = TrackAdapter(activeKey = viewModel.playerState.value.track?.key) { index ->
        viewModel.playAt(index)
        dialog.dismiss()
    }
    sheet.queueList.layoutManager = LinearLayoutManager(activity)
    sheet.queueList.adapter = adapter
    adapter.submitList(viewModel.queue.value)

    dialog.show()
}
