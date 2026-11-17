# 架构说明

## 1. 三层结构

```
┌─────────────────────────┐
│  Android 客户端          │  Kotlin + XML View，MVVM
│  android/               │  只管展示与交互
└───────────┬─────────────┘
            │ HTTP / JSON（Bearer token）
┌───────────▼─────────────┐
│  RuoYi-Vue 后端          │  Spring Boot 3 + MyBatis-Plus
│  server/（独立部署）      │  RBAC 权限、token 签发、业务接口
└───────────┬─────────────┘
            │ JDBC
┌───────────▼─────────────┐
│  MySQL 8 + Redis 7      │  MySQL 存业务数据
│  Docker Compose         │  Redis 存 token
└─────────────────────────┘
```

Android 端不直连数据库。这是硬约束：直连 MySQL 等于把数据库账号打包进 APK，
任何人反编译就能拿到全库权限。

## 2. Android 模块划分

```
android/
├── app/                  壳工程：Application、依赖容器、登录页、首页
├── core/
│   ├── common/           纯基础设施：结果封装、UI 状态、日志、View 扩展
│   └── network/          Retrofit + OkHttp + 若依协议适配
└── feature/
    └── demo/             贪吃蛇（已从构建中摘除，代码保留）
```

依赖方向（单向，不允许反向或循环）：

```
app ──┬──> feature:demo
      ├──> core:network ──> core:common
      └──> core:common
```

`feature:*` 之间**不能互相依赖**。要共享的东西下沉到 `core:*`。
这条规则防止功能模块之间长成一张网。

### 为什么用多模块

单模块也能跑，但多模块换来三件事：

1. **编译隔离**：改 `core:common` 才会全量重建，改 UI 只重编 `app`
2. **依赖边界**：想让 `feature` 用网络库，必须显式声明依赖，顺手就能发现「这层不该干这事」
3. **可拆可删**：`feature:demo` 直接注释掉 `include` 就从构建里消失，不留死代码

## 3. 各层职责

| 层 | 位置 | 职责 | 禁止 |
| --- | --- | --- | --- |
| Activity | `ui/*/` | 渲染、事件绑定、导航 | 写业务逻辑、直接调 Retrofit |
| ViewModel | `ui/*/` | 状态持有、协程调度 | 持有 Context / View 引用 |
| Repository | `core/network/` | 组合接口、异常翻译 | 处理 UI 状态 |
| ApiClient | `core/network/` | Retrofit / OkHttp 配置 | 写具体业务 |
| DTO | `core/network/` | 与后端契约一一对应 | 加业务方法 |

数据流：

```
用户点击
  → Activity 调 ViewModel
    → ViewModel 启动协程调 Repository
      → Repository 调 Api
        → ApiException / IOException 被翻译成 AppResult
      ← AppResult 回到 ViewModel
    ← UiState 更新到 StateFlow
  ← Activity collect 到新状态，刷新界面
```

## 4. 关键设计决策

### 4.1 手写依赖容器，不引 Hilt

`AppContainer` 只有三个属性，依赖关系一眼看完。
Hilt 带来 KSP、注解处理器、编译期代码生成，学习项目里收益小于成本。
等模块超过 10 个、依赖出现条件分支时再换。

### 4.2 业务错误与网络错误分开建模

`AppResult.Failure` 带 `code`：

- `code == 200` 之外的正数是**后端业务码**（比如 401 token 过期）
- `-2` 是网络不可达
- `-1` 是客户端兜底

这样 UI 能精确区分「该跳登录」和「该提示重试」。
把两者都塞进一个 `Exception` 字符串里，是后面 UI 逻辑失控的起点。

### 4.3 token 存 DataStore，内存里再缓存一份

- 落盘用 DataStore（异步、无 SharedPreferences 的 apply 丢失风险）
- 内存用 `@Volatile var` 缓存，因为 OkHttp 拦截器是同步执行的，等不了 suspend

副作用要记住：**登录后必须先 `sessionStore.save()`，再调 `/getInfo`**，
否则拦截器读到的还是旧 token。`AuthRepository.login()` 里的顺序不能调换。

### 4.4 明文 HTTP 只在 debug 放行

`main/res/xml/network_security_config.xml` 全局禁止明文，
`debug/res/xml/network_security_config.xml` 同名覆盖、允许明文。
release 包天然拒绝 `http://`，防止把测试地址和明文流量带到线上。

### 4.5 token 不进云备份

`backup_rules.xml` 与 `data_extraction_rules.xml` 都排除了 `datastore/auth.preferences_pb`。
否则用户换机时云备份会把有效 token 一起搬过去。

## 5. 页面分层与状态约定

三级页面各管一段，别把职责往上堆：

| 层级 | 页面 | 拿什么数据 | 负责什么 |
| --- | --- | --- | --- |
| 一级 | `HomeActivity` | 只有登录态 | 入口导航，不请求业务数据 |
| 二级 | `ShopListActivity` | `/app/shop/list` 一次拉全量 | 搜索、分类筛选、排序、结果计数 |
| 三级 | `ShopDetailActivity` | `/app/shop/{id}` | 店铺信息、分组菜单、购物车、结算 |
| 分支 | `OrderListActivity` | `/app/order/list` | 订单列表与模拟支付 |

两条刻意的取舍：

1. **搜索与筛选在本地做**。店铺总量是个位数，每敲一个字打一次后端只会让输入框抖；
   真上规模再换服务端分页 + 条件查询，接口契约不用提前设计。
2. **购物车只活在 `ShopDetailViewModel` 的内存里**。退出详情即清空，
   避免「昨天选的菜今天还在」，也省掉一套本地持久化与后台同步。

页面状态统一三态，混在一起会让用户误判后端挂了：

- `loading`：进度条
- **整页错误**：占满页面 + 错误文案 + 重试按钮（`loadError`）
- **空态**：再分「一条数据都没有」和「筛不出来」（`filtering`），后者给「清除筛选」

一次性错误（下单失败等）单独放 `actionError`，toast 完即清，不占页面。

## 6. 加一个新功能的完整步骤

以「某个列表接口」为例，`Xxx` 替换成实际业务名：

1. **契约**：在 `docs/api.md` 补接口，确定字段名
2. **DTO**：`core/network/XxxDto.kt`，字段加 `@SerializedName`
3. **接口**：`core/network/XxxApi.kt`，例如 `@GET("app/xxx/list")`
4. **仓库**：`core/network/XxxRepository.kt`，返回 `AppResult<List<Xxx>>`
5. **接线**：`ApiClient` 加 `val xxxApi`，`AppContainer` 加 `xxxRepository`
6. **页面**：新建 `feature/xxx/` 模块或直接在 `app/ui/xxx/` 下写
7. **验证**：单测规则层，仪器测试跑通列表 → 详情 → 返回

## 7. 已知约束

- 若依没有 C 端用户体系，直接用 `sys_user` 会让 App 用户和后台运维账号混在一起。
  详见 `server/README.md` 第 7 节的方案 A / B。
- 当前 `compileSdk 37` / `minSdk 24`，AGP 9 使用内置 Kotlin 编译，
  所以 `libs.versions.toml` 里没有 `org.jetbrains.kotlin.android` 插件，这是正常的。
