package com.ruoyi.takeout.service;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.takeout.domain.OrderCreateBody;
import com.ruoyi.takeout.domain.OrderDetail;
import com.ruoyi.takeout.domain.TakeoutDish;
import com.ruoyi.takeout.domain.TakeoutOrder;
import com.ruoyi.takeout.domain.TakeoutOrderItem;
import com.ruoyi.takeout.domain.TakeoutShop;
import com.ruoyi.takeout.mapper.TakeoutDishMapper;
import com.ruoyi.takeout.mapper.TakeoutOrderItemMapper;
import com.ruoyi.takeout.mapper.TakeoutOrderMapper;
import com.ruoyi.takeout.mapper.TakeoutShopMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 订单域业务逻辑：下单、支付、我的订单、订单详情。
 *
 * 店铺与分类的读写已拆到 TakeoutShopService。
 * 金额一律用 BigDecimal，不用 double，避免累加出现 0.01 的误差。
 */
@Service
public class TakeoutService {

    @Autowired
    private TakeoutShopMapper shopMapper;

    @Autowired
    private TakeoutDishMapper dishMapper;

    @Autowired
    private TakeoutOrderMapper orderMapper;

    @Autowired
    private TakeoutOrderItemMapper orderItemMapper;

    /**
     * 下单。整个过程放在一个事务里：插订单 → 插明细 → 累加销量。
     * 任一步失败全部回滚，不会出现「有订单没明细」的脏数据。
     */
    @Transactional(rollbackFor = Exception.class)
    public OrderDetail createOrder(Long userId, OrderCreateBody body) {
        if (body == null || body.getItems() == null || body.getItems().isEmpty()) {
            throw new ServiceException("购物车是空的");
        }

        TakeoutShop shop = shopMapper.selectById(body.getShopId());
        if (shop == null) {
            throw new ServiceException("店铺不存在");
        }

        BigDecimal total = BigDecimal.ZERO;
        List<TakeoutOrderItem> items = new ArrayList<>();

        for (OrderCreateBody.Item reqItem : body.getItems()) {
            if (reqItem.getDishId() == null || reqItem.getQuantity() == null || reqItem.getQuantity() <= 0) {
                throw new ServiceException("商品参数不合法");
            }

            TakeoutDish dish = dishMapper.selectById(reqItem.getDishId());
            if (dish == null || !shop.getShopId().equals(dish.getShopId())) {
                throw new ServiceException("菜品不存在或不属于该店铺");
            }

            BigDecimal subtotal = dish.getPrice().multiply(BigDecimal.valueOf(reqItem.getQuantity()));
            total = total.add(subtotal);

            TakeoutOrderItem item = new TakeoutOrderItem();
            item.setDishId(dish.getDishId());
            item.setDishName(dish.getDishName());
            item.setPrice(dish.getPrice());
            item.setQuantity(reqItem.getQuantity());
            item.setSubtotal(subtotal);
            items.add(item);
        }

        if (shop.getMinAmount() != null && total.compareTo(shop.getMinAmount()) < 0) {
            throw new ServiceException("还差 " + shop.getMinAmount().subtract(total) + " 元起送");
        }

        BigDecimal deliveryFee = shop.getDeliveryFee() == null ? BigDecimal.ZERO : shop.getDeliveryFee();

        TakeoutOrder order = new TakeoutOrder();
        order.setOrderNo(generateOrderNo());
        order.setUserId(userId);
        order.setShopId(shop.getShopId());
        order.setShopName(shop.getShopName());
        order.setTotalAmount(total);
        order.setDeliveryFee(deliveryFee);
        order.setPayAmount(total.add(deliveryFee));
        order.setStatus(TakeoutOrder.STATUS_UNPAID);
        order.setReceiver(body.getReceiver());
        order.setPhone(body.getPhone());
        order.setAddress(body.getAddress());
        order.setRemark(body.getRemark());

        orderMapper.insert(order);

        for (TakeoutOrderItem item : items) {
            item.setOrderId(order.getOrderId());
        }
        orderItemMapper.insertBatch(items);

        for (TakeoutOrderItem item : items) {
            dishMapper.increaseSales(item.getDishId(), item.getQuantity());
        }

        return new OrderDetail(order, items);
    }

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
     * 用 where 条件兜底：重复点击不会重复置为已支付。
     */
    @Transactional(rollbackFor = Exception.class)
    public void pay(Long orderId, Long userId) {
        int rows = orderMapper.markPaid(orderId, userId);
        if (rows == 0) {
            throw new ServiceException("订单不存在或当前状态不可支付");
        }
    }

    private String generateOrderNo() {
        String stamp = new SimpleDateFormat("yyyyMMddHHmmss").format(new Date());
        int tail = ThreadLocalRandom.current().nextInt(1000, 10000);
        return "TO" + stamp + tail;
    }
}
