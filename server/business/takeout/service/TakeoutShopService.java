package com.ruoyi.takeout.service;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.takeout.domain.PageData;
import com.ruoyi.takeout.domain.ShopDetail;
import com.ruoyi.takeout.domain.TakeoutShop;
import com.ruoyi.takeout.domain.TakeoutShopCategory;
import com.ruoyi.takeout.mapper.TakeoutDishMapper;
import com.ruoyi.takeout.mapper.TakeoutShopCategoryMapper;
import com.ruoyi.takeout.mapper.TakeoutShopMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

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

    @Autowired
    private TakeoutShopMapper shopMapper;

    @Autowired
    private TakeoutShopCategoryMapper categoryMapper;

    @Autowired
    private TakeoutDishMapper dishMapper;

    public List<TakeoutShopCategory> categories() {
        return categoryMapper.selectEnabled();
    }

    /** 营业中的全部店铺，旧接口 /app/shop/list 用。 */
    public List<TakeoutShop> listShops() {
        return shopMapper.selectOpenShops();
    }

    public ShopDetail shopDetail(Long shopId) {
        TakeoutShop shop = shopMapper.selectById(shopId);
        if (shop == null) {
            throw new ServiceException("店铺不存在");
        }
        return new ShopDetail(shop, dishMapper.selectByShop(shopId));
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
