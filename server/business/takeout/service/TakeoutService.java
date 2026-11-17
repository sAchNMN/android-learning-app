package com.ruoyi.takeout.service;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.takeout.domain.OrderCreateBody;
import com.ruoyi.takeout.domain.OrderDetail;
import com.ruoyi.takeout.domain.OrderPreview;
import com.ruoyi.takeout.domain.TakeoutAddress;
import com.ruoyi.takeout.domain.TakeoutDish;
import com.ruoyi.takeout.domain.TakeoutDishSpec;
import com.ruoyi.takeout.domain.TakeoutDishSpecOption;
import com.ruoyi.takeout.domain.TakeoutOrder;
import com.ruoyi.takeout.domain.TakeoutOrderItem;
import com.ruoyi.takeout.domain.TakeoutShop;
import com.ruoyi.takeout.mapper.TakeoutAddressMapper;
import com.ruoyi.takeout.mapper.TakeoutDishMapper;
import com.ruoyi.takeout.mapper.TakeoutDishSpecMapper;
import com.ruoyi.takeout.mapper.TakeoutOrderItemMapper;
import com.ruoyi.takeout.mapper.TakeoutOrderMapper;
import com.ruoyi.takeout.mapper.TakeoutOrderStatusLogMapper;
import com.ruoyi.takeout.mapper.TakeoutShopMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 订单域业务逻辑：结算预览、下单、支付、我的订单。
 *
 * 两条铁律：
 * 1. **金额只在服务端算**。create 的请求体不含任何金额字段，客户端改价无效。
 * 2. **状态变更用 CAS**（UPDATE ... WHERE status = 原状态），不先查再改。
 */
@Service
public class TakeoutService {

    private static final int MAX_ITEM_KINDS = 50;
    private static final int MAX_QUANTITY = 99;
    private static final int MAX_REMARK_LENGTH = 255;
    private static final int TOKEN_TTL_MINUTES = 5;
    private static final String TOKEN_PREFIX = "takeout:order:token:";

    /** 操作方：0 用户 / 1 商家 / 2 系统。 */
    private static final String OPERATOR_USER = "0";

    @Autowired
    private TakeoutShopMapper shopMapper;

    @Autowired
    private TakeoutDishMapper dishMapper;

    @Autowired
    private TakeoutDishSpecMapper specMapper;

    @Autowired
    private TakeoutOrderMapper orderMapper;

    @Autowired
    private TakeoutOrderItemMapper orderItemMapper;

    @Autowired
    private TakeoutOrderStatusLogMapper statusLogMapper;

    @Autowired
    private TakeoutAddressMapper addressMapper;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    // ============================================================
    // 结算预览
    // ============================================================

    /**
     * 结算预览。进入结算页时调用，把金额、起送差额、地址、配送时间档位一次性给出。
     *
     * shortOfMin > 0 时也返回成功：结算页要能显示「还差 ¥3 起送」，
     * 而不是把用户一脚踢回上一页。
     */
    public OrderPreview preview(Long userId, OrderCreateBody body) {
        Draft draft = buildDraft(userId, body);

        OrderPreview preview = new OrderPreview();
        preview.setShopId(draft.shop.getShopId());
        preview.setShopName(draft.shop.getShopName());
        preview.setItems(draft.items.stream()
                .map(i -> new OrderPreview.PreviewItem(
                        i.getDishId(), i.getDishName(), i.getSpecText(),
                        i.getPrice(), i.getQuantity(), i.getSubtotal()))
                .collect(Collectors.toList()));
        preview.setTotalAmount(draft.totalAmount);
        preview.setDeliveryFee(draft.deliveryFee);
        preview.setPayAmount(draft.payAmount);
        preview.setMinAmount(draft.minAmount);
        preview.setShortOfMin(draft.shortOfMin);
        preview.setDefaultAddress(draft.address != null
                ? draft.address
                : addressMapper.selectDefault(userId));
        preview.setExpectOptions(draft.expectOptions);
        return preview;
    }

    // ============================================================
    // 下单
    // ============================================================

    /**
     * 下单。
     *
     * 幂等靠 clientToken + Redis SETNX：同一 token 5 分钟内只允许成功一次。
     * 失败时显式删除占位，否则用户改完购物车重试会被自己的占位挡住。
     */
    @Transactional(rollbackFor = Exception.class)
    public OrderDetail createOrder(Long userId, OrderCreateBody body) {
        if (body == null) {
            throw new ServiceException("参数错误");
        }

        String clientToken = trimToNull(body.getClientToken());
        String tokenKey = clientToken == null
                ? null
                : TOKEN_PREFIX + userId + ":" + clientToken;

        if (tokenKey != null) {
            Boolean acquired = redisTemplate.opsForValue()
                    .setIfAbsent(tokenKey, "1", TOKEN_TTL_MINUTES, TimeUnit.MINUTES);
            if (!Boolean.TRUE.equals(acquired)) {
                throw new ServiceException("订单正在提交，请勿重复点击");
            }
        }

        try {
            return doCreate(userId, body);
        } catch (RuntimeException e) {
            if (tokenKey != null) {
                redisTemplate.delete(tokenKey);
            }
            throw e;
        }
    }

    private OrderDetail doCreate(Long userId, OrderCreateBody body) {
        Draft draft = buildDraft(userId, body);
        if (draft.shortOfMin.compareTo(BigDecimal.ZERO) > 0) {
            throw new ServiceException("还差 ¥" + draft.shortOfMin + " 起送");
        }

        TakeoutOrder order = new TakeoutOrder();
        order.setOrderNo(generateOrderNo());
        order.setUserId(userId);
        order.setShopId(draft.shop.getShopId());
        order.setShopName(draft.shop.getShopName());
        order.setAddressId(draft.address == null ? null : draft.address.getAddressId());
        order.setTotalAmount(draft.totalAmount);
        order.setDeliveryFee(draft.deliveryFee);
        order.setPayAmount(draft.payAmount);
        order.setItemCount(draft.itemCount);
        order.setStatus(TakeoutOrder.STATUS_UNPAID);
        order.setExpectTime(draft.expectTime);
        order.setRemark(limit(body.getRemark(), MAX_REMARK_LENGTH));

        // 收货信息：地址簿优先，其次兼容旧客户端的明文字段
        if (draft.address != null) {
            order.setReceiver(draft.address.getReceiver());
            order.setPhone(draft.address.getPhone());
            order.setAddress(draft.address.getFullAddress());
        } else {
            if (isBlank(body.getReceiver()) || isBlank(body.getPhone()) || isBlank(body.getAddress())) {
                throw new ServiceException("请先填写收货地址");
            }
            order.setReceiver(body.getReceiver().trim());
            order.setPhone(body.getPhone().trim());
            order.setAddress(body.getAddress().trim());
        }

        orderMapper.insert(order);

        for (TakeoutOrderItem item : draft.items) {
            item.setOrderId(order.getOrderId());
        }
        orderItemMapper.insertBatch(draft.items);
        statusLogMapper.insert(order.getOrderId(), null, TakeoutOrder.STATUS_UNPAID,
                OPERATOR_USER, userId, "下单");

        for (TakeoutOrderItem item : draft.items) {
            dishMapper.increaseSales(item.getDishId(), item.getQuantity());
        }

        return new OrderDetail(order, draft.items);
    }

    // ============================================================
    // 查询与支付
    // ============================================================

    public List<TakeoutOrder> myOrders(Long userId) {
        return orderMapper.selectByUser(userId);
    }

    public OrderDetail orderDetail(Long orderId, Long userId) {
        TakeoutOrder order = orderMapper.selectById(orderId);
        if (order == null || !order.getUserId().equals(userId)) {
            throw new ServiceException("订单不存在");
        }
        return new OrderDetail(order, orderItemMapper.selectByOrder(orderId));
    }

    /**
     * 模拟支付。没有接真实支付渠道，只把状态从「待支付」推到「已支付」。
     * where 条件里带 status='0' 和 15 分钟时限，重复点击与超时支付都影响 0 行。
     */
    @Transactional(rollbackFor = Exception.class)
    public void pay(Long orderId, Long userId) {
        int rows = orderMapper.markPaid(orderId, userId);
        if (rows == 0) {
            throw new ServiceException("订单状态已变更，请刷新后重试");
        }
        statusLogMapper.insert(orderId, TakeoutOrder.STATUS_UNPAID, TakeoutOrder.STATUS_PAID,
                OPERATOR_USER, userId, "支付");
    }

    // ============================================================
    // 内部：算钱与校验
    // ============================================================

    /**
     * 校验并算出订单草稿。preview 与 create 共用同一份实现，
     * 保证「预览看到的金额」就是「下单扣的金额」。
     */
    private Draft buildDraft(Long userId, OrderCreateBody body) {
        validateItems(body);

        TakeoutShop shop = shopMapper.selectById(body.getShopId());
        if (shop == null) {
            throw new ServiceException("店铺不存在");
        }
        if (!"0".equals(shop.getStatus())) {
            throw new ServiceException("店铺休息中，暂不接单");
        }

        List<Long> dishIds = body.getItems().stream()
                .map(OrderCreateBody.Item::getDishId)
                .distinct()
                .collect(Collectors.toList());

        Map<Long, TakeoutDish> dishById = dishMapper.selectByIds(dishIds).stream()
                .collect(Collectors.toMap(TakeoutDish::getDishId, dish -> dish));
        if (dishById.size() != dishIds.size()) {
            throw new ServiceException("有菜品已下架，请返回重新选择");
        }
        for (TakeoutDish dish : dishById.values()) {
            if (!shop.getShopId().equals(dish.getShopId())) {
                throw new ServiceException("菜品不属于该店铺");
            }
        }

        List<TakeoutDishSpec> specs = specMapper.selectByDishIds(dishIds);
        Map<Long, List<TakeoutDishSpec>> specsByDish = specs.stream()
                .collect(Collectors.groupingBy(TakeoutDishSpec::getDishId));
        Map<Long, TakeoutDishSpec> specById = specs.stream()
                .collect(Collectors.toMap(TakeoutDishSpec::getSpecId, spec -> spec));

        List<Long> optionIds = body.getItems().stream()
                .filter(item -> item.getOptionIds() != null)
                .flatMap(item -> item.getOptionIds().stream())
                .distinct()
                .collect(Collectors.toList());
        Map<Long, TakeoutDishSpecOption> optionById = new HashMap<>();
        if (!optionIds.isEmpty()) {
            for (TakeoutDishSpecOption option : specMapper.selectOptionsByIds(optionIds)) {
                optionById.put(option.getOptionId(), option);
            }
        }

        List<TakeoutOrderItem> items = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        int itemCount = 0;

        for (OrderCreateBody.Item req : body.getItems()) {
            TakeoutDish dish = dishById.get(req.getDishId());
            List<TakeoutDishSpecOption> chosen = resolveOptions(
                    dish, req.getOptionIds(), specsByDish, specById, optionById);

            BigDecimal unitPrice = dish.getPrice();
            for (TakeoutDishSpecOption option : chosen) {
                if (option.getPriceDelta() != null) {
                    unitPrice = unitPrice.add(option.getPriceDelta());
                }
            }
            unitPrice = scale(unitPrice);

            BigDecimal subtotal = scale(unitPrice.multiply(BigDecimal.valueOf(req.getQuantity())));
            total = total.add(subtotal);
            itemCount += req.getQuantity();

            TakeoutOrderItem item = new TakeoutOrderItem();
            item.setDishId(dish.getDishId());
            item.setDishName(dish.getDishName());
            item.setSpecText(chosen.stream()
                    .map(TakeoutDishSpecOption::getOptionName)
                    .collect(Collectors.joining("/")));
            item.setOptionIds(chosen.stream()
                    .map(option -> String.valueOf(option.getOptionId()))
                    .collect(Collectors.joining(",")));
            item.setPrice(unitPrice);
            item.setQuantity(req.getQuantity());
            item.setSubtotal(subtotal);
            items.add(item);
        }

        BigDecimal totalAmount = scale(total);
        BigDecimal deliveryFee = scale(shop.getDeliveryFee() == null ? BigDecimal.ZERO : shop.getDeliveryFee());
        BigDecimal minAmount = scale(shop.getMinAmount() == null ? BigDecimal.ZERO : shop.getMinAmount());

        Draft draft = new Draft();
        draft.shop = shop;
        draft.items = items;
        draft.totalAmount = totalAmount;
        draft.deliveryFee = deliveryFee;
        draft.payAmount = scale(totalAmount.add(deliveryFee));
        draft.minAmount = minAmount;
        draft.shortOfMin = scale(minAmount.subtract(totalAmount).max(BigDecimal.ZERO));
        draft.itemCount = itemCount;
        draft.address = resolveAddress(userId, body);
        draft.expectOptions = buildExpectOptions(shop);
        draft.expectTime = pickExpectTime(body.getExpectTime(), draft.expectOptions);
        return draft;
    }

    /**
     * 逐个 item 校验规格：选项必须存在、必须属于该菜品、必选组必须选满、单选组不能多选。
     * 校验通过返回选中的选项（顺序即 specText 的拼接顺序）。
     */
    private List<TakeoutDishSpecOption> resolveOptions(
            TakeoutDish dish,
            List<Long> optionIds,
            Map<Long, List<TakeoutDishSpec>> specsByDish,
            Map<Long, TakeoutDishSpec> specById,
            Map<Long, TakeoutDishSpecOption> optionById) {

        List<Long> ids = optionIds == null ? List.of() : optionIds;
        List<TakeoutDishSpecOption> chosen = new ArrayList<>();
        Set<Long> seen = new HashSet<>();

        for (Long optionId : ids) {
            TakeoutDishSpecOption option = optionById.get(optionId);
            if (option == null) {
                throw new ServiceException("所选规格已失效，请重新选择");
            }
            TakeoutDishSpec spec = specById.get(option.getSpecId());
            if (spec == null || !dish.getDishId().equals(spec.getDishId())) {
                throw new ServiceException("所选规格不属于「" + dish.getDishName() + "」");
            }
            if (seen.add(optionId)) {
                chosen.add(option);
            }
        }

        for (TakeoutDishSpec spec : specsByDish.getOrDefault(dish.getDishId(), List.of())) {
            long picked = ids.stream()
                    .map(optionById::get)
                    .filter(option -> option != null && spec.getSpecId().equals(option.getSpecId()))
                    .count();
            if (Boolean.TRUE.equals(spec.getRequired()) && picked == 0) {
                throw new ServiceException("请选择「" + spec.getSpecName() + "」");
            }
            if (!Boolean.TRUE.equals(spec.getMulti()) && picked > 1) {
                throw new ServiceException("「" + spec.getSpecName() + "」只能选一项");
            }
        }
        return chosen;
    }

    private TakeoutAddress resolveAddress(Long userId, OrderCreateBody body) {
        if (body.getAddressId() != null) {
            TakeoutAddress address = addressMapper.selectById(body.getAddressId(), userId);
            if (address == null) {
                throw new ServiceException("收货地址不存在");
            }
            return address;
        }
        return addressMapper.selectDefault(userId);
    }

    /**
     * 配送时间档位：尽快送达 + 之后两个半点。
     *
     * 时间由服务端算，客户端只回传 value —— 否则客户端时钟不准或时区不对就会错。
     */
    private List<OrderPreview.ExpectOption> buildExpectOptions(TakeoutShop shop) {
        SimpleDateFormat full = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        SimpleDateFormat shortFormat = new SimpleDateFormat("HH:mm");

        int minutes = shop.getDeliveryTime() == null ? 30 : shop.getDeliveryTime();
        Calendar calendar = Calendar.getInstance();
        calendar.add(Calendar.MINUTE, minutes);

        List<OrderPreview.ExpectOption> options = new ArrayList<>(3);
        options.add(new OrderPreview.ExpectOption(
                "asap",
                "尽快送达（约 " + minutes + " 分钟）",
                full.format(calendar.getTime())));

        // 对齐到下一个半点
        int remainder = calendar.get(Calendar.MINUTE) % 30;
        calendar.add(Calendar.MINUTE, remainder == 0 ? 30 : 30 - remainder);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);

        for (int i = 0; i < 2; i++) {
            String label = shortFormat.format(calendar.getTime());
            options.add(new OrderPreview.ExpectOption(
                    label,
                    "预计 " + label + " 送达",
                    full.format(calendar.getTime())));
            calendar.add(Calendar.MINUTE, 30);
        }
        return options;
    }

    /** 客户端传的 value 找不到就退回「尽快送达」，不接受客户端自己编时间。 */
    private String pickExpectTime(String requested, List<OrderPreview.ExpectOption> options) {
        if (options.isEmpty()) {
            return null;
        }
        if (requested != null) {
            for (OrderPreview.ExpectOption option : options) {
                if (option.getValue().equals(requested)) {
                    return option.getExpectTime();
                }
            }
        }
        return options.get(0).getExpectTime();
    }

    private void validateItems(OrderCreateBody body) {
        if (body.getShopId() == null) {
            throw new ServiceException("店铺参数缺失");
        }
        List<OrderCreateBody.Item> items = body.getItems();
        if (items == null || items.isEmpty()) {
            throw new ServiceException("购物车是空的");
        }
        if (items.size() > MAX_ITEM_KINDS) {
            throw new ServiceException("单次最多点 " + MAX_ITEM_KINDS + " 种商品");
        }
        for (OrderCreateBody.Item item : items) {
            if (item.getDishId() == null) {
                throw new ServiceException("商品参数不合法");
            }
            if (item.getQuantity() == null
                    || item.getQuantity() < 1
                    || item.getQuantity() > MAX_QUANTITY) {
                throw new ServiceException("数量必须在 1-" + MAX_QUANTITY + " 之间");
            }
        }
    }

    // ============================================================
    // 工具
    // ============================================================

    private BigDecimal scale(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    private String generateOrderNo() {
        String stamp = new SimpleDateFormat("yyyyMMddHHmmss").format(new Date());
        int tail = ThreadLocalRandom.current().nextInt(1000, 10000);
        return "TO" + stamp + tail;
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String limit(String value, int max) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() > max ? trimmed.substring(0, max) : trimmed;
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    /** preview 与 create 共用的计算结果，避免两边算钱逻辑走偏。 */
    private static class Draft {
        private TakeoutShop shop;
        private List<TakeoutOrderItem> items;
        private BigDecimal totalAmount;
        private BigDecimal deliveryFee;
        private BigDecimal payAmount;
        private BigDecimal minAmount;
        private BigDecimal shortOfMin;
        private int itemCount;
        private TakeoutAddress address;
        private List<OrderPreview.ExpectOption> expectOptions;
        private String expectTime;
    }
}
