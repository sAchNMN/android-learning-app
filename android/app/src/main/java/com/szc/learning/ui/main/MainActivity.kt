package com.szc.learning.ui.main

import android.content.Intent
import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.szc.learning.AppContainer
import com.szc.learning.LearningApp
import com.szc.learning.R
import com.szc.learning.databinding.ActivityMainBinding
import com.szc.learning.ui.cart.CartFragment
import com.szc.learning.ui.home.HomeFragment
import com.szc.learning.ui.mine.MineFragment
import com.szc.learning.ui.order.OrderFragment
import kotlinx.coroutines.launch

/**
 * 顾客端主页面：底部 4 个 tab。
 *
 * Fragment 用 show/hide 切换而不是 replace，切回来时滚动位置和输入内容都还在。
 * 二级页仍是独立 Activity，首页 tab 只负责入口与列表。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val container: AppContainer get() = (application as LearningApp).container

    private val tabIds = listOf(R.id.tab_home, R.id.tab_cart, R.id.tab_order, R.id.tab_mine)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        applyWindowInsets()
        bindNavigation()
        observeCartBadge()

        val initialTab = if (savedInstanceState != null) {
            savedInstanceState.getInt(KEY_TAB, R.id.tab_home)
        } else {
            intent.getIntExtra(EXTRA_TAB, R.id.tab_home)
        }.takeIf { it in tabIds } ?: R.id.tab_home
        selectTab(initialTab)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_TAB, binding.bottomNav.selectedItemId)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val tab = intent.getIntExtra(EXTRA_TAB, R.id.tab_home).takeIf { it in tabIds }
            ?: R.id.tab_home
        selectTab(tab)
    }

    /** 供 tab 内部跳转使用，例如购物车空态的「去逛逛」。 */
    fun selectTab(itemId: Int) {
        if (binding.bottomNav.selectedItemId == itemId) {
            // 已经选中时 set 不会回调监听器，需要手动刷新一次
            showTab(itemId)
        } else {
            binding.bottomNav.selectedItemId = itemId
        }
    }

    private fun applyWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }
    }

    private fun bindNavigation() {
        binding.bottomNav.setOnItemSelectedListener { item ->
            showTab(item.itemId)
            true
        }

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (binding.bottomNav.selectedItemId != R.id.tab_home) {
                        selectTab(R.id.tab_home)
                    } else {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            },
        )
    }

    private fun observeCartBadge() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                container.cartStore.state.collect { cart ->
                    val badge = binding.bottomNav.getOrCreateBadge(R.id.tab_cart)
                    badge.isVisible = cart.itemCount > 0
                    badge.number = cart.itemCount
                }
            }
        }
    }

    /** 首次进入时按需创建 4 个 Fragment，之后只切显示状态。 */
    private fun showTab(itemId: Int) {
        val targetTag = tagOf(itemId)
        val transaction = supportFragmentManager.beginTransaction()
        tabTags().forEach { tag ->
            val fragment = supportFragmentManager.findFragmentByTag(tag)
            if (fragment == null) {
                val created = createFragment(tag)
                transaction.add(R.id.tabContainer, created, tag)
                if (tag != targetTag) transaction.hide(created)
            } else if (tag == targetTag) {
                transaction.show(fragment)
            } else {
                transaction.hide(fragment)
            }
        }
        transaction.commitNow()
    }

    private fun tabTags(): List<String> = listOf(TAG_HOME, TAG_CART, TAG_ORDER, TAG_MINE)

    private fun tagOf(itemId: Int): String = when (itemId) {
        R.id.tab_cart -> TAG_CART
        R.id.tab_order -> TAG_ORDER
        R.id.tab_mine -> TAG_MINE
        else -> TAG_HOME
    }

    private fun createFragment(tag: String): Fragment = when (tag) {
        TAG_CART -> CartFragment()
        TAG_ORDER -> OrderFragment()
        TAG_MINE -> MineFragment()
        else -> HomeFragment()
    }

    companion object {
        /** 目标 tab，值为 R.id.tab_xxx。下单成功后直接落到订单 tab。 */
        const val EXTRA_TAB = "extra_tab"

        private const val KEY_TAB = "state_tab"

        private const val TAG_HOME = "tab_home"
        private const val TAG_CART = "tab_cart"
        private const val TAG_ORDER = "tab_order"
        private const val TAG_MINE = "tab_mine"
    }
}
