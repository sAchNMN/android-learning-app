package com.szc.learning.music.ui.cloud

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import com.szc.learning.R
import com.szc.learning.databinding.FragmentCloudBinding
import com.szc.learning.music.ui.MusicViewModel
import com.szc.learning.music.ui.track.CloudAdapter
import kotlinx.coroutines.launch

/**
 * 云端曲库。
 *
 * 和后端的关系是"可选依赖"：后端没起来时列表为空，但页面不报错、不白屏，
 * 只在底部给一句"连不上云端"。答辩现场后端挂了也不至于当场翻车。
 */
class CloudFragment : Fragment(R.layout.fragment_cloud) {

    private var _binding: FragmentCloudBinding? = null
    private val binding get() = requireNotNull(_binding)

    private val viewModel: MusicViewModel by lazy { ViewModelProvider(requireActivity())[MusicViewModel::class.java] }

    private val adapter = CloudAdapter { index -> viewModel.playCloud(index) }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentCloudBinding.bind(view)

        binding.cloudGrid.apply {
            layoutManager = GridLayoutManager(requireContext(), COLUMNS)
            adapter = this@CloudFragment.adapter
            setHasFixedSize(true)
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.cloud.collect { list ->
                    adapter.submitList(list)
                    binding.cloudMeta.text = getString(R.string.cloud_count, list.size)
                    renderEmpty(list)
                }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.cloudError.collect { renderEmpty(viewModel.cloud.value) }
            }
        }
        viewModel.loadCloud()
    }

    private fun renderEmpty(list: List<*>) {
        val error = viewModel.cloudError.value
        val showEmpty = list.isEmpty()
        binding.cloudEmpty.visibility = if (showEmpty) View.VISIBLE else View.GONE
        binding.cloudEmpty.setText(
            if (error.isNullOrBlank()) R.string.cloud_empty else R.string.cloud_offline,
        )
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    private companion object {
        const val COLUMNS = 2
    }
}
