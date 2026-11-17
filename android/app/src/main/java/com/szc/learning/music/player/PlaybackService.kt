package com.szc.learning.music.player

import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/**
 * 后台播放服务。
 *
 * 播放器必须活在这个 Service 里而不是 Activity：Activity 一销毁播放就断，
 * 那不是「音乐播放软件」。
 *
 * MediaSessionService 会自动把 MediaSession 暴露给：
 * - 本进程的 [PlayerController]（MediaController 通过 SessionToken 连接）
 * - 通知栏 / 锁屏 / 蓝牙耳机
 * - 车机、手表等外部控制器
 *
 * 所以 UI 层完全不用自己写通知，也不用处理按键事件。
 */
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()

        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            // 拔耳机时自动暂停，否则会突然外放
            .setHandleAudioBecomingNoisy(true)
            .build()

        mediaSession = MediaSession.Builder(this, player).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    /** 用户从最近任务里划掉应用：没在播就顺手结束服务，别留一个空进程。 */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player ?: return
        if (!player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }
}
