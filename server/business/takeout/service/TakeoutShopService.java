package com.ruoyi.takeout.service;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.takeout.domain.DishGroup;
import com.ruoyi.takeout.domain.DishSearchRow;
import com.ruoyi.takeout.domain.HomeIndex;
import com.ruoyi.takeout.domain.PageData;
import com.ruoyi.takeout.domain.ShopDetail;
import com.ruoyi.takeout.domain.TakeoutDish;
import com.ruoyi.takeout.domain.TakeoutDishSpec;
import com.ruoyi.takeout.domain.TakeoutDishSpecOption;
import com.ruoyi.takeout.domain.TakeoutShop;
import com.ruoyi.takeout.domain.TakeoutShopCategory;
import com.ruoyi.takeout.mapper.TakeoutDishMapper;
import com.ruoyi.takeout.mapper.TakeoutDishSpecMapper;
import com.ruoyi.takeout.mapper.TakeoutHotKeywordMapper;
import com.ruoyi.takeout.mapper.TakeoutShopCategoryMapper;
import com.ruoyi.takeout.mapper.TakeoutShopFavoriteMapper;
import com.ruoyi.takeout.mapper.TakeoutShopMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 店铺域：分类、分页列表、详情。
 *
 * 从原来的 TakeoutService 拆出来，那边只留订单相关的读写。
 */
@Service
public class TakeoutShopService {

    /** 允许的排序值。白名单以外的值一律按综合排序，绝不拼进 SQL。 */
    private static final Set<String> SORTS = Set.of("default", "rating", "fast", "cheap");

    private static final int DEFAULT_PAGE_SIZE = 10;
    private static final int MAX_PAGE_SIZE = 50;
    private static final int MAX_KEYWORD_LENGTH = 20;
    /** 首页推荐位数量。多了首屏信息过载，少了显得空。 */
    private static final int RECOMMEND_SHOP_COUNT = 5;

    @Autowired
    private TakeoutShopMapper shopMapper;

    @Autowired
    private TakeoutShopCategoryMapper categoryMapper;

    @Autowired
    private TakeoutDishMapper dishMapper;

    @Autowired
    private TakeoutDishSpecMapper specMapper;

    @Autowired
    private TakeoutShopFavoriteMapper favoriteMapper;

    @Autowired
    private TakeoutHotKeywordMapper hotKeywordMapper;

    // ============================================================
    // 首页聚合（T1）
    // ============================================================

    /** 首页首屏：分类 + 推荐店铺 + 热搜词，一次拿全。 */
    public HomeIndex homeIndex() {
        HomeIndex index = new HomeIndex();
        index.setCategories(categories());
        index.setShops(shopMapper.selectPage(null, null, "rating", 0, RECOMMEND_SHOP_COUNT));
        index.setHotKeywords(hotKeywords(8));
        return index;
    }

    // ============================================================
    // 搜索与收藏（L2 / L5）
    // ============================================================

    /** 热搜词。给个上限，别让后台配了 200 条全渲染到搜索页。 */
    public List<String> hotKeywords(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 20));
        return hotKeywordMapper.selectHot(safeLimit);
    }

    /** 店铺搜索：复用分页逻辑，只是不传分类。 */
    public PageData<TakeoutShop> searchShops(String keyword, Integer pageNum, Integer pageSize) {
        return page(null, keyword, "default", pageNum, pageSize);
    }

    /** 菜品搜索：按菜名匹配，按销量倒序。 */
    public PageData<DishSearchRow> searchDishes(String keyword, Integer pageNum, Integer pageSize) {
        String kw = normalizeKeyword(keyword);
        if (kw == null) {
            return new PageData<>(0L, List.of());
        }
        int num = (pageNum == null || pageNum < 1) ? 1 : pageNum;
        int size = (pageSize == null || pageSize < 1) ? 10 : Math.min(pageSize, 50);

        int total = dishMapper.countSearch(kw);
        List<DishSearchRow> rows = total == 0
                ? List.of()
                : dishMapper.searchPage(kw, (num - 1) * size, size);
        return new PageData<>(total, rows);
    }

    /** 我收藏的店铺。 */
    public PageData<TakeoutShop> favoriteShops(Long userId, Integer pageNum, Integer pageSize) {
        int num = (pageNum == null || pageNum < 1) ? 1 : pageNum;
        int size = (pageSize == null || pageSize < 1) ? 10 : Math.min(pageSize, 50);

        int total = favoriteMapper.countShops(userId);
        List<TakeoutShop> rows = total == 0
                ? List.of()
                : favoriteMapper.selectShopPage(userId, (num - 1) * size, size);
        return new PageData<>(total, rows);
    }

    public List<TakeoutShopCategory> categories() {
        return categoryMapper.selectEnabled();
    }

    /** 营业中的全部店铺，旧接口 /app/shop/list 用。 */
    public List<TakeoutShop> listShops() {
        return shopMapper.selectOpenShops();
    }

    /**
     * 店铺详情：店铺 + 收藏状态 + 按分类分组的菜品（含规格）。
     *
     * 三条 SQL 拿全，Service 里组装。分组用 LinkedHashMap 按菜品顺序建组，
     * 不走 GROUP BY —— MySQL 不保证组的顺序，左右联动会跳。
     */
    public ShopDetail shopDetail(Long shopId, Long userId) {
        TakeoutShop shop = shopMapper.selectById(shopId);
        if (shop == null) {
            throw new ServiceException("店铺不存在");
        }

        List<TakeoutDish> dishes = dishMapper.selectByShop(shopId);
        attachSpecs(dishes);

        Map<String, List<TakeoutDish>> grouped = new LinkedHashMap<>();
        for (TakeoutDish dish : dishes) {
            String category = (dish.getCategory() == null || dish.getCategory().trim().isEmpty())
                    ? "其他"
                    : dish.getCategory();
            grouped.computeIfAbsent(category, key -> new ArrayList<>()).add(dish);
        }

        List<DishGroup> groups = new ArrayList<>(grouped.size());
        grouped.forEach((category, list) -> groups.add(new DishGroup(category, list)));

        boolean favorited = favoriteMapper.count(userId, shopId) > 0;
        return new ShopDetail(shop, favorited, groups);
    }

    /**
     * 给菜品挂上规格组与选项。
     *
     * 两条批量查询：先按 dishId 查组，再按 specId 查选项，绝不按菜品循环查库。
     */
    private void attachSpecs(List<TakeoutDish> dishes) {
        if (dishes.isEmpty()) {
            return;
        }
        List<Long> dishIds = dishes.stream().map(TakeoutDish::getDishId).collect(Collectors.toList());
        List<TakeoutDishSpec> specs = specMapper.selectByDishIds(dishIds);
        if (specs.isEmpty()) {
            return;
        }

        List<Long> specIds = specs.stream().map(TakeoutDishSpec::getSpecId).collect(Collectors.toList());
        Map<Long, List<TakeoutDishSpecOption>> optionsBySpec = specMapper.selectOptionsBySpecIds(specIds)
                .stream()
                .collect(Collectors.groupingBy(TakeoutDishSpecOption::getSpecId));

        Map<Long, List<TakeoutDishSpec>> specsByDish = new LinkedHashMap<>();
        for (TakeoutDishSpec spec : specs) {
            spec.setOptions(optionsBySpec.getOrDefault(spec.getSpecId(), List.of()));
            specsByDish.computeIfAbsent(spec.getDishId(), key -> new ArrayList<>()).add(spec);
        }
        for (TakeoutDish dish : dishes) {
            dish.setSpecs(specsByDish.getOrDefault(dish.getDishId(), List.of()));
        }
    }

    /**
     * 收藏 / 取消收藏，都幂等：重复调用不报错，客户端不用先查状态。
     */
    public void favorite(Long shopId, Long userId) {
        ensureShopExists(shopId);
        favoriteMapper.insert(userId, shopId);
    }

    public void unfavorite(Long shopId, Long userId) {
        favoriteMapper.delete(userId, shopId);
    }

    private void ensureShopExists(Long shopId) {
        if (shopMapper.selectById(shopId) == null) {
            throw new ServiceException("店铺不存在");
        }
    }

    /**
     * 分页列表。
     *
     * @param categoryId 空或 0 表示全部分类
     * @param keyword    店铺名模糊匹配，超长截断
     * @param sort       default / rating / fast / cheap
     */
    public PageData<TakeoutShop> page(Long categoryId, String keyword, String sort,
                                      Integer pageNum, Integer pageSize) {
        int num = (pageNum == null || pageNum < 1) ? 1 : pageNum;
        int size = (pageSize == null || pageSize < 1) ? DEFAULT_PAGE_SIZE
                : Math.min(pageSize, MAX_PAGE_SIZE);

        Long cid = (categoryId != null && categoryId > 0) ? categoryId : null;
        String kw = normalizeKeyword(keyword);
        String order = (sort != null && SORTS.contains(sort)) ? sort : "default";

        int total = shopMapper.countPage(cid, kw);
        List<TakeoutShop> rows = total == 0
                ? List.of()
                : shopMapper.selectPage(cid, kw, order, (num - 1) * size, size);
        return new PageData<>(total, rows);
    }

    private String normalizeKeyword(String keyword) {
        if (keyword == null) {
            return null;
        }
        String trimmed = keyword.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.length() > MAX_KEYWORD_LENGTH
                ? trimmed.substring(0, MAX_KEYWORD_LENGTH)
                : trimmed;
    }
}
