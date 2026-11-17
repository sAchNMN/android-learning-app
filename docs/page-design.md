# 页面设计与交互逻辑（二级 / 三级）

本文件定义页面的**层级归属、进入参数、UI 结构、状态机、异常分支**。
接口字段见 [`api.md`](api.md)，表结构见 [`data-model.md`](data-model.md)，
服务端事务与校验见 [`server-logic.md`](server-logic.md)。

## 1. 层级判定标准

只有一条规则，避免以后争论：

| 层级 | 判定 | 返回行为 |
| --- | --- | --- |
| 一级 | 底部 Tab 的落点，可长期停留，没有"上一页" | 按返回键退出 App |
| 二级 | 从一级点入的**列表 / 聚合**页，内部可无限滚动 | 返回到对应一级页 |
| 三级 | 从二级点入的**详情 / 表单 / 流程**页，承载一次完整业务动作 | 返回到二级页；流程完成后可跳级清栈 |

三级页面是"动作页"：结算、支付、评价都是**有始有终的一次事务**，所以它们不放在二级。
把结算塞进店铺详情的弹窗（当前实现）就是三级职责混进了三级详情页，P2 要拆出来。

## 2. 页面地图

```
一级（Tab）
├── T1 首页 HomeActivity .................. 定位、搜索入口、金刚区、推荐店铺
├── T2 订单 OrderListActivity ............. 状态 Tab + 分页        ← P2 改造
└── T3 我的 MineActivity .................. 地址簿、收藏、退出登录  ← P2 新增

二级（列表 / 聚合）
├── L1 店铺列表 ShopListActivity .......... 分类筛选、排序、分页     ← P2 改造
├── L2 搜索 SearchActivity ................ 热搜、历史、店铺/菜品双 Tab ← 新增
├── L3 地址列表 AddressListActivity ....... 选择模式 / 管理模式       ← 新增
├── L4 收藏列表 FavoriteListActivity ...... 收藏的店铺               ← 新增
└── (T2 订单列表本身就是二级)

三级（详情 / 表单 / 流程）
├── D1 店铺详情 ShopDetailActivity ........ 分类联动、规格、购物车    ← P2 改造
├── D2 结算确认 CheckoutActivity .......... 地址、配送时间、金额、提交 ← 新增
├── D3 支付 PayActivity ................... 模拟支付 + 倒计时         ← 新增
├── D4 订单详情 OrderDetailActivity ....... 时间轴、明细、操作        ← 新增
├── D5 地址编辑 AddressEditActivity ....... 新增 / 修改地址           ← 新增
└── D6 评价 ReviewActivity ................ 星级、标签、文字          ← 新增
```

导航与回传关系：

```
T1 ─┬→ L1 ─→ D1 ─→ D2 ─→ D3 ──成功──→ T2（CLEAR_TOP，回栈清空）
    ├→ L2 ─→ D1
    └→ L4 ─→ D1
T2 ──→ D4 ─┬→ D3（待支付时）
           ├→ D6（已完成未评价）
           └→ D1（再来一单）
T3 ─┬→ L3 ─→ D5
    └→ L4
D2 ─→ L3（选择模式，ActivityResult 回传 addressId）
```

## 3. 跨页面通用约定

### 3.1 三态渲染

每个列表 / 详情页统一三种状态，不允许"加载中白屏"：

| 状态 | 触发 | UI |
| --- | --- | --- |
| Loading | 首屏请求中 | 顶部细进度条，列表区保留旧数据 |
| Empty | 请求成功但列表为空 | 居中插图 + 一句文案 + 行动按钮 |
| Error | 请求失败 | 居中文案 = `AppResult.Failure.message` + 重试按钮 |

分页加载失败**不清空已有数据**，只在列表底部显示"加载失败，点击重试"。

### 3.2 参数传递

只用两种方式，禁止静态字段传参（进程被杀后为空）：

| 场景 | 方式 |
| --- | --- |
| 传 ID | `Intent.putExtra(EXTRA_XXX_ID, id)`，每个页面定义 `private const val EXTRA_XXX_ID` |
| 回传结果 | `registerForActivityResult(ActivityResultContracts.StartActivityForResult())`，结果放 `Intent` 的 `EXTRA_RESULT_*` |

`shopId` / `orderId` 这类关键参数同时写进 `SavedStateHandle`，进程重建后能恢复。

### 3.3 时间与金额

- 金额：网络层 `Double`，展示统一 `formatMoney()`，**结算页禁止用 Double 累加做金额展示**，一律用后端返回的 `totalAmount` / `payAmount`。
- 时间：后端统一 `yyyy-MM-dd HH:mm:ss` 字符串，客户端只做展示与倒计时计算，不参与业务判断（超时与否由服务端说了算）。

### 3.4 敏感操作的二次确认

取消订单、删除地址、清空购物车、退出登录：一律 `AlertDialog` 二次确认，确认按钮文案写具体动作（"取消订单"而不是"确定"）。

---

## 4. 二级页面

### L1 店铺列表 ShopListActivity

**入参**：`categoryId: Long`（可选，0 表示全部）、`categoryName: String`（标题用）、`keyword: String`（可选）

**UI 结构**

```
[← 返回] [标题：分类名/全部店铺] [🔍]
[综合] [评分] [送达最快] [起送最低]     ← 横向筛选栏
┌──────────────────────────────┐
│ 店铺名  评分4.8  月售1286        │
│ 起送¥15 配送¥3 约25分钟   [营业中]│
└──────────────────────────────┘
... 触底追加 / "没有更多了"
```

**数据来源**：`GET /app/shop/page`

**状态机**

| 事件 | 动作 |
| --- | --- |
| 进入 | 首次加载第 1 页，`pageSize=10` |
| 切换排序 | 重置 `pageNum=1`，清空列表，回到顶部 |
| 触底 | `pageNum+1`，追加；`rows.size < pageSize` 时置 `endReached`，不再请求 |
| 下拉刷新 | 重置到第 1 页，保留列表内容直到新数据返回 |

**边界**

- 分类下无店铺：空态文案"这个分类还没有店铺"，按钮"看看全部"（回 `categoryId=0`）
- 排序切换时请求竞态：ViewModel 里用 `Job` 取消上一个请求，只认最后一次
- 店铺休息中（`status=1`）：卡片置灰 + 角标"休息中"，**仍可点击进详情**（可预约，但不能下单，下单由服务端拦截）

**前端实现**

- `ShopListViewModel`：`StateFlow<ShopListState>`，字段 `list / pageNum / endReached / loading / loadingMore / error / sort / categoryId`
- `ShopAdapter`：`ListAdapter + DiffUtil`，item 布局 `item_shop.xml` 增加月售与"休息中"角标

### L2 搜索 SearchActivity

**入参**：`initialKeyword: String?`

**UI 结构**

```
[←] [搜索框：输入关键词] [搜索]
--- 输入为空 ---
热搜：  牛肉面  麻辣烫  沙拉  ...
历史：  卤蛋 ×   轻食 ×           [清空]
--- 提交后 ---
[店铺 12] [菜品 5]        ← 两个 Tab，各自独立分页
```

**数据来源**：`GET /app/search/hot`、`GET /app/search`

**交互与状态机**

| 事件 | 动作 |
| --- | --- |
| 进入 | 拉热搜词；历史词从本地 DataStore 读，最多 10 条 |
| 点热搜 / 历史 / 提交 | 写入历史（去重、置顶、截断 10 条）→ 请求第 1 页 |
| 切换 Tab | 保留各 Tab 已有结果，不重新请求 |
| 点店铺结果 | 进 D1 |
| 点菜品结果 | 进 D1 并带 `EXTRA_HIGHLIGHT_DISH_ID`，详情页滚动定位并高亮 2 秒 |

**边界**

- 关键词为空或全空格：不发请求，toast "请输入关键词"
- 单次关键词长度上限 20 字，超出截断
- 无结果：空态 + "换个词试试"，下方直接展示热搜
- **不做输入联想**：省一个接口和一个防抖状态机，收益不值。搜索按钮 + 软键盘"搜索"键触发

**前端实现**

- 历史词存 `DataStore`，key = `search_history`，逗号分隔；搜索页是唯一读写方
- 搜索结果两个 `ListAdapter` 复用一个 `SearchViewModel`，Tab 切换换数据源

### L3 地址列表 AddressListActivity

**入参**：`mode: String` = `select`（结算页进入）| `manage`（我的进入）

**UI 结构**

```
[←] 收货地址
┌────────────────────────────┐
│ 张三  138****8000  [家][默认] │
│ 郑州市金水区文化路 1 号        │
│                   编辑  删除  │
└────────────────────────────┘
[+ 新增收货地址]                        ← 底部固定
```

**数据来源**：`GET /app/address/list`、`DELETE /app/address/{id}`、`POST /app/address/{id}/default`

**交互与状态机**

| 事件 | 动作 |
| --- | --- |
| `select` 模式点击条目 | `setResult(RESULT_OK, addressId + 展示文案)` → `finish()` |
| `manage` 模式点击条目 | 进 D5 编辑 |
| 编辑 / 删除 | 按钮显式文字，不靠左滑（左滑无 affordance，学习项目不做） |
| 删除 | 二次确认 → 请求 → 成功后**整表重拉**，失败 toast。删的若是默认地址，服务端会把另一条置为默认，就地移除会让那条的"默认"标记错一整页 |
| 设默认 | 请求成功后**整表重拉**，因为其它行的 `is_default` 变了，局部刷新会不一致 |
| 从 D5 返回 | `onResume` 重拉列表（地址可能新增/修改） |

**边界**

- 列表为空：空态"还没有收货地址" + "新增地址"
- 删除的是默认地址：服务端把该用户最近更新的一条设为默认，返回体里不带新默认 ID，客户端直接重拉
- 请求失败：toast，列表保持原样（不做乐观删除）

### L4 订单列表 OrderListActivity

**入参**：`initialTab: Int`（默认"全部"）

**UI 结构**

```
[全部] [进行中] [待支付] [已完成] [已取消]
┌────────────────────────────────┐
│ 老王牛肉面            待支付 >   │
│ 红烧牛肉面 等 3 件              │
│ ¥57.00           [取消] [去支付]│
└────────────────────────────────┘
```

**数据来源**：`GET /app/order/page?status={tab}&pageNum=&pageSize=`

Tab 到 `status` 的映射（**这是最容易做错的地方**）：

| Tab | 传参 | 说明 |
| --- | --- | --- |
| 全部 | 空 | 不过滤 |
| 进行中 | `1,2` | 已支付待接单 + 配送中，用户视角都是"在路上" |
| 待支付 | `0` | |
| 已完成 | `3` | |
| 已取消 | `4` | |

接口的 `status` 参数是**逗号分隔多值**，SQL 用 `FIND_IN_SET` 或 `in` 拼接（服务端做白名单校验，只允许 `0-4` 的字符）。

**状态机**

| 事件 | 动作 |
| --- | --- |
| 切换 Tab | 各 Tab 独立缓存列表与 `pageNum`，切回来不重拉（`Map<tab, TabState>`） |
| 下拉刷新 | 只刷当前 Tab |
| 点条目 | 进 D4 |
| 点"去支付" | 进 D3（不进详情，少一次点击） |
| 点"取消" | 复用 D4 的取消弹窗逻辑，成功后本项状态就地更新为已取消并从"进行中"Tab 移除 |
| 点"确认收货" | 二次确认 → `POST /app/order/{id}/confirm` → 就地改状态 |
| 点"再来一单" | 拉订单明细 → 加入本地购物车 → 跳 D1 |
| 点"去评价" | 进 D6 |
| `onResume` | 若当前 Tab 有订单处于"待支付"或"进行中"，静默刷新一次（支付返回后状态要变） |

**边界**

- 待支付订单超时：**惰性取消**，不跑定时任务。服务端在查列表 / 查详情 / 支付时判断 `create_time + 15min < now`，命中则先置为已取消再返回。客户端只负责展示后端给的状态。
- 空态分 Tab 给不同文案："还没有订单" / "没有进行中的订单" / "没有待支付的订单"

### L5 收藏列表 FavoriteListActivity

**入参**：无

**UI 结构**：与 L1 的店铺卡片一致，右上角多一个"已收藏"实心图标；点击图标即取消收藏并移除该项。

**数据来源**：`GET /app/favorite/list`、`DELETE /app/favorite/{shopId}`

**边界**

- 空态："还没有收藏的店铺" + "去逛逛"（进 L1）
- 取消收藏后不弹二次确认（可逆操作，重新收藏即可），但要 toast "已取消收藏"

---

## 5. 三级页面

### D1 店铺详情 ShopDetailActivity

**入参**：`shopId: Long`（必填）、`highlightDishId: Long`（可选，从搜索菜品结果进入）

**UI 结构**

```
[←]  店铺名 描述/评分/月售  [♡收藏]
[店铺信息条：起送¥15 · 配送¥3 · 约25分钟 · 09:00-21:30]
┌────────┬───────────────────────┐
│ 招牌面  │ 红烧牛肉面  月售328  ¥22 │
│ 小菜    │ 大块牛腩，汤浓面劲   [+] │
│        │ 清汤牛肉面  月售215  ¥20 │
└────────┴───────────────────────┘
[🛒 3] ¥57.00        差¥0 起送     [去结算]
```

**数据来源**：`GET /app/shop/{shopId}`（返回店铺 + 按分类分组的菜品 + 是否已收藏）

响应结构改成**分组形式**，不是平铺 dishes：前端左右联动需要稳定的分类顺序。

```json
{
  "shop": { },
  "favorited": false,
  "groups": [
    { "category": "招牌面", "dishes": [ { "dishId": 1, "hasSpec": true, ... } ] },
    { "category": "小菜",   "dishes": [ ] }
  ]
}
```

**左右联动逻辑**（这是本页最容易写崩的地方）

1. 右侧 `RecyclerView` 用**扁平多类型列表**：分类头（`TYPE_HEADER`）+ 菜品（`TYPE_DISH`），单一 Adapter，不要嵌套 RecyclerView（嵌套滚动会打架）。
2. 右侧滚动监听 `onScrolled`：取第一个可见 item 的 position → 反查该 position 属于哪个分组 → 更新左侧高亮。**用户手动滚动右侧时置 `isUserScrolling=true`**，避免程序触发的滚动反过来又更新左侧，形成抖动。
3. 点左侧分类：`layoutManager.scrollToPositionWithOffset(headerPosition, 0)`，并置 `isUserScrolling=false`，等 300ms 后再恢复监听。
4. `highlightDishId` 存在时：找到该菜品 position → 滚过去 → 用 `ValueAnimator` 闪一下背景色（2 次），不做长驻高亮。

**购物车逻辑**

购物车是**本地状态**，不落库。理由见 `data-model.md` 第 6 节。

| 事件 | 动作 |
| --- | --- |
| 点 `+`（`hasSpec=false`） | 直接加 1 份，同一菜品累加数量 |
| 点 `+`（`hasSpec=true`） | 弹规格选择（`BottomSheetDialog`），选完才入车 |
| 入车 | key = `dishId + 排序后的 optionIds`，同 key 累加数量，不同 key 视为两行 |
| 减到 0 | 从购物车移除该行 |
| 底部栏点击 | 展开购物车弹层：逐行增减、清空 |
| 去结算 | `itemCount=0` 或 `商品小计 < minAmount` 时置灰 |
| 跨店 | 购物车按 `shopId` 存，进入另一家店时若其它店有货，弹窗"清空其它店铺购物车？"，确认后清空 |

**规格选择弹层（D1 的子界面，不单独建页面）**

- 每个规格组一块，组名 + 必选/可选标记；单选组用 `ChipGroup`，多选组用多选 `Chip`
- 必选组未选时"加入购物车"按钮置灰
- 底部实时显示"已选：大份/微辣"与计算后单价
- 默认值：`is_default=1` 的选项预选中

**边界与异常**

- `shopId <= 0`：直接 `finish()`（现有实现已这么做，保留）
- 店铺休息中：底部结算按钮置灰，文案"店铺休息中"
- 详情加载失败：整页错误态 + 重试，不显示空购物车
- **返回时不清空购物车**：退出店铺再进来要能看到之前选的（购物车按 shopId 持久化在内存 + DataStore）
- 进程被杀：购物车从 DataStore 恢复，恢复时校验 `dishId` 与 `price` 是否仍存在（价格变了也不改历史，下单时以服务端为准）

**前端实现**

- `ShopDetailViewModel`：`shop / groups / cart / favorited / submitting / error`
- `CartStore`（新建，放 `core:common` 或 `app/data`）：`StateFlow<Map<Long, CartShop>>`，写 DataStore 用 `debounce(500ms)` 防止频繁落盘
- `DishGroupAdapter`：`getItemViewType` 按 `TYPE_HEADER/TYPE_DISH` 分支

### D2 结算确认 CheckoutActivity

**入参**：`shopId: Long` + 购物车快照（**通过接口传选项，不通过 Intent 传列表**）

购物车内容改用 `POST /app/order/preview` 请求体提交，避免 Intent 传大对象和 `TransactionTooLargeException`。

**UI 结构**

```
[←] 确认订单
[收货地址]  张三 138****8000            >
            郑州市金水区文化路 1 号
[配送时间]  尽快送达（约 25 分钟）        >
[商品清单]
  红烧牛肉面 大份/微辣        ×1   ¥26.00
  卤蛋                        ×2    ¥6.00
[订单备注]  请多给一双筷子                >
[金额]  商品小计 ¥32.00
        配送费   ¥3.00
        合计     ¥35.00
[实付 ¥35.00]                    [提交订单]
```

**数据来源**：`POST /app/order/preview`（进入时）、`POST /app/order/create`（提交）

**preview 返回什么**（这是关键设计：**所有金额与校验都在服务端算，客户端只展示**）

```json
{
  "shopId": 1, "shopName": "老王牛肉面",
  "items": [ { "dishId": 1, "dishName": "红烧牛肉面", "specText": "大份/微辣", "price": 26.00, "quantity": 1, "subtotal": 26.00 } ],
  "totalAmount": 32.00,
  "deliveryFee": 3.00,
  "payAmount": 35.00,
  "minAmount": 15.00,
  "shortOfMin": 0.00,
  "defaultAddress": { "addressId": 3, "receiver": "张三", "phone": "13800138000", "fullAddress": "郑州市金水区文化路 1 号", "isDefault": true },
  "expectOptions": [
    { "value": "asap", "label": "尽快送达（约 25 分钟）", "expectTime": "2026-11-17 09:10:00" },
    { "value": "1830", "label": "18:30", "expectTime": "2026-11-17 18:30:00" }
  ]
}
```

**交互与状态机**

| 事件 | 动作 |
| --- | --- |
| 进入 | 请求 preview；成功后才允许提交；失败直接错误态（说明购物车已失效） |
| 点地址区 | 进 L3 选择模式；回传 `addressId` 后**重新 preview**（服务端要重算配送费，不能本地改） |
| 选配送时间 | 只改本地选中项，不改金额 |
| 点提交 | 按钮立即置 `submitting`（防连点）→ 生成 `clientToken`（UUID，Activity 内保持同一个，失败重试复用）→ create |
| create 成功 | `startActivity(D3)` + `finish()`（D2 不再回栈） |
| create 失败（起送价不足 / 菜品下架） | 弹窗展示 `msg`，按钮"返回修改" → `finish()` 回 D1 |

**边界**

- 无收货地址：地址区显示"添加收货地址"，提交按钮置灰并提示
- 购物车为空：preview 返回业务错误（`code=500, msg=购物车为空`），页面直接关闭
- 菜品在预览后被下架：create 返回明确 msg"「卤蛋」已下架"，客户端保留在结算页让用户返回改
- **金额一致性**：客户端**不使用** preview 的金额计算 create 的入参，create 只传 `items/addressId/expectTime/remark/clientToken`，金额由服务端重算。否则客户端改价就能白吃。

### D3 支付 PayActivity

**入参**：`orderId: Long`、`payAmount: Double`（仅展示用，以服务端为准）

**UI 结构**

```
[←] 收银台
        ¥ 35.00
        剩余支付时间 14:32
[支付方式]  ● 模拟支付（演示用）
            ○ 微信支付（未接入）
[确认支付 ¥35.00]
```

**数据来源**：`POST /app/order/{orderId}/pay`

**状态机**

```
进入 → 展示金额与倒计时
  ├ 点支付 → 请求中（按钮 loading）
  │    ├ 成功 → 弹成功页内浮层（"支付成功" + 查看订单 / 回首页）
  │    ├ 超时/已取消（msg 含"订单状态已变更"）→ 提示 + 跳到 D4 看真实状态
  │    └ 网络失败 → toast + 恢复按钮（同一个订单可重试，服务端 CAS 保证不重复扣）
  └ 倒计时归零 → 禁用按钮，提示"订单已超时"，跳 D4
```

**边界**

- 支付接口**幂等**：服务端 `UPDATE ... WHERE order_id=? AND status='0'`，影响行数 0 直接返回业务失败，不会重复支付
- 退出页面不清订单：待支付订单仍在列表里，用户随时能回来付
- 倒计时只在本地展示，**不作为取消依据**（本地时间可被改）

**成功后的栈处理**

```kotlin
// T2 已是栈底之一，用 CLEAR_TOP 回到它，避免连按返回又回到收银台
startActivity(Intent(this, OrderListActivity::class.java).apply {
    flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
    putExtra(OrderListActivity.EXTRA_INITIAL_TAB, OrderListActivity.TAB_IN_PROGRESS)
})
```

### D4 订单详情 OrderDetailActivity

**入参**：`orderId: Long`

**UI 结构**

```
[←] 订单详情
┌──────────────────────────────┐
│  配送中                       │
│  预计 09:40 送达               │
└──────────────────────────────┘
[时间轴] 已下单 09:00 → 已支付 09:01 → 商家已接单 09:03 → ● 配送中
[收货信息] 张三 138****8000 / 郑州市金水区文化路 1 号
[商品] 红烧牛肉面 大份/微辣 ×1 ¥26.00 ...
[金额] 小计 ¥32.00 配送费 ¥3.00 实付 ¥35.00
[订单信息] 订单号 2026111709001234  [复制]
          下单时间 2026-11-17 09:00:12
          备注 请多给一双筷子
[底部操作] 取消订单        联系商家
```

**数据来源**：`GET /app/order/{orderId}`（返回 order + items + 状态时间轴数据）

响应结构：

```json
{
  "order": { },
  "items": [ { "dishName": "红烧牛肉面", "specText": "大份/微辣", "quantity": 1, "subtotal": 26.00 } ],
  "reviewed": false,
  "timeline": [
    { "status": "0", "label": "已下单", "time": "2026-11-17 09:00:12" },
    { "status": "1", "label": "已支付", "time": "2026-11-17 09:01:03" }
  ]
}
```

时间轴由服务端拼：只返回**已发生**的节点，客户端按顺序渲染，最后一个是"当前状态"用实心圆点。

**底部按钮矩阵**（由 `status` 决定，客户端不自己推断业务）

| status | 按钮 |
| --- | --- |
| 0 待支付 | 取消订单（次要）、去支付（主要） |
| 1 已支付 | 取消订单（次要，提示"商家接单后退款请联系客服"） |
| 2 配送中 | 确认收货（主要） |
| 3 已完成 | 再来一单（`reviewed=false` 时并排"去评价"） |
| 4 已取消 | 再来一单 |

**交互与状态机**

| 事件 | 动作 |
| --- | --- |
| 进入 | 拉详情；`status in (0,1,2)` 时启动 15s 轮询 |
| `onPause` | 停轮询；`onResume` 恢复并立即拉一次 |
| 取消订单 | 弹原因选择（不想要了 / 点错了 / 地址填错 / 其他）→ `POST /app/order/{id}/cancel` → 重拉详情 |
| 确认收货 | 二次确认 → `POST /app/order/{id}/confirm` → 重拉详情 |
| 复制订单号 | `ClipboardManager` + toast "已复制" |
| 再来一单 | 拉明细 → 按 `option_ids` 还原购物车 → 跳 D1（由 D1 校验店铺是否还营业） |

**边界**

- 订单不属于当前用户：服务端返回 500"订单不存在"，客户端直接 `finish()` + toast（不暴露越权细节）
- 轮询只在页面前台时进行，禁止后台轮询（耗电 + 无意义）
- 评价完成后从 D6 返回：`onResume` 重拉，`reviewed` 变 true，按钮消失

### D5 地址编辑 AddressEditActivity

**入参**：`addressId: Long`（0 表示新增），回传 `EXTRA_RESULT_ADDRESS_ID`

**UI 结构**

```
[←] 新增收货地址 / 编辑收货地址
收货人       [____________]
手机号       [____________]
所在地区     [省] [市] [区]        ← 三段文本输入 + 常用城市快捷
详细地址     [____________]
地址标签     [家] [公司] [学校]   ← 单选 Chip，可不选
[设为默认地址]  (Switch)
[保存]
```

**数据来源**：`GET /app/address/{id}`、`POST /app/address`、`PUT /app/address`

**校验规则**（前后端**同一套**，前端先拦，后端必须再校验一遍）

| 字段 | 规则 | 提示 |
| --- | --- | --- |
| receiver | 2–20 字，非空白 | 请填写收货人（2-20 字） |
| phone | `^1[3-9]\d{9}$` | 请填写正确的手机号 |
| province/city/district | 各 2–32 字（暂不接地址库，纯文本） | 请填写所在地区 |
| detail | 5–128 字 | 详细地址至少 5 个字 |
| tag | 长度 ≤ 8，可为空 | — |

**交互与状态机**

| 事件 | 动作 |
| --- | --- |
| 编辑模式进入 | 拉详情回填；400 表示地址不存在 → finish |
| 点保存 | 前端校验 → 按钮 loading（防连点）→ 请求 → 成功 `setResult(RESULT_OK, id)` + finish |
| 失败 | toast `msg`，按钮恢复，**不清空已填内容** |
| 用户改过内容后按返回 | 弹"放弃本次修改？"（用 `hasEdited` 标记，仅在有改动时弹） |

**边界**

- 手机上滑遮挡输入框：`windowSoftInputMode="adjustResize"` + 表单外层 `NestedScrollView`
- 地址数量已达 20：后端拒绝，前端 toast 提示去删旧的
- 默认地址开关打开：后端在同一事务里把该用户其它地址的 `is_default` 归零

### D6 评价 ReviewActivity

**入参**：`orderId: Long`、`shopName: String`（标题展示）

**UI 结构**

```
[←] 评价
老王牛肉面
[★★★★★]  总评（必填）
口味 [★★★★★]  包装 [★★★★★]  配送 [★★★★★]
标签 [分量足][味道好][包装完整][配送快][性价比高]
[________________________________]
[ ] 匿名评价
[提交评价]
```

**数据来源**：`POST /app/order/{orderId}/review`

**交互与状态机**

| 事件 | 动作 |
| --- | --- |
| 进入 | 总评默认 5 星（降低操作成本）；标签、文字、分项都可空 |
| 点提交 | 总评必填（默认已满足）→ 提交 → 成功 toast + `setResult(RESULT_OK)` + finish |
| 重复提交 | 服务端 `uk_review_order` 冲突返回"该订单已评价"，客户端提示后 finish |

**边界**

- 仅 `status=3`（已完成）且 `review_status=0` 可评价，其他状态服务端拒绝
- 文字上限 200 字，输入框 `maxLength=200` + 底部计数
- 匿名只影响展示，`user_id` 照常落库（后台可查）

---

## 6. 页面 → 接口 → 表 映射

| 页面 | 主要接口 | 读表 | 写表 |
| --- | --- | --- | --- |
| T1 首页 | `GET /app/home/index` | shop_category、shop、hot_keyword | — |
| T3 我的 | `GET /getInfo` | sys_user | — |
| L1 店铺列表 | `GET /app/shop/page` | shop | — |
| L2 搜索 | `GET /app/search/hot`、`GET /app/search` | hot_keyword、shop、dish | — |
| L3 地址列表 | `GET /app/address/list`、`DELETE`、`POST /default` | address | address |
| L4 订单列表 | `GET /app/order/page` | order、order_item（件数已冗余） | order（惰性取消时） |
| L5 收藏 | `GET /app/favorite/list`、`DELETE` | favorite、shop | favorite |
| D1 店铺详情 | `GET /app/shop/{id}` | shop、dish、dish_spec、dish_spec_option、favorite | — |
| D2 结算 | `POST /app/order/preview`、`POST /app/order/create` | shop、dish、spec_option、address | order、order_item、order_status_log、dish |
| D3 支付 | `POST /app/order/{id}/pay` | order | order、order_status_log |
| D4 订单详情 | `GET /app/order/{id}`、`POST cancel`、`POST confirm` | order、order_item、review | order、order_status_log、dish |
| D5 地址编辑 | `GET/PUT /app/address`、`POST /app/address` | address | address |
| D6 评价 | `POST /app/order/{id}/review` | order | review、shop、order |

## 7. 实现顺序（建议）

按依赖排，每步都能独立验证：

1. **地址簿**（L3 + D5 + 接口）：最独立，无其它模块依赖，先把 CRUD 打通 —— **已完成**
2. **店铺列表改造**（L1）：分页 + 排序 + 分类，验证分页协议 —— **已完成**（搜索入口待 L2）
3. **店铺详情改造**（D1）：分组 + 规格 + 本地购物车，验证左右联动 —— **已完成**（结算入口待 D2）
4. **结算**（D2 + preview/create）：金额全在服务端，验证事务与幂等 —— **已完成**（下单后暂跳订单列表，等 D3 收银台）
5. **支付 + 订单详情**（D3 + D4）：状态机与轮询 —— **已完成**（演示期支付后 1 分钟自动进配送中）
6. **订单列表改造**（L4）：多值 status + Tab 缓存 —— **已完成**
7. **搜索 / 收藏 / 首页 / 我的**（L2、L5、T1、T3）：纯查询，最后做 —— **已完成**
8. **评价**（D6）：依赖已完成订单，放最后

每步完成后跑一次真机验证：进页面 → 断网重试 → 返回再进 → 杀进程重进。

## 8. Android 端代码落位

### 8.1 不拆 feature 模块（P2 阶段）

`architecture.md` 里写了 `feature:*` 之间不能互相依赖。P2 会直接撞上这条规则：
结算页（订单域）必须读店铺域的购物车，订单详情"再来一单"又要写回购物车。
硬拆成 `feature:shop` / `feature:order` 就得把 `CartStore` 下沉到 `core`，
而购物车依赖 `Dish` / `SpecOption` 这些店铺 DTO，下沉就要连带搬 DTO，牵一发动全身。

所以 P2 的选择：**UI 全部留在 `app` 模块内分包**，用包结构表达领域边界：

```
app/src/main/java/com/szc/learning/
├── AppContainer.kt                  新增 cartStore / takeoutRepository 扩展
├── data/CartStore.kt                购物车（内存 + DataStore 持久化）
├── ui/
│   ├── home/                        T1
│   ├── mine/                        T3
│   ├── shop/                        L1 店铺列表、D1 店铺详情、规格弹层
│   ├── search/                      L2
│   ├── address/                     L3、D5
│   ├── order/                       T2、L4、D2 结算、D3 支付、D4 详情、D6 评价
│   └── favorite/                    L5
```

包之间**允许单向引用**：`order` 可以引用 `shop` 的 DTO，反之不行。
这条规则用代码评审守，不靠 Gradle。

等 `CartStore` 的 DTO 全部来自 `core:network` 且不再被 UI 引用时，再拆 feature 模块，那时是水到渠成。

### 8.2 网络层扩展

`TakeoutApi` 现在是单接口 6 个方法，P2 到 22 个，拆成四个：

```
core/network/takeout/
├── ShopApi.kt          /app/home/**  /app/shop/**  /app/search/**  /app/favorite/**
├── OrderApi.kt         /app/order/**
├── AddressApi.kt       /app/address/**
├── TakeoutDto.kt       按域拆成 ShopDto / OrderDto / AddressDto 三个文件
└── TakeoutRepository.kt  保持单一仓库，内部组合四个 Api
```

`TakeoutRepository` **不加方法调用次数上限**，但每个方法必须返回 `AppResult`，
且不在这里做业务判断（比如"起送价不足"由服务端判定，仓库只负责翻译错误）。

### 8.3 购物车 `CartStore`

```kotlin
class CartStore(private val dataStore: DataStore<Preferences>, scope: CoroutineScope) {

    // shopId -> 该店铺的购物车
    private val _carts = MutableStateFlow<Map<Long, ShopCart>>(emptyMap())
    val carts: StateFlow<Map<Long, ShopCart>> = _carts.asStateFlow()

    fun add(shopId: Long, dish: Dish, options: List<SpecOption>)   // key = dishId + optionIds.sorted()
    fun decrease(shopId: Long, lineKey: String)
    fun clear(shopId: Long)
    fun clearOthers(keepShopId: Long)
    fun snapshot(shopId: Long): List<CartLine>                     // 结算时转成 preview 的 items
}
```

落盘策略：`_carts` 变化后 `debounce(500ms)` 再写 DataStore。
**不要每次加减都写盘**，一次连点十下会写十个事务。

进程重启后恢复：读取时若发现 DataStore 里的 `shopId` 对应的店铺已被删，整店丢弃，
不要弹错误 —— 用户不关心过期购物车。

### 8.4 公共组件

| 组件 | 位置 | 用途 |
| --- | --- | --- |
| `MoneyFormat` | `core:common` | `¥12.00` 统一格式，避免各页面 `String.format("%.2f")` 各写一遍 |
| `StatusText` | 不再需要 | 服务端下发 `statusText`，客户端删掉 `when(status)` 映射 |
| `EmptyView` | `app/ui/widget` | 空态/错误态共用的自定义 View，带 `show(text, actionText, onClick)` |
| `LoadingFooter` | `app/ui/widget` | 列表底部"加载中 / 加载失败点击重试 / 没有更多了"三态 |

`item_shop.xml` 要加月售、营业时段、"休息中"角标；`item_dish.xml` 要加规格摘要行
（`已选：大份/微辣`）。现有布局改造，不新建重复布局。

### 8.5 文案

所有新页面文案走 `strings.xml`。P2 新增约 60 条，按页面前缀命名：
`shop_list_empty`、`checkout_submit`、`order_cancel_reason_title`。
带占位符的用 `%1$s` / `%1$.2f`，不要在 Kotlin 里拼字符串再传给 `setText`。

