package com.szc.learning.music.ui.search

import android.os.Bundle
import android.view.View
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.szc.learning.R
import com.szc.learning.core.common.music.MusicTrack
import com.szc.learning.databinding.FragmentSearchBinding
import com.szc.learning.music.ui.MusicViewModel
import com.szc.learning.music.ui.track.TrackAdapter

/**
 * 搜索页。
 *
 * 空查询时是分类方块墙，一输入就换成结果列表 —— 两态切换而不是"上面分类下面结果"，
 * 因为后者会让上面那堵墙永远占着 40% 屏幕却没人看。
 */
class SearchFragment : Fragment(R.layout.fragment_search) {

    private var _binding: FragmentSearchBinding? = null
    private val binding get() = requireNotNull(_binding)

    private val viewModel: MusicViewModel by lazy { ViewModelProvider(requireActivity())[MusicViewModel::class.java] }

    private val genreAdapter = GenreAdapter { genre ->
        binding.searchInput.setText(getString(genre.labelRes))
        binding.searchInput.setSelection(binding.searchInput.text.length)
    }

    private val resultAdapter = TrackAdapter { index ->
        viewModel.play(results, index)
    }

    private var results: List<MusicTrack> = emptyList()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentSearchBinding.bind(view)

        binding.genreGrid.apply {
            layoutManager = GridLayoutManager(requireContext(), 2)
            adapter = genreAdapter
            setHasFixedSize(true)
        }
        genreAdapter.submitList(defaultGenres)

        binding.searchResults.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = resultAdapter
            setHasFixedSize(true)
        }

        binding.searchInput.doAfterTextChanged { text -> applyQuery(text?.toString().orEmpty()) }
        binding.searchEmpty.setText(R.string.search_empty)
    }

    private fun applyQuery(raw: String) {
        val query = raw.trim()
        val searching = query.isNotEmpty()
        binding.genreGrid.visibility = if (searching) View.GONE else View.VISIBLE
        binding.searchResults.visibility = if (searching) View.VISIBLE else View.GONE

        if (!searching) {
            results = emptyList()
            resultAdapter.submitList(emptyList())
            binding.searchEmpty.visibility = View.GONE
            return
        }

        results = viewModel.search(query)
        resultAdapter.submitList(results)
        binding.searchEmpty.visibility = if (results.isEmpty()) View.VISIBLE else View.GONE
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
