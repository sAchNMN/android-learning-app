package com.szc.learning.music.ui.library

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.szc.learning.R
import com.szc.learning.core.common.music.MusicTrack
import com.szc.learning.databinding.FragmentLibraryBinding
import com.szc.learning.music.ui.MusicViewModel
import com.szc.learning.music.ui.track.GroupAdapter
import com.szc.learning.music.ui.track.TrackAdapter
import com.szc.learning.music.ui.track.TrackGroup
import com.szc.learning.ui.main.MainActivity
import kotlinx.coroutines.launch

/**
 * 资料库：本地音频，三种视角（歌曲 / 专辑 / 艺人）。
 *
 * 数据全部来自 MediaStore，不维护本地索引表；权限没给时整页只显示一个"允许"，
 * 不做半残的列表。
 */
class LibraryFragment : Fragment(R.layout.fragment_library) {

    private var _binding: FragmentLibraryBinding? = null
    private val binding get() = requireNotNull(_binding)

    private val viewModel: MusicViewModel by lazy { ViewModelProvider(requireActivity())[MusicViewModel::class.java] }

    private val songAdapter = TrackAdapter { index -> viewModel.playLocal(index) }
    private val groupAdapter = GroupAdapter { group ->
        viewModel.play(group.tracks, 0)
    }

    private var localTracks: List<MusicTrack> = emptyList()
    private var mode = MODE_SONGS

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentLibraryBinding.bind(view)

        binding.songList.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = songAdapter
            setHasFixedSize(true)
        }

        binding.segment.setItems(
            listOf(
                getString(R.string.library_songs),
                getString(R.string.library_albums),
                getString(R.string.library_artists),
            ),
        )
        binding.segment.onSelected = { index ->
            mode = index
            render()
        }

        binding.grantButton.setOnClickListener {
            (activity as? MainActivity)?.requestAudioPermission()
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.local.collect { list ->
                    localTracks = list
                    render()
                }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.localLoading.collect { loading ->
                    if (loading) binding.storageLine.setText(R.string.library_scanning)
                }
            }
        }
    }

    private fun render() {
        val granted = (activity as? MainActivity)?.audioPermissionGranted() ?: true
        binding.permissionPanel.visibility = if (granted) View.GONE else View.VISIBLE
        binding.storageLine.visibility = if (granted) View.VISIBLE else View.INVISIBLE

        if (!granted) {
            binding.songList.visibility = View.GONE
            binding.libraryEmpty.visibility = View.GONE
            return
        }

        binding.songList.visibility = View.VISIBLE
        binding.storageLine.text = summary(localTracks)

        val empty = localTracks.isEmpty()
        binding.libraryEmpty.visibility = if (empty) View.VISIBLE else View.GONE
        binding.libraryEmpty.setText(R.string.library_empty)

        when (mode) {
            MODE_ALBUMS -> bindGroups(groupByAlbum())
            MODE_ARTISTS -> bindGroups(groupByArtist())
            else -> bindSongs()
        }
    }

    private fun bindSongs() {
        val list = binding.songList
        if (list.adapter !== songAdapter) list.adapter = songAdapter
        songAdapter.submitList(localTracks)
        list.scrollToPosition(0)
    }

    private fun bindGroups(groups: List<TrackGroup>) {
        val list = binding.songList
        if (list.adapter !== groupAdapter) list.adapter = groupAdapter
        groupAdapter.submitList(groups)
        list.scrollToPosition(0)
    }

    private fun groupByAlbum(): List<TrackGroup> = localTracks
        .groupBy { it.album.ifBlank { getString(R.string.player_unknown_album) } }
        .map { (album, tracks) ->
            TrackGroup(
                key = "album:$album",
                title = album,
                subtitle = tracks.first().artist.ifBlank {
                    getString(R.string.player_unknown_artist)
                },
                tracks = tracks,
            )
        }
        .sortedBy { it.title.lowercase() }

    private fun groupByArtist(): List<TrackGroup> = localTracks
        .groupBy { it.artist.ifBlank { getString(R.string.player_unknown_artist) } }
        .map { (artist, tracks) ->
            TrackGroup(
                key = "artist:$artist",
                title = artist,
                subtitle = tracks.map { it.album }.distinct().joinToString(" · ").take(40),
                tracks = tracks,
            )
        }
        .sortedBy { it.title.lowercase() }

    /** "128 首 · 8 小时 12 分"，数字在前，单位在后，读起来最短。 */
    private fun summary(tracks: List<MusicTrack>): String {
        val totalMs = tracks.sumOf { it.durationMs }
        val hours = totalMs / 3_600_000
        val minutes = (totalMs % 3_600_000) / 60_000
        val duration = if (hours > 0) {
            getString(R.string.duration_hm, hours, minutes)
        } else {
            getString(R.string.duration_m, minutes.coerceAtLeast(1))
        }
        return getString(R.string.library_summary, tracks.size, duration)
    }

    override fun onResume() {
        super.onResume()
        // 从系统设置里授权返回时不会走 onViewCreated，这里补一次状态刷新
        if (_binding != null) render()
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    private companion object {
        const val MODE_SONGS = 0
        const val MODE_ALBUMS = 1
        const val MODE_ARTISTS = 2
    }
}
