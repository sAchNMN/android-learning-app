package com.szc.learning.music.ui.player

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
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
    private val onShowQueue: () -> Unit,
    private val onDislike: (MusicTrack) -> Unit,
    private val onShowComments: (MusicTrack) -> Unit,
    private val onMore: () -> Unit,
    private val onFullscreen: () -> Unit,
) : RecyclerView.Adapter<PlayerAdapter.Holder>() {

    private var tracks: List<MusicTrack> = emptyList()

    /**
     * 存活中的 holder。
     *
     * 用 Set 存 holder 本身、靠 `boundPosition` 判断它是哪一页 —— 原来是
     * `Map<position, holder>`，但 submit 每次都要 `clear()` 才能保证键不失效，
     * 而 clear + 全量重绑正是「推荐追一批歌，当前页闪一下」的根源。
     * 换成增量通知后，位置会变（插入/删除），键就不可靠了，只认 holder 自己记的位置。
     */
    private val holders = mutableSetOf<Holder>()

    /**
     * 歌词解析结果。
     *
     * 按访问顺序的 LRU：播放页上下滑几十首后，每首的解析结果都留着只增不减。
     * 只影响缓存命中，重新解析的结果完全一样。
     */
    private val lyrics: MutableMap<String, LyricSheet> =
        object : LinkedHashMap<String, LyricSheet>(16, 0.75f, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<String, LyricSheet>?,
            ): Boolean = size > LYRICS_CACHE_MAX
        }

    private var activePosition = 0
    private var state: PlayerUiState? = null
    private var topInsetPx = 0
    private var bottomInsetPx = 0

    /** 当前页的播放进度与时长。进度来自独立流，只在 [setPosition] 里更新。 */
    private var positionMs = 0L
    private var durationMs = 0L

    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long = tracks[position].key.hashCode().toLong()

    override fun getItemCount(): Int = tracks.size

    /**
     * 换一批曲目。
     *
     * 用 DiffUtil 而不是全量重绑：播放页的队列会「尾部追加」（歌曲推荐续流），
     * 全量重绑会让 ViewPager2 把当前页拆了重建，用户看到的是闪一下、偶尔还跳回第一页。
     * 增量通知下，尾部追加只是 `notifyItemRangeInserted`，当前页的 holder 完全不动。
     *
     * 比较依据是 `track.key`（跨来源唯一），内容相同就不重画 —— 同一首歌换了个
     * 播放地址时 `key` 不变、`uri` 变，仍会正确刷新。
     */
    fun submit(list: List<MusicTrack>) {
        if (tracks == list) return
        val old = tracks
        val diff = DiffUtil.calculateDiff(
            object : DiffUtil.Callback() {
                override fun getOldListSize(): Int = old.size

                override fun getNewListSize(): Int = list.size

                override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
                    old[oldItemPosition].key == list[newItemPosition].key

                override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
                    old[oldItemPosition] == list[newItemPosition]
            },
        )
        tracks = list
        diff.dispatchUpdatesTo(this)
    }

    fun size(): Int = tracks.size

    fun trackAt(position: Int): MusicTrack? = tracks.getOrNull(position)

    fun setActive(position: Int) {
        if (activePosition != position) positionMs = 0L
        activePosition = position
        holders.forEach { holder ->
            holder.render(state, active = holder.boundPosition == position)
        }
    }

    /**
     * 进度推进。只碰当前页的进度条、时间文字和歌词高亮。
     *
     * 走独立的 position 流而不是 [setState]：整份状态每 350ms 重建一次，
     * ViewPager2 里所有存活页面都会被重绑一遍，滑动时会掉帧。
     */
    fun setPosition(position: Long) {
        if (position == positionMs) return
        positionMs = position
        holders.firstOrNull { it.boundPosition == activePosition }?.renderPosition(position)
    }

    fun setState(value: PlayerUiState) {
        state = value
        holders.forEach { holder ->
            holder.render(value, active = holder.boundPosition == activePosition)
        }
    }

    fun setFavorites(keys: Set<String>) {
        holders.forEach { holder -> holder.renderFavorite(holder.track?.key in keys) }
    }

    /** [offsetMs] 是用户手动校准的偏移，解析时叠加到文件自带的时间轴上。 */
    fun setLyrics(key: String, text: String?, offsetMs: Long = 0L) {
        lyrics[key] = LrcParser.parse(text, offsetMs)
        holders
            .filter { it.track?.key == key }
            .forEach { it.renderLyrics() }
    }

    fun setInsets(topPx: Int, bottomPx: Int) {
        topInsetPx = topPx
        bottomInsetPx = bottomPx
        holders.forEach { it.applyInsets() }
    }

    fun release() {
        holders.clear()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemPlayerBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(tracks[position], position)
        holders.add(holder)
    }

    override fun onViewAttachedToWindow(holder: Holder) {
        super.onViewAttachedToWindow(holder)
        val position = holder.boundPosition
        if (position == RecyclerView.NO_POSITION) return
        holders.add(holder)
        holder.render(state, active = position == activePosition)
    }

    override fun onViewRecycled(holder: Holder) {
        holders.remove(holder)
        super.onViewRecycled(holder)
    }

    override fun onViewDetachedFromWindow(holder: Holder) {
        // 滑出预加载窗口的页面也摘掉：ViewPager2 会保留一页在屏幕外，
        // 留着它就会被 setState / setActive 反复重画（而且它已经不可见）
        holders.remove(holder)
        super.onViewDetachedFromWindow(holder)
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
            binding.playerQueue.setOnClickListener { onShowQueue() }
            binding.playerMore.setOnClickListener { onMore() }
            binding.playerFullscreen.setOnClickListener { onFullscreen() }
            binding.playerDislike.setOnClickListener { track?.let(onDislike) }
            binding.playerComments.setOnClickListener { track?.let(onShowComments) }
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
                binding.playerQueue,
                binding.playerMore,
                binding.playerFullscreen,
                binding.playerComments,
                binding.playerDislike,
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
            // 全屏按钮浮在内容之上，位置跟着状态栏 inset 走，不能压在系统图标下
            val lp = binding.playerFullscreen.layoutParams as ViewGroup.MarginLayoutParams
            val top = (topInsetPx - binding.root.context.dp(12f).toInt())
                .coerceAtLeast(binding.root.context.dp(8f).toInt())
            if (lp.topMargin != top) {
                lp.topMargin = top
                binding.playerFullscreen.layoutParams = lp
            }
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
            when {
                // 加载中和没有歌词都留空：封面和标题已经够了，不用一句话解释空白
                sheet == null || sheet.isEmpty -> binding.playerLyrics.showPlaceholder("")

                else -> binding.playerLyrics.setSheet(
                    sheet,
                    if (boundPosition == activePosition) positionMs else 0L,
                )
            }
        }

        /** 进度 tick 的唯一入口：只更新进度条、时间文字、歌词高亮。 */
        fun renderPosition(position: Long) {
            renderProgress(position, durationMs)
            binding.playerLyrics.updateProgress(position)
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

            val duration = if (active && (value?.durationMs ?: 0L) > 0L) {
                value?.durationMs ?: 0L
            } else {
                track?.durationMs ?: 0L
            }
            if (active) {
                durationMs = duration
                renderPosition(positionMs)
            } else {
                // 非当前页只画个静态的 0 进度，别把别人的时间算进来
                renderProgress(0L, duration)
            }
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

    private companion object {
        /** 歌词缓存最多留多少首。 */
        const val LYRICS_CACHE_MAX = 24
    }
}