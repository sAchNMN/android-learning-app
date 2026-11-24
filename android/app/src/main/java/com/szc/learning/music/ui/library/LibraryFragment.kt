package com.szc.learning.music.ui.library

import android.os.Bundle
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.szc.learning.R
import com.szc.learning.core.common.music.MusicTrack
import com.szc.learning.core.common.toast
import com.szc.learning.databinding.FragmentLibraryBinding
import com.szc.learning.music.ui.MusicViewModel
import com.szc.learning.music.ui.track.GroupAdapter
import com.szc.learning.music.ui.track.TrackAdapter
import com.szc.learning.music.ui.track.TrackGroup
import com.szc.learning.music.ui.widget.ShimmerSkeletonView
import com.szc.learning.music.ui.widget.dp
import com.szc.learning.ui.main.MainActivity
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * 本地音乐。
 *
 * 四个视图共用一份扫描结果：歌曲 / 歌手 / 专辑 / 文件夹。
 * 歌手、专辑、文件夹都不再单独查询 MediaStore，而是在内存里 groupBy，
 * 十几百首歌的规模这么干最省事，也不会出现"列表里有、点进去空的"。
 */
class LibraryFragment : Fragment(R.layout.fragment_library) {

    private enum class Mode { SONGS, ARTISTS, ALBUMS, FOLDERS }

    private enum class Sort { TITLE, ARTIST, DURATION }

    private var _binding: FragmentLibraryBinding? = null
    private val binding get() = requireNotNull(_binding)

    private val viewModel: MusicViewModel by lazy {
        ViewModelProvider(requireActivity())[MusicViewModel::class.java]
    }

    private var mode = Mode.SONGS
    private var sort = Sort.TITLE
    private var query = ""

    private val trackAdapter = TrackAdapter { index ->
        val tracks = visibleTracks()
        if (index in tracks.indices) viewModel.play(tracks, index)
    }

    private val groupAdapter = GroupAdapter { group ->
        viewModel.play(group.tracks, 0)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentLibraryBinding.bind(view)

        binding.localList.layoutManager = LinearLayoutManager(requireContext())
        binding.localList.adapter = trackAdapter
        binding.localList.itemAnimator = null

        binding.localTabs.setItems(
            listOf(
                getString(R.string.local_songs),
                getString(R.string.local_artists),
                getString(R.string.local_albums),
                getString(R.string.local_folders),
            ),
        )
        binding.localSkeleton.style = ShimmerSkeletonView.Style.LIBRARY
        installInsets()
        binding.localTabs.onSelected = { index ->
            mode = Mode.entries[index]
            render()
        }

        binding.localSearch.doAfterTextChanged {
            query = it?.toString().orEmpty().trim()
            render()
        }
        binding.localRescan.setOnClickListener { rescan() }
        binding.localPlayAll.setOnClickListener {
            val tracks = visibleTracks()
            if (tracks.isNotEmpty()) viewModel.play(tracks, 0)
        }
        binding.localSort.setOnClickListener { showSortDialog() }
        binding.localGrant.setOnClickListener { rescan() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(viewModel.local, viewModel.localLoading) { _, _ -> Unit }
                    .collect { render() }
            }
        }
        render()
    }

    /**
     * 顶部固定栏避开状态栏；底部列表内容可以滚到手势条下面，
     * 但最后一行会停在 dock 上方。
     */
    private fun installInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(top = bars.top)
            binding.localList.updatePadding(
                bottom = view.context.dp(156f).toInt() + bars.bottom,
            )
            binding.localSkeleton.updatePadding(
                top = view.context.dp(4f).toInt(),
                bottom = view.context.dp(156f).toInt() + bars.bottom,
            )
            insets
        }
        ViewCompat.requestApplyInsets(binding.root)
    }

    private fun render() {
        val granted = (activity as? MainActivity)?.audioPermissionGranted() ?: true
        val all = viewModel.local.value
        val visible = visibleTracks()
        val loading = granted && all.isEmpty() && query.isEmpty() &&
            viewModel.localLoading.value

        // 曲库为空时，搜索、排序、歌手/专辑/文件夹都没有作用对象 —— 整排收起，
        // 让空态和「重新扫描」成为页面上唯一的动作。
        val hasLibrary = all.isNotEmpty()
        binding.localSearchBar.isVisible = hasLibrary
        binding.localActions.isVisible = hasLibrary
        binding.localTabs.isVisible = hasLibrary

        binding.localSkeleton.visibility = if (loading) View.VISIBLE else View.GONE
        if (loading) {
            binding.localEmpty.visibility = View.GONE
            binding.localList.visibility = View.INVISIBLE
            return
        }

        val empty = !granted || visible.isEmpty()
        binding.localEmpty.visibility = if (empty) View.VISIBLE else View.GONE
        binding.localList.visibility = if (empty) View.INVISIBLE else View.VISIBLE
        binding.localPlayAll.isVisible = visible.isNotEmpty()
        if (empty) {
            val needsPermission = !granted
            // 搜索无结果时不给动作：这里没有"重新扫描"能解决的问题
            binding.localGrant.isVisible = needsPermission || all.isEmpty()
            binding.localGrant.setText(
                if (needsPermission) R.string.local_grant else R.string.local_scan,
            )
            binding.localEmptyTitle.setText(
                when {
                    needsPermission -> R.string.local_permission
                    query.isNotEmpty() -> R.string.local_search_empty
                    else -> R.string.local_empty
                },
            )
        }

        if (mode == Mode.SONGS) {
            if (binding.localList.adapter !== trackAdapter) {
                binding.localList.adapter = trackAdapter
            }
            trackAdapter.submitList(visible)
        } else {
            if (binding.localList.adapter !== groupAdapter) {
                binding.localList.adapter = groupAdapter
            }
            groupAdapter.submitList(buildGroups(visible))
        }
    }

    private fun buildGroups(tracks: List<MusicTrack>): List<TrackGroup> {
        val unknown = getString(R.string.player_unknown_artist)
        return when (mode) {
            Mode.ARTISTS -> tracks
                .groupBy { it.artist.ifBlank { unknown } }
                .map { (name, list) ->
                    TrackGroup(
                        "artist:$name",
                        name,
                        list.sortedBy { it.title }
                            .take(3)
                            .joinToString(" / ") { it.title },
                        list.sortedBy { it.title },
                    )
                }
                .sortedBy { it.title }

            Mode.ALBUMS -> tracks
                .groupBy { it.album.ifBlank { getString(R.string.player_unknown_album) } }
                .map { (album, list) ->
                    TrackGroup(
                        "album:$album",
                        album,
                        list.first().artist.ifBlank { unknown },
                        list,
                    )
                }
                .sortedBy { it.title }

            Mode.FOLDERS -> tracks
                .groupBy { it.folder.ifBlank { getString(R.string.local_unknown_folder) } }
                .map { (folder, list) ->
                    // 路径当标题太长，标题只留最后一级，完整路径放副标题
                    val name = folder.trimEnd('/').substringAfterLast('/').ifBlank { folder }
                    TrackGroup(
                        "folder:$folder",
                        name,
                        folder,
                        list.sortedBy { it.title },
                    )
                }
                .sortedBy { it.title }

            Mode.SONGS -> emptyList()
        }
    }

    private fun visibleTracks(): List<MusicTrack> {
        val keyword = query
        val filtered = viewModel.local.value.filter { track ->
            keyword.isEmpty() ||
                track.title.contains(keyword, ignoreCase = true) ||
                track.artist.contains(keyword, ignoreCase = true) ||
                track.album.contains(keyword, ignoreCase = true) ||
                track.folder.contains(keyword, ignoreCase = true)
        }
        return when (sort) {
            Sort.TITLE -> filtered.sortedBy { it.title }
            Sort.ARTIST -> filtered.sortedWith(compareBy({ it.artist }, { it.title }))
            Sort.DURATION -> filtered.sortedByDescending { it.durationMs }
        }
    }

    private fun showSortDialog() {
        val labels = arrayOf(
            getString(R.string.local_sort_default),
            getString(R.string.local_sort_artist),
            getString(R.string.local_sort_duration),
        )
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.local_sort)
            .setSingleChoiceItems(labels, sort.ordinal) { dialog, which ->
                sort = Sort.entries[which]
                dialog.dismiss()
                render()
            }
            .show()
    }

    private fun rescan() {
        val activity = activity as? MainActivity ?: return
        if (!activity.audioPermissionGranted()) {
            activity.requestAudioPermission()
            return
        }
        viewModel.refreshLocal()
        requireContext().toast(R.string.local_rescanned)
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}