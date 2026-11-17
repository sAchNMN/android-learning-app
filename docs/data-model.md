# 数据模型（P2）

对应脚本：`sql/03_takeout_p2_schema.sql`（结构）、`sql/04_takeout_p2_data.sql`（数据）。
字段清单以脚本为准，本文件讲**为什么这么设计**与**页面查询怎么落到索引上**。

## 1. 全景

```
                      ┌──────────────────┐
                      │ takeout_shop_    │
                      │ category         │ 分类（金刚区 / 筛选）
                      └────────┬─────────┘
                               │ category_id
┌──────────────┐      ┌────────▼─────────┐      ┌──────────────────┐
│ takeout_     │      │ takeout_shop     │◄─────┤ takeout_shop_    │
│ hot_keyword  │      │  店铺             │      │ favorite 收藏     │
└──────────────┘      └───┬──────────┬───┘      └──────────────────┘
                          │          │
                ┌─────────▼──┐   ┌───▼──────────┐
                │ takeout_   │   │ takeout_     │
                │ dish 菜品   │   │ review 评价   │
                └──┬──────┬──┘   └───▲──────────┘
                   │      │          │
        ┌──────────▼┐  ┌──▼──────────┴────┐
        │ dish_spec │  │ takeout_order    │
        │ 规格组     │  │  订单             │
        └─────┬─────┘  └──┬────────────┬──┘
              │           │            │
     ┌────────▼───────┐   │      ┌─────▼──────────────┐
     │ dish_spec_     │   │      │ takeout_order_item │
     │ option 选项     │   │      │ 明细（快照）        │
     └────────────────┘   │      └────────────────────┘
                          │
                ┌─────────▼─────────┐   ┌────────────────────┐
                │ takeout_order_    │   │ takeout_address    │
                │ status_log 日志    │   │ 地址簿              │
                └───────────────────┘   └────────────────────┘
```

订单表用 `address_id` 指向地址簿，同时把 `receiver/phone/address` **冗余一份**。
地址簿改了不影响历史订单，这是快照，不是冗余错误。

## 2. 表清单

| 表 | 性质 | 谁写 | 谁读 |
| --- | --- | --- | --- |
| `takeout_shop_category` | 新 | 管理端 | 首页、店铺列表 |
| `takeout_dish_spec` | 新 | 管理端 | 店铺详情、下单校验 |
| `takeout_dish_spec_option` | 新 | 管理端 | 店铺详情、下单校验、金额计算 |
| `takeout_address` | 新 | App 用户 | 地址列表、结算页、下单 |
| `takeout_order_status_log` | 新 | 服务端 | 后台审计（页面不读） |
| `takeout_review` | 新 | App 用户 | 订单详情、店铺评分 |
| `takeout_shop_favorite` | 新 | App 用户 | 收藏列表、店铺详情 |
| `takeout_hot_keyword` | 新 | 管理端 | 搜索页 |
| `takeout_shop` | 改 | 管理端 | 首页、列表、详情 |
| `takeout_dish` | 改 | 管理端 | 详情、下单 |
| `takeout_order` | 改 | 服务端 | 列表、详情 |
| `takeout_order_item` | 改 | 服务端 | 详情、再来一单 |

## 3. 新增表设计要点

### 3.1 店铺分类 `takeout_shop_category`

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| category_id | bigint PK | |
| category_name | varchar(32) UK | 名称唯一，避免运营建两个"面食" |
| icon | varchar(128) | 存 drawable 资源名或 URL，前端自行决定怎么取 |
| sort_no | int | 越小越前 |
| status | char(1) | 0 显示 / 1 隐藏 |

`takeout_shop.category` 文本字段**保留不删**。原因：筛选走 `category_id`（有索引、能改名），
展示走文本（不用 join 就能出"分类：面食"）。这是典型的"查询字段与展示字段分离"，
不是重复设计。

### 3.2 菜品规格 `takeout_dish_spec` + `takeout_dish_spec_option`

拆两张表而不是一张 JSON：

- 规格组有 `required` / `multi` 两个策略，属于组级属性
- 选项有加价、默认值、排序，属于项级属性
- 下单校验"必选组是否已选"必须按组遍历，一张表混着存要反复分组

关键约束：

| 约束 | 落地方式 |
| --- | --- |
| 单选组最多选一个 | 服务端校验，靠 `multi='0'` 判断 |
| 必选组必须选一个 | 服务端校验，靠 `required='0'` 判断 |
| 选项必须属于该菜品的规格组 | 下单时 `option.spec_id` join `spec.dish_id` 校验，防串菜 |
| 加价不能为负 | 数据库不约束（MySQL 8 check 兼容性差），服务端校验 `price_delta >= 0` |

`takeout_dish.has_spec` 是给列表页用的**冗余标记**：店铺详情渲染 30 个菜品，
不能在列表接口里为每个菜品查一次规格表（N+1）。列表按标记决定点 `+` 弹不弹窗，
真实规格在点开时再拉（或详情接口一次返回，见 api.md）。

### 3.3 地址簿 `takeout_address`

| 设计点 | 做法 | 理由 |
| --- | --- | --- |
| 归属 | `user_id` 必填，所有查询带 `user_id` 条件 | 防越权，比在 SQL 里 join sys_user 便宜 |
| 默认地址唯一 | 服务端事务：先把同用户其它行 `is_default='0'`，再置当前行 `'1'` | 数据库唯一索引做不到"部分唯一" |
| 删除 | `del_flag='2'` 逻辑删除 | 历史订单可能引用过，物理删会留下悬空 `address_id` |
| 数量上限 | 20 条，服务端 `count` 校验 | 防刷，避免有人脚本塞十万条 |
| 省市区 | 三个 varchar，暂不接地址库 | 学习项目不引第三方地址库；前端用文本 + 常用城市快捷 |

默认地址的坑：**删除默认地址后必须有新的默认**，否则结算页取不到默认地址，
用户每次都要手动选。服务端在删除事务里做：若删的是默认地址，取该用户剩下的
`update_time` 最新一条置为默认。

### 3.4 订单状态日志 `takeout_order_status_log`

只给后台审计用，**订单详情页的时间轴不读这张表**。

原因：时间轴需要的是"下单/支付/发货/收货"四个绝对时间点，这些在 `takeout_order`
上有专门字段（`create_time` / `pay_time` / `deliver_time` / `receive_time`），
一次查询就拿到，且天然不会重复。日志表是流水，同一状态可能写多条（比如后台反复改），
渲染时间轴会出现重复节点。

两边都留的代价是写两处，收益是：查询快、审计全。值得。

### 3.5 评价 `takeout_review`

| 设计点 | 做法 |
| --- | --- |
| 一单一评 | `UNIQUE KEY uk_review_order(order_id)`，并发重复提交靠 DB 兜底 |
| 店铺评分 | 评价成功后**重算** `takeout_shop.rating = ROUND(AVG(rating),1)`，不累加 |
| 软删除 | `del_flag`，后台删评价后重算一次店铺评分 |
| 匿名 | `is_anonymous` 只影响 C 端展示，`user_id` 照常落库 |

为什么重算而不是累加：累加一旦有删除、改评、并发写就会漂移，且无法自愈。
重算是幂等的，一行 `UPDATE ... SET rating = (SELECT ...)` 就能纠正所有历史错误。
数据量小时（学习项目）重算的成本可以忽略；真到几万条评价，再改成定时任务重算。

## 4. 既有表改造

### 4.1 `takeout_order` 新增字段

| 字段 | 用途 | 为什么必须加 |
| --- | --- | --- |
| `address_id` | 指向地址簿 | "再来一单"能回填地址；也便于统计某地址的下单量 |
| `item_count` | 商品总件数 | 列表页要显示"共 3 件"，不加就得对每个订单 `sum(quantity)`，N+1 |
| `expect_time` | 期望送达 | 结算页选的配送时间必须落库，骑手端（未来）要看 |
| `deliver_time` | 商家发货 | 时间轴节点，缺了就无法展示"商家已接单"的具体时间 |
| `receive_time` | 确认收货 | 与 `finish_time` 区分：`finish_time` 是系统判定完成，`receive_time` 是用户点击 |
| `cancel_reason` / `cancel_time` | 取消信息 | 详情页要展示"取消原因：不想要了" |
| `review_status` | 0 未评价 / 1 已评价 | 列表页决定显示"去评价"还是"已评价"，避免 join review 表 |

`item_count` 是冗余，写入时机只有一个（下单事务内），不存在不一致风险。
凡是"只有一个写入点"的冗余都能加；多个写入点的冗余才是定时炸弹。

### 4.2 `takeout_order_item` 新增字段

| 字段 | 用途 |
| --- | --- |
| `spec_text` | 规格快照，"大份/微辣"。展示用，菜品改规格后历史订单不变 |
| `option_ids` | 选项 ID 逗号串，"12,15"。**"再来一单"还原购物车用**，纯文本展示用不上 |

为什么不建 `takeout_order_item_option` 关联表：明细选项只用于展示和还原，
不参与任何统计与查询。建第三张表会让下单事务多一次批量插入，
收益是理论上更规范，成本是每次查询都要 join。学习项目选简单方案，并在字段注释里写明它是快照。

`spec_text` 与 `option_ids` 的关系：前者给人看，后者给程序用。两者都不保证与当前规格表一致，这是故意的。

### 4.3 `takeout_shop` / `takeout_dish` 新增字段

- `month_sales`：列表页展示"月售 1286"。真实实现应按月聚合订单，学习项目下单时自增，注释里注明数据是近似的。
- `open_time`：展示"09:00-21:30"。**不参与营业判断**，营业与否只看 `status`。跨天营业（22:00-02:00）的时段解析是无底洞，不碰。
- `image`：头图为空时前端用色块占位，不阻塞开发。
- `dish.sort_no`：同时决定组内顺序和分类分组顺序（按组内最小值）。不额外建"菜品分类表"，
  因为菜品分类是店铺私有的一层，做全局表会引入"店铺 A 的招牌面"和"店铺 B 的招牌面"要不要共用的无解问题。

## 5. 索引

| 索引 | 支撑的查询 |
| --- | --- |
| `idx_shop_category(category_id, status, del_flag)` | L1 分类筛选 + 只查营业中 |
| `idx_shop_status(status, del_flag)`（已有） | 首页推荐店铺 |
| `idx_dish_shop(shop_id, status, del_flag)`（已有） | D1 店铺菜品 |
| `idx_spec_dish(dish_id, del_flag, sort_no)` | D1 规格组 |
| `idx_option_spec(spec_id, del_flag, sort_no)` | D1 规格选项 |
| `idx_addr_user(user_id, del_flag, is_default)` | L3 地址列表（倒序取默认在前） |
| `idx_order_user(user_id, create_time)`（已有） | L4 "全部" Tab |
| `idx_order_user_status(user_id, status, create_time)` | L4 按状态过滤后的排序分页 |
| `idx_log_order(order_id, create_time)` | 后台查某单流转 |
| `uk_review_order(order_id)` | 一单一评（唯一约束本身就是索引） |
| `idx_review_shop(shop_id, del_flag, create_time)` | 店铺评价列表（P3 用） |
| `uk_fav_user_shop(user_id, shop_id)` | 收藏去重 |
| `idx_fav_user(user_id, create_time)` | L5 收藏列表 |

`idx_order_user_status` 与已有的 `idx_order_user` 有部分重叠，但列表页最常见的查询是
"某用户 + 某状态 + 按时间倒序"，走 `(user_id, status, create_time)` 可以完全避免排序。
保留旧索引是因为后台管理端按 `user_id + create_time` 查，删了会退化。

搜索用的是 `LIKE '%关键词%'`，**前缀通配符用不上 B+Tree 索引**。数据量在千级以内
全表扫可以接受；真要优化应上全文索引或 ES，不在本项目范围，故不为搜索建索引。

## 6. 购物车为什么不落库

结论：**购物车只存客户端（DataStore），不建表**。

理由：

1. 购物车是会话态数据，生命周期以小时计，跨设备同步的实际需求接近零
2. 落库就要处理：加购接口、改数量接口、删行接口、过期清理、变更通知，
   至少 4 个接口和 1 张表，换来的能力用户感知不到
3. 价格的最终裁决点在 `order/preview` 和 `order/create`，服务端每次都按当前菜品与规格重算，
   客户端存的购物车改了价也没用

需要服务端知道购物车内容的唯一时刻是结算，那时一次性把 `items` 提交给 preview，足够了。

代价：换手机后购物车丢失。接受。

## 7. 典型查询

**L1 店铺列表（分类 + 排序 + 分页）**

```sql
SELECT shop_id, shop_name, description, category, delivery_fee, min_amount,
       delivery_time, rating, month_sales, open_time, status, image
FROM takeout_shop
WHERE del_flag = '0'
  AND status = '0'
  AND (#{categoryId} IS NULL OR category_id = #{categoryId})
  AND (#{keyword} IS NULL OR shop_name LIKE CONCAT('%', #{keyword}, '%'))
ORDER BY
  CASE WHEN #{sort} = 'rating'  THEN rating        END DESC,
  CASE WHEN #{sort} = 'fast'    THEN delivery_time END ASC,
  CASE WHEN #{sort} = 'cheap'   THEN min_amount    END ASC,
  shop_id DESC
LIMIT #{offset}, #{pageSize};
```

排序用 `CASE WHEN` 而不是动态拼 SQL 字符串，是为了让 MyBatis 缓存与 SQL 注入风险都可控；
缺点是用不上排序索引，店铺量级下无所谓。真要优化就改成 `<choose>` 分支拼 `ORDER BY`。

**L4 订单列表（多状态 + 分页）**

```sql
SELECT order_id, order_no, shop_id, shop_name, total_amount, delivery_fee,
       pay_amount, item_count, status, create_time, review_status
FROM takeout_order
WHERE user_id = #{userId}
  AND (#{statusList} IS NULL OR status IN (/* 白名单校验后拼进来的 0-4 */))
ORDER BY create_time DESC
LIMIT #{offset}, #{pageSize};
```

**D1 店铺详情（一次拿全，避免 N+1）**

```sql
-- 1. 店铺 + 收藏状态
SELECT s.*, (SELECT COUNT(1) FROM takeout_shop_favorite f
             WHERE f.shop_id = s.shop_id AND f.user_id = #{userId}) > 0 AS favorited
FROM takeout_shop s WHERE s.shop_id = #{shopId} AND s.del_flag = '0';

-- 2. 菜品 + 规格（两条 SQL，Service 层按 dishId 组装）
SELECT * FROM takeout_dish
WHERE shop_id = #{shopId} AND status = '0' AND del_flag = '0'
ORDER BY sort_no, dish_id;

SELECT sp.spec_id, sp.dish_id, sp.spec_name, sp.required, sp.multi, sp.sort_no,
       op.option_id, op.option_name, op.price_delta, op.is_default, op.sort_no AS option_sort
FROM takeout_dish_spec sp
LEFT JOIN takeout_dish_spec_option op ON op.spec_id = sp.spec_id AND op.del_flag = '0' AND op.status = '0'
WHERE sp.dish_id IN (/* 上面查出的 dishId 列表 */) AND sp.del_flag = '0'
ORDER BY sp.dish_id, sp.sort_no, op.sort_no;
```

分组在 Service 层用 `LinkedHashMap<String, List<Dish>>` 做，按菜品顺序首次出现建立分组，
保证左右联动的分类顺序稳定（不能靠 `GROUP BY`，MySQL 不保证组的顺序）。

## 8. 数据一致性约束清单

应用层保证，不用物理外键（与若依风格一致，避免分库与批量导入时的死锁）：

| 约束 | 保证位置 |
| --- | --- |
| 订单明细的 dish 必属于订单的 shop | 下单事务校验 |
| 订单地址必属当前用户 | 下单事务校验 |
| 评价的 order 必属当前用户且已完成 | 评价事务校验 |
| 菜品删除不物理删（订单明细要能追溯） | `del_flag` |
| 金额字段不用 float | 全部 `decimal(10,2)`，Java 用 `BigDecimal` |
| 订单总额 = Σ 明细小计 | 下单事务内一次算完，不接受前端金额 |
