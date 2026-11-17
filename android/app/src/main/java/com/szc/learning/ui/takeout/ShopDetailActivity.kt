package com.szc.learning.ui.takeout

import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.szc.learning.AppContainer
import com.szc.learning.LearningApp
import com.szc.learning.R
import com.szc.learning.core.common.toast
import com.szc.learning.core.network.takeout.Dish
import com.szc.learning.core.network.takeout.DishGroup
import com.szc.learning.core.network.takeout.DishSpecOption
import com.szc.learning.core.network.takeout.ShopDetail
import com.szc.learning.data.ShopCart
import com.szc.learning.databinding.ActivityShopDetailBinding
import kotlinx.coroutines.launch

/**
 * 店铺详情（D1）：分类联动 + 规格选择 + 购物车。
 *
 * 左右联动的两条规则：
 * 1. 右侧滚动驱动左侧高亮（用户手动滚动时生效）
 * 2. 左侧点击驱动右侧滚动，期间**关掉**规则 1，
 *    否则程序滚动会反过来改左侧，形成抖动
 */
class ShopDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityShopDetailBinding

    private val container: AppContainer get() = (application as LearningApp).container

    private val shopId: Long by lazy { intent.getLongExtra(EXTRA_SHOP_ID, -1L) }

    /** 从搜索页进来时要定位到某道菜，普通进入为 0。 */
    private val highlightDishId: Long by lazy {
        intent.getLongExtra(EXTRA_HIGHLIGHT_DISH_ID, 0L)
    }

    private val viewModel: ShopDetailViewModel by viewModels {
        ShopDetailViewModel.factory(container.shopRepository, container.cartStore, shopId)
    }

    private val categoryAdapter = CategoryAdapter { groupIndex -> scrollToCategory(groupIndex) }

    private val dishAdapter = DishListAdapter(
        onAdd = { dish -> handleAdd(dish) },
        onMinus = { dish -> viewModel.decreaseDish(dish) },
    )

    private var renderedGroups: List<DishGroup> = emptyList()

    private var cartSheet: CartSheetDialog? = null

    /** true 期间忽略右侧滚动，避免程序滚动与手动滚动互相打架。 */
    private var suppressScrollSync = false

    private var highlightApplied = false

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        if (shopId <= 0) {
            finish()
            return
        }

        binding = ActivityShopDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }

        setupRecyclers()
        bindActions()
        observe()
    }

    private fun setupRecyclers() {
        binding.categoryRecycler.layoutManager = LinearLayoutManager(this)
        binding.categoryRecycler.adapter = categoryAdapter

        val dishLayoutManager = LinearLayoutManager(this)
        binding.dishRecycler.layoutManager = dishLayoutManager
        binding.dishRecycler.adapter = dishAdapter

        binding.dishRecycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (suppressScrollSync) return
                val first = dishLayoutManager.findFirstVisibleItemPosition()
                val groupIndex = dishAdapter.groupIndexOf(first)
                if (groupIndex >= 0) {
                    categoryAdapter.select(groupIndex)
                }
            }
        })
    }

    private fun bindActions() {
        binding.retryButton.setOnClickListener { viewModel.load() }
        binding.favoriteButton.setOnClickListener { viewModel.toggleFavorite() }
        binding.cartSummaryGroup.setOnClickListener { showCartSheet() }
        binding.checkoutButton.setOnClickListener {
            startActivity(CheckoutActivity.intent(this, shopId))
        }
    }

    private fun observe() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { state ->
                    binding.loadingBar.isVisible = state.loading

                    val showError = state.loadError != null && state.detail == null
                    binding.errorGroup.isVisible = showError
                    if (showError) {
                        binding.errorText.text = state.loadError
                    }

                    state.detail?.let { detail ->
                        bindShopHeader(detail)
                        if (detail.groups != renderedGroups) {
                            renderedGroups = detail.groups
                            categoryAdapter.submit(detail.groups.map { it.category })
                            highlightApplied = false
                        }
                    }

                    binding.favoriteButton.setText(
                        if (state.favorited) R.string.shop_detail_favorited
                        else R.string.shop_detail_favorite,
                    )
                    binding.favoriteButton.isEnabled = !state.favoriteBusy

                    renderCart(state)

                    state.actionError?.let {
                        toast(it)
                        viewModel.consumeError()
                    }
                }
            }
        }
    }

    private fun bindShopHeader(detail: ShopDetail) {
        val shop = detail.shop ?: return

        binding.shopName.text = shop.shopName
        binding.shopMeta.text = buildList {
            shop.description?.takeIf { it.isNotBlank() }?.let(::add)
            add(getString(R.string.shop_rating_score, shop.ratingText))
        }.joinToString(" · ")

        binding.shopInfo.text = buildString {
            append(
                getString(
                    R.string.shop_fee_detail,
                    formatMoney(shop.minAmount),
                    formatMoney(shop.deliveryFee),
                    shop.deliveryTime,
                ),
            )
            shop.openTime?.takeIf { it.isNotBlank() }?.let { append(" · ").append(it) }
            if (!shop.isOpen) {
                append(" · ").append(getString(R.string.shop_closed))
            }
        }
    }

    private fun renderCart(state: ShopDetailState) {
        val cart = state.cart

        binding.cartBadge.isVisible = cart.itemCount > 0
        binding.cartBadge.text = getString(R.string.shop_detail_items, cart.itemCount)
        binding.totalPrice.text = getString(
            R.string.shop_detail_total,
            formatMoney(cart.totalAmount),
        )

        binding.cartSummary.text = when {
            cart.itemCount == 0 -> getString(R.string.shop_detail_cart_empty)
            state.shortOfMin > 0 -> getString(
                R.string.shop_detail_min_short,
                formatMoney(state.shortOfMin),
            )
            !state.shopOpen -> getString(R.string.shop_detail_closed_hint)
            else -> getString(R.string.shop_detail_fee_hint, formatMoney(state.deliveryFee))
        }

        binding.checkoutButton.isEnabled = state.canCheckout

        if (renderedGroups.isNotEmpty()) {
            dishAdapter.submitList(buildItems(renderedGroups, cart))
            applyHighlightIfNeeded()
        }

        cartSheet?.render(cart)
    }

    private fun buildItems(groups: List<DishGroup>, cart: ShopCart): List<DishItem> {
        val items = ArrayList<DishItem>()
        groups.forEachIndexed { index, group ->
            items += DishItem.Header(group.category, index)
            group.dishes.forEach { dish ->
                val lines = cart.lines.filter { it.dishId == dish.dishId }
                val quantity = lines.sumOf { it.quantity }
                val specText = when {
                    lines.isEmpty() -> ""
                    lines.size == 1 -> lines.first().specText
                    // 同菜品选了多个规格时行上只能概括，细节点进购物车看
                    else -> getString(R.string.dish_multi_spec)
                }
                items += DishItem.Row(dish, index, quantity, specText)
            }
        }
        return items
    }

    private fun applyHighlightIfNeeded() {
        if (highlightApplied || highlightDishId <= 0) return
        val position = dishAdapter.currentList.indexOfFirst {
            it is DishItem.Row && it.dish.dishId == highlightDishId
        }
        if (position < 0) return

        highlightApplied = true
        binding.dishRecycler.post {
            (binding.dishRecycler.layoutManager as? LinearLayoutManager)
                ?.scrollToPositionWithOffset(position, 0)
        }
    }

    private fun scrollToCategory(groupIndex: Int) {
        val position = dishAdapter.headerPositionOf(groupIndex)
        if (position < 0) return

        suppressScrollSync = true
        (binding.dishRecycler.layoutManager as? LinearLayoutManager)
            ?.scrollToPositionWithOffset(position, 0)
        binding.dishRecycler.postDelayed({ suppressScrollSync = false }, SCROLL_SYNC_DELAY_MS)
    }

    private fun handleAdd(dish: Dish) {
        if (dish.hasSpec && dish.specs.isNotEmpty()) {
            SpecSheetDialog(this, dish) { options -> confirmAdd(dish, options) }.show()
        } else {
            confirmAdd(dish, emptyList())
        }
    }

    private fun confirmAdd(dish: Dish, options: List<DishSpecOption>) {
        if (!viewModel.hasOtherShopCart()) {
            viewModel.add(dish, options)
            return
        }
        MaterialAlertDialogBuilder(this)
            .setMessage(R.string.cart_other_shop_confirm)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.cart_switch_continue) { _, _ ->
                viewModel.clearOtherCarts()
                viewModel.add(dish, options)
            }
            .show()
    }

    private fun showCartSheet() {
        val cart = viewModel.state.value.cart
        if (cart.isEmpty) return

        if (cartSheet == null) {
            cartSheet = CartSheetDialog(
                context = this,
                cart = cart,
                onIncrease = { key -> viewModel.increase(key) },
                onDecrease = { key -> viewModel.decrease(key) },
                onClear = { viewModel.clearCart() },
            ).also { dialog ->
                dialog.setOnDismissListener { cartSheet = null }
            }
        }
        cartSheet?.show()
    }

    companion object {
        private const val SCROLL_SYNC_DELAY_MS = 400L

        const val EXTRA_SHOP_ID = "extra_shop_id"
        const val EXTRA_HIGHLIGHT_DISH_ID = "extra_highlight_dish_id"
    }
}
