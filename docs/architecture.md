# 架构说明

> **设计基线：顾客端 4 个 tab + 骑手端 3 个 tab，两端拆成两个 APK。**
> 顾客端已落地底部导航骨架（`MainActivity` + 首页 / 购物车 / 订单 / 我的 4 个 tab，
> 购物车为进程内 `CartStore`）；确认订单、地址管理等二级页与 `rider` 模块尚未实现。
> 本文档第 3、5、6 节是目标设计，其余是现状约束。

## 1. 三层结构

```
┌──────────────────┐        ┌──────────────────┐
│  顾客端 APK       │        │  骑手端 APK       │
│  android/app      │        │  android/rider    │
└────────┬─────────┘        └────────┬─────────┘
         │                           │
         └───────────┬───────────────┘
                     │ HTTP / JSON（Bearer token）
         ┌───────────▼───────────────┐
         │  RuoYi-Vue 后端            │  Spring Boot 4.1 + MyBatis-Plus
         │  server/（独立部署）        │  RBAC 权限、token 签发、业务接口
         └───────────┬───────────────┘
                     │ JDBC
         ┌───────────▼───────────────┐
         │  MySQL 8+ + Redis 5+      │  MySQL 存业务数据
         │  本机安装 / 便携版          │  Redis 存 token
         └───────────────────────────┘
```

两端共用同一套后端与数据库。Android 端不直连数据库：直连 MySQL 等于把数据库账号
打包进 APK，任何人反编译就能拿到全库权限。

## 2. Android 模块划分

目标结构：

```
android/
├── app/                  顾客端 APK（applicationId com.szc.learning）
│   ├── LearningApp.kt / AppContainer.kt
│   ├── MainActivity.kt   tab 宿主
│   └── ui/
│       ├── home/ cart/ order/ mine/   4 个 tab
│       ├── shop/ address/ login/      二级页面
│       └── common/                    支付弹层、空态等共用 UI
├── rider/                骑手端 APK（applicationId com.szc.learning.rider，新增）
│   ├── RiderApp.kt / RiderContainer.kt
│   ├── RiderMainActivity.kt
│   └── ui/
│       ├── pool/ task/ profile/       3 个 tab
│       └── login/                     骑手登录（多一步角色校验）
├── core/
│   ├── common/           AppResult、UiState、OrderStatus、日志、View 扩展
│   └── network/          ApiClient、SessionStore、auth、takeout、rider
└── feature/
    └── demo/             贪吃蛇（已从构建摘除，代码保留）
```

依赖方向（单向，不允许反向或循环）：

```
app ───┬──> core:network ──> core:common
       └──> core:common

rider ─┬──> core:network ──> core:common
       └──> core:common
```

`app` 与 `rider` **互不依赖**；`feature:*` 之间也**不能互相依赖**。要共享的东西下沉到
`core:*`。这条规则防止两个客户端和功能模块之间长成一张网。

### 为什么是两个 APK

- 真实外卖产品就是分开的（美团 / 美团骑手）：角色互斥，顾客端不需要打骑手代码。
- 独立 `applicationId`、图标、名称，安装后互不覆盖，联调时一台机器能同时装两端。
- 共享 `core:network` 与 `core:common`，账号、错误码、订单状态码仍然只有一份定义。

### 暂不新增 core:ui

两端重复的只有底部导航这一处，先各写一份。出现第三处重复再抽模块，
避免为了“架构好看”增加构建复杂度。

## 3. 页面架构

### 3.1 顾客端：4 个 tab

宿主是 `MainActivity`：`BottomNavigationView` + `FragmentContainerView`。
Fragment 用 `show/hide` 切换以保留滚动位置和输入状态，不用 `replace`。

| Tab | 页面内容 | 点击效果 | 接口 |
| --- | --- | --- | --- |
| 首页 | 顶部地址、搜索、消息入口；分类金刚区；附近店铺列表（评分 / 起送 / 配送费 / 时长） | 地址 → 地址选择弹层，选完刷新列表并回顶；搜索 → `SearchActivity`；分类 → `ShopListActivity(category)`；店铺卡片 → `ShopDetailActivity`；下拉刷新、触底分页 | `GET app/shop/list?category=&keyword=&page=` |
| 购物车 | 同店购物车：菜品行 + 数量步进器 + 合计 + 去结算；tab 上带件数角标 | `+ / -` 实时改数量，减到 0 自动移除；未达起送价按钮置灰并提示差额；去结算 → 回到店铺页结算；空态“去逛逛”切回首页 | 本地 `CartStore`（当前进程内内存，后续可换 DataStore 按 userId 持久化），下单时才调 `app/order/create` |
| 订单 | 状态分栏：全部 / 待支付 / 待接单 / 配送中 / 已完成；卡片带操作按钮 | 卡片 → `OrderDetailActivity`；去支付 → 支付 BottomSheet，成功刷新；取消 → 二次确认；再来一单 → 明细回填购物车并跳店铺 | `GET app/order/list?status=`、`detail`、`pay`、`cancel` |
| 我的 | 用户卡、我的地址、设置 / 关于、退出登录 | 头像 → 资料页；地址 → `AddressListActivity`；退出 → 确认后清 token + 购物车 → `LoginActivity`（清栈）；401 全局跳登录 | `/getInfo`、`/logout`、`app/address` |

顾客端“配送中”一栏实际对应 `status IN (2,3)`：骑手已接单或已取餐，对顾客都是“配送中”。

### 3.2 骑手端：3 个 tab

宿主是 `RiderMainActivity`，同样是 `BottomNavigationView` + Fragment。

| Tab | 页面内容 | 点击效果 | 接口 |
| --- | --- | --- | --- |
| 接单 | 在线 / 休息开关；今日单量与收入；待接订单大厅卡片（取餐店、送达地址、距离、配送费） | 开关 → 调接口，失败回滚；抢单 → 二次确认，成功移除卡片并切到“我的订单”，失败 toast“已被抢走”并刷新；页面 resumed 时 8 秒轮询，新单提示音 + 震动；离线时显示“已休息，不接收新单” | `GET app/rider/order/pool`、`POST grab`、`POST online` |
| 我的订单 | 子栏：进行中 / 已完成；卡片按状态给唯一主按钮 | 待取餐 → 确认取餐（状态 3）；配送中 → 确认送达（状态 4）；电话用 `ACTION_DIAL`，导航用 `geo:`；完成后刷新并累计今日收入 | `GET app/rider/order/list?status=`、`pickup`、`deliver` |
| 我的 | 骑手资料 / 认证状态、今日与累计统计、接单设置、退出登录 | 退出确认；设置项本期静态展示 | `app/rider/profile`、`stats` |

骑手端没有购物车和店铺浏览，3 个 tab 足够；消息入口放“我的”，不单独占 tab。

### 3.3 二级页面（不进 tab）

顾客端：

- `ShopDetailActivity`：店铺头 + 分类侧栏 + 菜品列表 + 底部购物车条（改造现有页面）。
- `ConfirmOrderActivity`：地址、送达时间、备注、明细、配送费、提交（把现有结算弹窗抽成页面）。
- `OrderDetailActivity`：状态时间轴 + 骑手信息 + 明细 + 金额。
- `AddressListActivity`：地址增删改、设为默认。
- `SearchActivity`：搜索历史 + 结果列表。

骑手端：

- `RiderOrderDetailActivity`：取送地址、距离、顾客备注、电话与导航入口。

支付、地址选择、二次确认统一用 `BottomSheetDialogFragment` 或 `AlertDialog`。

### 3.4 导航约定

- tab 内不做回退栈：点 tab 直达根页面；非首页 tab 按返回键先回首页，首页再按两次退出。
- 详情页继续用 Activity，返回时由来源页在 `onResume` 刷新，不引 EventBus。
- 登录成功进 `MainActivity` / `RiderMainActivity`，并用 clear-task 清掉登录页。
- 任何接口返回 401 时统一清 Session 并跳登录页。

## 4. 订单状态机（两端共用）

| 码 | 状态 | 谁推进 |
| --- | --- | --- |
| 0 | 待支付 | 顾客下单 |
| 1 | 待接单（进骑手大厅） | 顾客支付成功 |
| 2 | 待取餐 | 骑手抢单成功，绑定 `rider_id` |
| 3 | 配送中 | 骑手确认取餐 |
| 4 | 已完成 | 骑手确认送达 |
| 5 | 已取消 | 顾客取消 / 超时 |

状态码统一放在 `core:common` 的 `OrderStatus`，两端共用，禁止在 UI 里散落魔法数字。

现有库是 `0..4`（`2=配送中、3=已完成、4=已取消`），迁移时一次性改成
`2→3、3→4、4→5`，并同步改所有状态判断与文案。

## 5. 数据与接口改造

表：

- `takeout_order` 增加 `rider_id / accept_time / pickup_time / deliver_time`。
- 新增 `takeout_rider`：骑手档案，关联 `sys_user`，含在线状态。
- 新增 `takeout_address`：收货地址。
- 购物车不建服务端表，用本地 DataStore，避免为学习项目增加整套购物车接口。

接口：

| 端 | 接口 | 说明 |
| --- | --- | --- |
| 顾客 | `POST app/order/{id}/cancel` | 仅待支付 / 待接单可取消 |
| 顾客 | `GET/POST/PUT/DELETE app/address` | 地址管理 |
| 骑手 | `POST app/rider/online` | 切换接单状态 |
| 骑手 | `GET app/rider/order/pool` | 待接单大厅 |
| 骑手 | `POST app/rider/order/{id}/grab` | 抢单 |
| 骑手 | `GET app/rider/order/list?status=` | 进行中 / 已完成 |
| 骑手 | `POST app/rider/order/{id}/pickup` | 确认取餐 |
| 骑手 | `POST app/rider/order/{id}/deliver` | 确认送达 |
| 骑手 | `GET app/rider/profile`、`stats` | 资料与统计 |

### 抢单并发

不做分布式锁，用条件更新：

```sql
UPDATE takeout_order
SET rider_id = ?, status = '2', accept_time = NOW()
WHERE order_id = ? AND status = '1' AND rider_id IS NULL
```

影响行数为 0 就是已被抢，直接返回业务失败，客户端 toast 并刷新大厅。

### 实时性

本期不做 WebSocket：顾客端订单页在 `onResume` 刷新；骑手端在
`repeatOnLifecycle(STARTED)` 内 8 秒轮询大厅。后续需要再换长连接。

## 6. 各层职责

| 层 | 位置 | 职责 | 禁止 |
| --- | --- | --- | --- |
| Activity / Fragment | `ui/*/` | 渲染、事件绑定、导航 | 写业务逻辑、直接调 Retrofit |
| ViewModel | `ui/*/` | 状态持有、协程调度 | 持有 Context / View 引用 |
| Repository | `core/network/` | 组合接口、异常翻译 | 处理 UI 状态 |
| ApiClient | `core/network/` | Retrofit / OkHttp 配置 | 写具体业务 |
| DTO | `core/network/` | 与后端契约一一对应 | 加业务方法 |

数据流：

```
用户点击
  → Fragment / Activity 调 ViewModel
    → ViewModel 启动协程调 Repository
      → Repository 调 Api
        → ApiException / IOException 被翻译成 AppResult
      ← AppResult 回到 ViewModel
    ← UiState 更新到 StateFlow
  ← 页面 collect 到新状态，刷新界面
```

## 7. 关键设计决策

### 7.1 手写依赖容器，不引 Hilt

`AppContainer` / `RiderContainer` 的属性一只手数得过来，依赖关系一眼看完。
Hilt 带来 KSP、注解处理器、编译期代码生成，学习项目里收益小于成本。
等模块超过 10 个、依赖出现条件分支时再换。

### 7.2 业务错误与网络错误分开建模

`AppResult.Failure` 带 `code`：

- `code == 200` 之外的正数是**后端业务码**（比如 401 token 过期）
- `-2` 是网络不可达
- `-1` 是客户端兜底

这样 UI 能精确区分“该跳登录”和“该提示重试”。
把两者都塞进一个 `Exception` 字符串里，是后面 UI 逻辑失控的起点。

### 7.3 token 存 DataStore，内存里再缓存一份

- 落盘用 DataStore（异步、无 SharedPreferences 的 apply 丢失风险）
- 内存用 `@Volatile var` 缓存，因为 OkHttp 拦截器是同步执行的，等不了 suspend

副作用要记住：**登录后必须先 `sessionStore.save()`，再调 `/getInfo`**，
否则拦截器读到的还是旧 token。`AuthRepository.login()` 里的顺序不能调换。
两个 APK 的 token 存在各自沙箱，互不影响。

### 7.4 明文 HTTP 只在 debug 放行

`main/res/xml/network_security_config.xml` 全局禁止明文，
`debug/res/xml/network_security_config.xml` 同名覆盖、允许明文。
release 包天然拒绝 `http://`，防止把测试地址和明文流量带到线上。

### 7.5 token 不进云备份

`backup_rules.xml` 与 `data_extraction_rules.xml` 都排除了 `datastore/auth.preferences_pb`。
否则用户换机时云备份会把有效 token 一起搬过去。

### 7.6 购物车只存在本地

`CartStore` 放在 `AppContainer`，用 `StateFlow` 暴露给 tab 角标和购物车页。
当前阶段只做进程内内存态，后续可换 DataStore 按 userId 持久化。约束：

- 一次只允许一家店的商品；加入第二家店时弹窗“清空并添加”。
- 金额按分取整，避免二进制误差影响起送价判断。
- 下单成功后清空对应店铺，不依赖服务端回写。

### 7.7 两个 App 的登录角色互斥

后端 `/getInfo` 返回 roles。顾客端发现 rider 角色时提示“请使用骑手端登录”；
骑手端要求 rider 角色。角色判断只做一次，放在登录成功后的落地逻辑里。

## 8. 加一个新功能的完整步骤

以“某个列表接口”为例，`Xxx` 替换成实际业务名：

1. **契约**：在 `docs/api.md` 补接口，确定字段名。
2. **DTO**：`core/network/XxxDto.kt`，字段加 `@SerializedName`。
3. **接口**：`core/network/XxxApi.kt`，例如 `@GET("app/xxx/list")`。
4. **仓库**：`core/network/XxxRepository.kt`，返回 `AppResult<List<Xxx>>`。
5. **接线**：`ApiClient` 加 `val xxxApi`，对应 `AppContainer` / `RiderContainer` 加仓库。
6. **页面**：顾客端放 `app/ui/`，骑手端放 `rider/ui/`；跨端共用逻辑下沉 `core:*`。
7. **验证**：单测规则层，仪器测试跑通列表 → 详情 → 返回。

## 9. 已知约束

- 若依没有 C 端用户体系，直接用 `sys_user` 会让 App 用户和后台运维账号混在一起。
  骑手账号通过 `takeout_rider` 关联 `sys_user` 并绑定 rider 角色。
  详见 `server/README.md` 第 6 节的方案 A / B。
- 当前 `compileSdk 37` / `minSdk 24`，AGP 9 使用内置 Kotlin 编译，
  所以 `libs.versions.toml` 里没有 `org.jetbrains.kotlin.android` 插件，这是正常的。
- 骑手端新 APK 需要独立的 `rider/build.gradle.kts`、`AndroidManifest.xml` 与启动图标，
  并在 `settings.gradle.kts` 里 `include(":rider")`。

## 10. 实施顺序

1. 后端：状态码迁移 + `takeout_rider` / `takeout_address` + 骑手接口。
2. `core:common` 加 `OrderStatus`；`core:network` 加 `rider` 包与取消 / 地址接口。
3. 顾客端：`MainActivity` + 4 个 Fragment；`HomeActivity` 的账号逻辑迁到“我的”；
   `OrderListActivity` 改为订单 Fragment；新增 `CartStore` 与确认订单页。
4. 骑手端：新建 `rider` 模块 + 3 个 Fragment + 独立登录页。
5. 联调主链路：下单 → 支付 → 抢单 → 取餐 → 送达 → 两端看到同一状态。
6. 补单测：状态机非法流转、抢单并发、购物车金额与起送价判断。
