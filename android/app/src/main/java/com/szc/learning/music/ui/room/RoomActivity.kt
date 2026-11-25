package com.szc.learning.music.ui.room

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.color.MaterialColors
import com.szc.learning.LearningApp
import com.szc.learning.R
import com.szc.learning.core.common.toast
import com.szc.learning.databinding.ActivityRoomBinding
import com.szc.learning.music.data.room.SyncMode
import com.szc.learning.music.data.room.TogetherUiState
import kotlinx.coroutines.launch

/**
 * 一起听房间界面。
 *
 * 界面只做两件事：把房间码递出去、把同步状态显示出来。
 * **不放任何播放控制** —— 房间里的播放由房主的播放页驱动，
 * 这里再放一套按钮就会出现两个真相互相覆盖。
 *
 * 按返回键只是离开这个页面，房间和同步继续跑（作用域挂在 App 级的 container 上）。
 * 真要退出得点"退出房间"，这是个刻意的区分：
 * 一边听歌一边翻别的页面是常态，退出去看个歌词不该把房间散了。
 */
class RoomActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRoomBinding

    private val manager get() = (application as LearningApp).container.togetherListenManager
    private val myDeviceId get() = (application as LearningApp).container.deviceIdStore.id

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRoomBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.roomBack.setOnClickListener { finish() }
        binding.roomCreateButton.setOnClickListener { connect(create = true) }
        binding.roomJoinButton.setOnClickListener { connect(create = false) }
        binding.roomLeaveButton.setOnClickListener { manager.leave() }
        binding.roomCopy.setOnClickListener { copyCode() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { manager.state.collect { render(it) } }
                launch { manager.events.collect { message -> toast(message) } }
            }
        }
    }

    private fun connect(create: Boolean) {
        val nickname = nickname()
        lifecycleScope.launch {
            if (create) {
                manager.create(nickname)
            } else {
                val code = binding.roomCodeInput.text.toString().trim()
                if (code.length < CODE_LENGTH) {
                    toast(getString(R.string.room_code_hint))
                    return@launch
                }
                manager.join(code, nickname)
            }
        }
    }

    private fun render(state: TogetherUiState) {
        binding.roomLobby.visibility = if (state.active) View.GONE else View.VISIBLE
        binding.roomBody.visibility = if (state.active) View.VISIBLE else View.GONE
        binding.roomCreateButton.isEnabled = !state.busy
        binding.roomJoinButton.isEnabled = !state.busy

        if (!state.active) return

        binding.roomCodeText.text = state.roomCode
        binding.roomModeBadge.text = when (state.mode) {
            SyncMode.LAN -> getString(R.string.room_mode_lan)
            SyncMode.WAN -> getString(R.string.room_mode_wan)
            null -> ""
        }
        binding.roomLatency.text = if (state.latencyMs >= 0) {
            getString(R.string.room_latency, state.latencyMs)
        } else {
            getString(R.string.room_latency_measuring)
        }
        binding.roomMemberTitle.text = getString(R.string.room_member_count, state.members.size)
        renderMembers(state)
    }

    /**
     * 成员列表直接重建。
     *
     * 最多 8 个人，重建的成本远低于维护 diff —— 用 RecyclerView 的话还得处理
     * "哪一行变了"的动画，而这里的变化频率是"有人进房间"级别，动画没有意义。
     */
    private fun renderMembers(state: TogetherUiState) {
        val container = binding.roomMembers
        container.removeAllViews()
        val ink = MaterialColors.getColor(container, R.attr.auInk)
        val dim = MaterialColors.getColor(container, R.attr.auInkSecondary)
        state.members.forEach { member ->
            val label = buildString {
                append(member.nickname.ifBlank { "听友" })
                if (member.host) append("  ·  ${getString(R.string.room_host_badge)}")
                if (member.deviceId == myDeviceId) append("  ·  ${getString(R.string.room_me_badge)}")
            }
            container.addView(
                TextView(this).apply {
                    text = label
                    textSize = 15f
                    setTextColor(if (member.host) ink else dim)
                    setPadding(0, 10.dp(), 0, 10.dp())
                },
            )
        }
    }

    private fun copyCode() {
        val code = manager.state.value.roomCode
        if (code.isBlank()) return
        val clipboard = getSystemService(ClipboardManager::class.java) ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText("room", code))
        toast(getString(R.string.room_code_copied))
    }

    /** 昵称取机型，模拟器那种超长名字截断一下，不然成员列表会被撑爆。 */
    private fun nickname(): String {
        val model = runCatching { Build.MODEL }.getOrNull().orEmpty()
        return model.take(12).ifBlank { "听友" }
    }

    private fun Int.dp(): Int = (this * resources.displayMetrics.density).toInt()

    private companion object {
        const val CODE_LENGTH = 6
    }
}
