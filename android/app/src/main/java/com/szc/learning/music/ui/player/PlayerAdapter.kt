package com.szc.learning.music.ui.player

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.szc.learning.R
import com.szc.learning.core.common.music.MusicTrack
import com.szc.learning.core.common.music.formatDuration
import com.szc.learning.databinding.ItemPlayerBinding
import com.szc.learning.music.data.LrcParser
import com.szc.learning.music.data.LyricSheet
import com.szc.learning.music.player.PlayMode
import com.szc.learning.music.player.PlayerUiState
import com.szc.learning.music.ui.common.bindArt
import com.szc.learning.music.ui.common.iconRes
import com.szc.learning.music.ui.common.labelRes
import com.szc.learning.music.ui.motion.installSpringPress
import com.szc.learning.music.ui.motion.springPop
import com.szc.learning.music.ui.widget.dp
import com.szc.learning.ui.theme.themeColor

/**
 * 竖向播放页适配器。
 *
 * 和旧 MV 适配器最大的区别：这里**没有任何视频/独立播放器**。
 * 音频由全局 PlayerController 播放，页面只是同一份播放状态的多个视图。
 * 上下滑时换的是「正在显示哪首」，不是「启动/销毁第 N 个解码器」。
 */
class PlayerAdapter(
    private val favoriteKeys: () -> Set<String>,
    private val onToggleFavorite: (MusicTrack) -> Unit,
    private val onTogglePlay: () -> Unit,
    private val onPrevious: () -> Unit,
    private val onNext: () -> Unit,
    private val onCyclePlayMode: () -> Unit,
    private val onSeek: (Float) -> Unit,
    private val onSeekTo: (Long) -> Unit,
    private val onShowLyrics: (MusicTrack, String) -> Unit,
) : RecyclerView.Adapter<PlayerAdapter.Holder>() {

    private var tracks: List<MusicTrack> = emptyList()
    private val holders = mutableMapOf<Int, Holder>()
    private val lyrics = mutableMapOf<String, LyricSheet>()
    private var activePosition = 0
    private var state: PlayerUiState? = null
    private var topInsetPx = 0
    private var bottomInsetPx = 0

    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long = tracks[position].key.hashCode().toLong()

    override fun getItemCount(): Int = tracks.size

    fun submit(list: List<MusicTrack>) {
        tracks = list
        holders.clear()
        @Suppress("NotifyDataSetChanged")
        notifyDataSetChanged()
    }

    fun size(): Int = tracks.size

    fun trackAt(position: Int): MusicTrack? = tracks.getOrNull(position)

    fun setActive(position: Int) {
        activePosition = position
        holders.forEach { (index, holder) ->
            holder.render(state, active = index == position)
        }
    }

    fun setState(value: PlayerUiState) {
        state = value
        holders.forEach { (index, holder) ->
            holder.render(value, active = index == activePosition)
        }
    }

    fun setFavorites(keys: Set<String>) {
        holders.values.forEach { holder -> holder.renderFavorite(holder.track?.key in keys) }
    }

    fun setLyrics(key: String, text: String?) {
        lyrics[key] = LrcParser.parse(text)
        holders.values
            .filter { it.track?.key == key }
            .forEach { it.renderLyrics() }
    }

    fun setInsets(topPx: Int, bottomPx: Int) {
        topInsetPx = topPx
        bottomInsetPx = bottomPx
        holders.values.forEach { it.applyInsets() }
    }

    fun release() {
        holders.clear()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemPlayerBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(tracks[position], position)
        holders[position] = holder
    }

    override fun onViewAttachedToWindow(holder: Holder) {
        super.onViewAttachedToWindow(holder)
        val position = holder.boundPosition
        if (position == RecyclerView.NO_POSITION) return
        holders[position] = holder
        holder.render(state, active = position == activePosition)
    }

    override fun onViewRecycled(holder: Holder) {
        holders.remove(holder.boundPosition)
        super.onViewRecycled(holder)
    }

    inner class Holder(
        val binding: ItemPlayerBinding,
    ) : RecyclerView.ViewHolder(binding.root) {

        var track: MusicTrack? = null
            private set

        var boundPosition: Int = RecyclerView.NO_POSITION
            private set

        init {
            binding.playerSeek.apply {
                showKnob = true
                onDark = true
                onSeekFinished = { fraction -> onSeek(fraction) }
            }
            binding.playerPlay.setOnClickListener { onTogglePlay() }
            binding.playerPrev.setOnClickListener { onPrevious() }
            binding.playerNext.setOnClickListener { onNext() }
            binding.playerMode.setOnClickListener {
                onCyclePlayMode()
                binding.playerMode.springPop(startScale = 0.78f, durationSeconds = 0.4f)
            }
            binding.playerFavorite.setOnClickListener {
                track?.let {
                    onToggleFavorite(it)
                    binding.playerFavorite.springPop(
                        startScale = 0.72f,
                        durationSeconds = 0.52f,
                        bounce = 0.55f,
                    )
                }
            }
            binding.playerLyrics.onLineClick = { line ->
                val current = track
                when {
                    current == null -> Unit
                    line.timeMs >= 0 -> onSeekTo(line.timeMs)
                    else -> {
                        val text = lyrics[current.key]?.lines
                            ?.joinToString("\n") { it.text }
                        if (!text.isNullOrBlank()) onShowLyrics(current, text)
                    }
                }
            }

            listOf<View>(
                binding.playerPlay,
                binding.playerPrev,
                binding.playerNext,
                binding.playerMode,
                binding.playerFavorite,
            ).forEach { it.installSpringPress(0.9f) }
        }

        fun bind(item: MusicTrack, position: Int) {
            track = item
            boundPosition = position
            val context = binding.root.context

            binding.playerTitle.text = item.title
            binding.playerArtist.text = item.artist.ifBlank {
                context.getString(R.string.player_unknown_artist)
            }
            binding.playerAlbum.text = item.album.ifBlank {
                context.getString(R.string.player_unknown_album)
            }
            binding.playerCover.bindArt(item, sizePx = 720)
            binding.playerBackdrop.bindArt(item, sizePx = 720)

            renderFavorite(item.key in favoriteKeys())
            renderLyrics()
            applyInsets()
            render(state, active = position == activePosition)
        }

        fun applyInsets() {
            if (topInsetPx <= 0 && bottomInsetPx <= 0) return
            binding.playerContent.setPadding(
                binding.root.context.dp(24f).toInt(),
                topInsetPx,
                binding.root.context.dp(24f).toInt(),
                bottomInsetPx,
            )
        }

        fun renderFavorite(favorite: Boolean) {
            val context = binding.root.context
            binding.playerFavorite.setImageResource(
                if (favorite) R.drawable.ic_heart_filled else R.drawable.ic_heart,
            )
            binding.playerFavorite.imageTintList = ColorStateList.valueOf(
                context.themeColor(if (favorite) R.attr.auLike else R.attr.auOnDark),
            )
            binding.playerFavorite.contentDescription = context.getString(
                if (favorite) R.string.player_unlike else R.string.player_like,
            )
        }

        fun renderLyrics() {
            val current = track ?: return
            val sheet = lyrics[current.key]
            val context = binding.root.context
            when {
                sheet == null -> binding.playerLyrics.showPlaceholder(
                    context.getString(R.string.player_lyrics_loading),
                )

                sheet.isEmpty -> binding.playerLyrics.showPlaceholder(
                    context.getString(R.string.player_lyrics_none),
                )

                else -> binding.playerLyrics.setSheet(
                    sheet,
                    if (boundPosition == activePosition) state?.positionMs ?: 0L else 0L,
                )
            }
        }

        fun render(value: PlayerUiState?, active: Boolean) {
            val context = binding.root.context
            val playing = value?.isPlaying == true
            binding.playerPlay.setImageResource(
                if (playing) R.drawable.ic_pause else R.drawable.ic_play,
            )
            binding.playerPlay.contentDescription = context.getString(
                if (playing) R.string.player_pause else R.string.player_play,
            )

            val mode = value?.playMode ?: PlayMode.SEQUENCE
            binding.playerMode.setImageResource(mode.iconRes)
            binding.playerMode.contentDescription = context.getString(
                R.string.player_mode_switch,
                context.getString(mode.labelRes),
            )

            val positionMs = if (active) value?.positionMs ?: 0L else 0L
            val durationMs = if (active && (value?.durationMs ?: 0L) > 0L) {
                value?.durationMs ?: 0L
            } else {
                track?.durationMs ?: 0L
            }
            renderProgress(positionMs, durationMs)
            if (active) binding.playerLyrics.updateProgress(positionMs)
        }

        private fun renderProgress(positionMs: Long, durationMs: Long) {
            binding.playerSeek.progress = if (durationMs > 0L) {
                (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
            } else {
                0f
            }
            binding.playerPosition.text = formatDuration(positionMs)
            binding.playerDuration.text = formatDuration(durationMs)
        }
    }
}