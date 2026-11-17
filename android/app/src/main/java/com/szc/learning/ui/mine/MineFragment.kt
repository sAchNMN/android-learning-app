package com.szc.learning.ui.mine

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.szc.learning.AppContainer
import com.szc.learning.LearningApp
import com.szc.learning.R
import com.szc.learning.core.common.toast
import com.szc.learning.databinding.FragmentMineBinding
import com.szc.learning.ui.home.HomeViewModel
import com.szc.learning.ui.login.LoginActivity
import kotlinx.coroutines.launch

/** 我的 tab：账号信息与退出登录，逻辑沿用原首页。 */
class MineFragment : Fragment() {

    private var _binding: FragmentMineBinding? = null
    private val binding get() = _binding!!

    private val container: AppContainer
        get() = (requireActivity().application as LearningApp).container

    private lateinit var viewModel: HomeViewModel

    override fun onCreateView(
        inflater: LayoutInflater,
        parent: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentMineBinding.inflate(inflater, parent, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewModel = ViewModelProvider(
            this,
            HomeViewModel.factory(container.authRepository, container.sessionStore),
        )[HomeViewModel::class.java]

        binding.logoutButton.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setMessage(R.string.home_logout_confirm)
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.action_confirm) { _, _ -> viewModel.logout() }
                .show()
        }
        observe()
    }

    private fun observe() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    val displayName = state.nickname.ifBlank { "未登录" }
                    binding.nicknameText.text = displayName
                    binding.avatarText.text = displayName.take(1)

                    binding.accountText.isVisible = state.account.isNotBlank()
                    binding.accountText.text = getString(R.string.home_account, state.account)
                    binding.logoutButton.isEnabled = !state.loggingOut

                    if (state.sessionExpired) {
                        goLogin(getString(R.string.home_session_expired))
                        return@collect
                    }

                    if (state.loggedOut) {
                        viewModel.consumeLoggedOut()
                        container.cartStore.clear()
                        goLogin()
                    }
                }
            }
        }
    }

    private fun goLogin(message: String? = null) {
        startActivity(
            Intent(requireContext(), LoginActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            },
        )
        if (message != null) requireContext().toast(message)
        requireActivity().finish()
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
