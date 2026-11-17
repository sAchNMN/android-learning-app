# 服务端逻辑（P2）

契约见 [`api.md`](api.md)，页面交互见 [`page-design.md`](page-design.md)。
本文件只讲**服务端怎么实现**：分层落位、状态机、事务、校验、幂等、越权防护。

## 1. 代码落位

沿用现有结构（`server/business/takeout/`，包名 `com.ruoyi.takeout`），P2 扩展为：

```
server/business/takeout/
├── controller/
│   ├── AppShopController.java        /app/shop/**  /app/search/**  /app/home/**
│   ├── AppOrderController.java       /app/order/**
│   ├── AppAddressController.java     /app/address/**
│   └── AppFavoriteController.java    /app/favorite/**
├── service/
│   ├── TakeoutShopService.java       店铺、分类、搜索、首页聚合
│   ├── TakeoutOrderService.java      结算预览、下单、支付、取消、收货、订单查询
│   ├── TakeoutAddressService.java    地址簿
│   └── TakeoutReviewService.java     评价
├── mapper/                           简单查询用注解
├── domain/                           实体 + 请求体 + 视图对象
└── resources/mapper/takeout/         复杂动态 SQL 用 XML
```

**为什么把 `AppTakeoutController` 拆成四个**：P2 后接口从 6 个涨到 22 个，
单类会到 400 行以上，且四块业务没有共享状态。若依默认扫描
`classpath*:mapper/**/*Mapper.xml`，XML 放 `server/resources/mapper/takeout/` 即可，不用改 yml。

**Mapper 写法**：现有约定是注解 + 显式别名（`shop_id as shopId`），继续沿用。
但分页 + 动态排序 + 多状态过滤这种 SQL 用注解拼字符串会变成不可读的 `StringBuilder`，
这几条改用 XML（`ShopMapper.xml` / `OrderMapper.xml`），其余保持注解。
不要为了统一而统一，可读性优先。

## 2. 分层职责

| 层 | 做什么 | 不做什么 |
| --- | --- | --- |
| Controller | 取 `SecurityUtils.getUserId()`、参数绑定、包 `AjaxResult` | 任何业务判断 |
| Service | 校验、算钱、事务、状态机 | 拼 SQL、处理 HTTP |
| Mapper | 单表与固定联表查询 | 业务条件判断 |

Controller 里**禁止出现 `if (xxx == null) return error(...)` 之外的分支**，参数合法性交给 Service，
这样业务规则只有一处，改规则不用满项目找。

## 3. 订单状态机

### 3.1 状态与转移

```
             ┌──────────── 支付(用户) ────────────┐
             │                                    ▼
  [创建] ──► 0 待支付 ──取消(用户)──► 4 已取消   1 已支付
               │                        ▲           │
               │ 超时15min(系统)         │           │ 接单(商家)
               └────────────────────────┘           ▼
                                                 2 配送中
                                                    │
                                          确认收货(用户) / 完成(系统)
                                                    ▼
                                                 3 已完成 ──评价──► review_status=1
```

### 3.2 转移表（唯一可信来源）

| from | to | 触发 | 操作方 | 守卫条件 |
| --- | --- | --- | --- | --- |
| — | 0 | 下单 | 用户 | 购物车非空、达起送价、店铺营业中 |
| 0 | 1 | 支付 | 用户 | 订单属当前用户、未超时 |
| 0 | 4 | 取消 | 用户 | 未超时（超时也允许取消，结果一样） |
| 0 | 4 | 超时取消 | 系统 | `create_time + 15min < now()` |
| 1 | 2 | 接单发货 | 商家（后台） | — |
| 1 | 4 | 取消 | 用户 | 仅在商家未接单时允许（`status=1` 且 `deliver_time is null`） |
| 2 | 3 | 确认收货 | 用户 | — |
| 2 | 3 | 自动完成 | 系统 | 发货后 2 小时（P3 定时任务，P2 不做） |

**除表中列出的转移，其余一律拒绝。** 不要写"status < 3 就允许取消"这种模糊判断。

### 3.3 落地方式：带条件的 UPDATE（CAS）

所有状态变更都用一条带 `WHERE status = 原状态` 的 UPDATE，靠影响行数判断成败：

```java
// 支付
int rows = orderMapper.pay(orderId, userId, now());
if (rows == 0) {
    throw new ServiceException("订单状态已变更，请刷新后重试");
}
```

```xml
<update id="pay">
  update takeout_order
     set status = '1', pay_time = #{now}, update_time = #{now}
   where order_id = #{orderId}
     and user_id = #{userId}
     and status = '0'
     and create_time &gt; date_sub(#{now}, interval 15 minute)
</update>
```

好处：

- 天然防重复支付（第二次 `status` 已是 1，影响 0 行）
- 天然防并发（两个请求同时进来只有一个能更新成功）
- 不需要 `SELECT ... FOR UPDATE`，不锁行

**禁止**先 `select` 判断状态再 `update`。中间的时间窗口就是超卖和重复支付的来源。

## 4. 结算预览 `POST /app/order/preview`

服务端算钱的唯一入口，`create` 复用同一套计算逻辑（抽成私有方法 `buildDraft()`）。

### 4.1 入参

```json
{
  "shopId": 1,
  "items": [
    { "dishId": 1, "quantity": 1, "optionIds": [12, 15] },
    { "dishId": 4, "quantity": 2, "optionIds": [] }
  ],
  "addressId": 3
}
```

`optionIds` 为空数组表示无规格。**不要**让客户端传 `price` 或 `subtotal`。

### 4.2 处理步骤

```
1. 校验 items 非空、条数 <= 50、每项 quantity 1..99
2. 查店铺：存在、del_flag=0、status=0（营业中）→ 否则 ServiceException("店铺休息中")
3. 一次查全部菜品：select ... where dish_id in (...) and shop_id=? and status='0' and del_flag='0'
   - 数量对不上 → 抛出具体菜名："「卤蛋」已下架"
4. 查这些菜品涉及的规格选项：
   select op.*, sp.dish_id, sp.required, sp.multi
     from takeout_dish_spec_option op
     join takeout_dish_spec sp on sp.spec_id = op.spec_id
    where op.option_id in (...) and op.del_flag='0' and op.status='0'
   - 选项不属于该菜品 → "规格已失效，请重新选择"
5. 校验必选组：对每个菜品，其 required='0' 的每个规格组，必须恰好命中 1 个选项
   （multi='0' 的组命中 >1 也报错）
6. 单价 = dish.price + Σ option.price_delta
   小计 = 单价 × quantity，用 BigDecimal，setScale(2, HALF_UP)
7. 商品总额 = Σ 小计；配送费 = shop.delivery_fee；
   实付 = 商品总额 + 配送费
8. shortOfMin = max(0, shop.min_amount - 商品总额)
9. 地址：addressId 为空则取该用户 is_default='1' 的一条；
   传了则必须属于当前用户，否则 "地址不存在"
10. 组装 expectOptions：以当前时间 + shop.delivery_time 为基准，
    生成「尽快送达」+ 两个整点/半点档位（09:00-21:00 之外不生成整点档）
```

**算钱的细节**：`BigDecimal` 全程保留 2 位，比较用 `compareTo` 不用 `equals`。
数量乘加只做一次，不要"先算单价再累加再四舍五入"多次舍入。

### 4.3 返回

见 `page-design.md` D2 的响应示例。**`shortOfMin > 0` 时也返回 200**，
让客户端能在结算页显示"还差 ¥3 起送"，而不是直接报错把用户踢回上一页。

只有在 `items` 全部无效（下架/规格失效）时才返回业务错误。

## 5. 下单 `POST /app/order/create`

### 5.1 入参

```json
{
  "clientToken": "b3f1c2d4-...",
  "shopId": 1,
  "items": [ { "dishId": 1, "quantity": 1, "optionIds": [12,15] } ],
  "addressId": 3,
  "expectTime": "asap",
  "remark": "少放辣"
}
```

**不传金额。** `totalAmount` / `deliveryFee` / `payAmount` 全部服务端算。

### 5.2 事务步骤

```java
@Transactional(rollbackFor = Exception.class)
public OrderDetail createOrder(Long userId, OrderCreateBody body) {
    // 1 幂等：同一 clientToken 5 分钟内只允许成功一次
    String key = "takeout:order:token:" + userId + ":" + body.getClientToken();
    if (!redisCache.setCacheObjectIfAbsent(key, "1", 5, TimeUnit.MINUTES)) {
        throw new ServiceException("订单正在提交，请勿重复点击");
    }

    // 2 复用 preview 的计算（含店铺、菜品、规格、金额、地址校验）
    OrderDraft draft = buildDraft(userId, body);
    if (draft.getShortOfMin().compareTo(BigDecimal.ZERO) > 0) {
        throw new ServiceException("还差 ¥" + draft.getShortOfMin() + " 起送");
    }

    // 3 插订单（order_no 由 时间戳+随机 生成，冲突抛 uk_order_no 由外层重试一次）
    TakeoutOrder order = draft.toOrder(userId);
    orderMapper.insert(order);

    // 4 批量插明细（含 spec_text / option_ids 快照）
    orderItemMapper.insertBatch(draft.toItems(order.getOrderId()));

    // 5 写状态日志
    statusLogMapper.insert(order.getOrderId(), null, "0", "0", userId, "下单");

    // 6 累加销量（月售 + 总销量）
    for (DishLine line : draft.getLines()) {
        dishMapper.increaseSales(line.getDishId(), line.getQuantity());
    }

    // 7 期望送达时间落库
    // expectTime 由服务端换算成绝对时间，客户端传的字符串不直接入库

    return orderDetail(order.getOrderId(), userId);
}
```

顺序不能调：**幂等占位在最前**（后面任何一步失败，占位还在，用户重试会拿到"请勿重复点击"，
所以失败时要删占位 —— 见 5.4）。

`order_no` 生成：`yyyyMMddHHmmss` + 4 位随机，共 18 位。`uk_order_no` 冲突时重试一次，
再冲突就抛异常（概率极低，不值得写循环）。

### 5.3 销量累加的取舍

下单时 `update takeout_dish set sales = sales + #{qty}, month_sales = month_sales + #{qty}`。
取消订单时**扣回**。已支付后退款（P2 不做）才需要更复杂的补偿。

月售不按自然月归零，是近似的累计值。真要精确就按月分区统计订单，成本远超收益，
在字段注释里写明"近似值"。

### 5.4 失败清理

`@Transactional` 只回滚数据库，**Redis 占位不会回滚**。所以：

```java
try {
    return doCreate(userId, body);
} catch (RuntimeException e) {
    redisCache.deleteObject(key);   // 业务失败释放占位，允许用户改完再提交
    throw e;
}
```

`clientToken` 由客户端在进入结算页时生成一次，**失败重试复用同一个**。
这样"网络超时但其实已下单成功"的场景，重试会命中占位，不会产生两单。

## 6. 取消与确认收货

```java
// 取消：只有待支付、或已支付但未发货 可取消
@Transactional
public void cancel(Long orderId, Long userId, String reason) {
    int rows = orderMapper.cancel(orderId, userId, reason, now());   // where status in ('0','1') and deliver_time is null
    if (rows == 0) throw new ServiceException("订单当前状态不可取消");
    orderItemMapper.selectByOrder(orderId)
        .forEach(i -> dishMapper.decreaseSales(i.getDishId(), i.getQuantity()));
    statusLogMapper.insert(orderId, null, "4", "0", userId, reason);
}

// 确认收货
int rows = orderMapper.confirm(orderId, userId, now());  // where status = '2'
if (rows == 0) throw new ServiceException("订单当前状态不可确认收货");
```

取消原因做长度截断（128 字），后端只存文本，不做枚举校验 —— 客户端已经限定选项，
后端再校验一遍枚举值属于过度设计，但要防超长和 XSS（若依的 `@Xss` 注解可直接用）。

## 7. 惰性取消待支付订单

不写定时任务。在三个读取点判断超时：

```java
private void cancelIfExpired(TakeoutOrder order) {
    if ("0".equals(order.getStatus())
            && order.getCreateTime().before(DateUtils.addMinutes(new Date(), -15))) {
        int rows = orderMapper.expireCancel(order.getOrderId());   // where status='0'
        if (rows > 0) {
            order.setStatus("4");
            order.setCancelReason("超时未支付，系统自动取消");
        }
    }
}
```

调用点：`orderDetail`、`orderPage`（列表里逐条判断，只在命中时写库）。

优点：零调度、零额外部署；缺点：用户不查就永远停在数据库里的"待支付"，
但用户看不到它，也就无所谓。后台统计要看真实状态时，在后台列表里同样做一次惰性判断。

**不要在 `pay()` 里也做惰性取消**：支付接口的 SQL 已经带了 15 分钟条件，超时自然影响 0 行，
返回"订单状态已变更"，够用。

## 8. 越权防护

这是 C 端项目最容易出事故的地方。统一规则：

| 资源 | 规则 |
| --- | --- |
| 订单 | 所有 order 的 SQL 都带 `and user_id = #{userId}`，**包括 update** |
| 地址 | 同上，`address_id + user_id` 双条件 |
| 评价 | 校验订单属于用户且 `status='3'` 且 `review_status='0'` |
| 收藏 | 只操作 `(user_id, shop_id)` 这一对，不接受前端传 favId |
| 店铺/菜品 | 全局可见，不需归属校验，但要过滤 `del_flag='0'` |

`userId` 一律来自 `SecurityUtils.getUserId()`，**任何请求体里出现 `userId` 字段都视为错误**，
代码评审直接拒。

文档里出现过两次的问题：`orderDetail(orderId, userId)` 这种签名是对的，
但别在实现里图省事只按 `orderId` 查再在 Java 里比对 —— 那样容易漏掉 `del_flag` 与越权日志。
直接让 SQL 带上条件，查不到就是"订单不存在"。

## 9. 分页协议

若依的 `PageHelper` 是 `startPage()` 拦截下一条 SQL，容易和嵌套查询冲突。约定：

- 只有列表接口分页，详情接口不分页
- Service 方法签名是 `(param, pageNum, pageSize)`，内部 `PageHelper.startPage(pageNum, pageSize)` 后
  **紧跟一条查询**，中间不允许插别的 SQL
- 返回 `TableDataInfo`（`total` + `rows`），与若依后台列表一致，管理端能直接复用表格组件

```java
public TableDataInfo shopPage(ShopQuery query) {
    PageHelper.startPage(query.getPageNum(), query.getPageSize());
    List<TakeoutShop> list = shopMapper.selectPage(query);   // 紧跟，不许插入其它查询
    return new TableDataInfo(list, new PageInfo(list).getTotal());
}
```

`pageSize` 服务端强制上限 50，`pageNum < 1` 时归 1。

## 10. 异常与错误码

沿用若依机制：

```java
throw new ServiceException("还差 ¥3.00 起送");   // → GlobalExceptionHandler → code=500 + msg
```

约定：

| 场景 | 做法 |
| --- | --- |
| 业务规则不满足 | `ServiceException(msg)`，msg 直接面向用户，要能指导操作 |
| 参数格式错误 | `ServiceException` 也够用，但 msg 要具体（"数量必须大于 0"） |
| 未登录 | 若依拦截器自动返回 `code=401` |
| 系统异常 | 交给 `GlobalExceptionHandler`，不要在 Controller 里 catch |

**msg 的写法规则**：带具体对象和数值。写"「卤蛋」已下架"不写"菜品不可用"；
写"还差 ¥3.00 起送"不写"未达起送价"。客户端是把 msg 直接 toast 出来的，含糊等于没提示。

## 11. 管理端接入

P2 新增 5 张运营表需要后台可维护：分类、菜品规格、店铺、订单、评价。

做法（省力且与若依一致）：

1. 若依代码生成器导入 `takeout_shop` / `takeout_shop_category` / `takeout_dish_spec` /
   `takeout_dish_spec_option` / `takeout_review`，生成 CRUD 页面
2. 菜单挂在「外卖管理」父菜单下，权限标识统一 `takeout:xxx:list/query/add/edit/remove`
3. 订单表生成后**只保留查询与状态流转**，禁止后台直接编辑金额与明细
4. 后台订单状态操作走专用接口 `PUT /takeout/order/{id}/status`，内部仍用 CAS，
   不允许直接 `update` 全字段

后台的订单列表页要能按 `status` / 时间段 / 店铺筛选，这是运营每天要看的页面，
比 C 端任何页面都重要，别糊弄。

## 12. 自测清单（每步实现后必跑）

| # | 场景 | 期望 |
| --- | --- | --- |
| 1 | 两个账号 A/B，A 用 B 的 orderId 查详情 | 500「订单不存在」 |
| 2 | 同一订单并发调两次 pay | 一次成功，一次「状态已变更」，`pay_time` 只有一个 |
| 3 | 提交订单后马上重放同一 `clientToken` | 第二次拒绝，库里只有一单 |
| 4 | 下单时传别的店铺的 dishId | 500「菜品不属于该店铺」 |
| 5 | 必选规格组不传 optionIds | 500「请选择份量」 |
| 6 | 商品总额 12 元、起送 15 元 | preview 返回 200 + `shortOfMin=3`；create 返回 500 |
| 7 | 菜品下架后重新 preview | 500 且 msg 带菜名 |
| 8 | 创建订单 16 分钟后再查详情 | 状态已是 4，`cancelReason` 为超时文案 |
| 9 | 删除默认地址 | 列表里另一条自动成为默认 |
| 10 | 同一订单评价两次 | 第二次 500「该订单已评价」 |
| 11 | `pageSize=1000` | 实际返回 ≤ 50 条 |
| 12 | 金额全是 0.1 元的 3 件商品 | 实付精确 0.30，无 0.30000000000000004 |
