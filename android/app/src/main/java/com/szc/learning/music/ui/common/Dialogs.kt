package com.szc.learning.music.ui.common

import android.content.Context
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.szc.learning.R

/** 几个重复出现的小弹窗，统一在这里维护文案和样式。 */
fun showListenStats(context: Context, minutes: Long, favorites: Int, recent: Int) {
    MaterialAlertDialogBuilder(context)
        .setTitle(R.string.mine_stats_title)
        .setMessage(context.getString(R.string.mine_stats_body, minutes, favorites, recent))
        .setPositiveButton(R.string.mine_ok, null)
        .show()
}