# MEMORY.md — 学习助手项目长期约定

## 仓库结构（monorepo，三端各占一个顶层目录）
```
android/   移动端    Gradle 根，Android Studio 必须打开这个目录，不是仓库根
server/    服务端    RuoYi-Vue/ 源码（独立 clone，gitignore）+ docker-compose + README
admin/     管理端    RuoYi-Vue3/ 源码（独立 clone，gitignore）+ README
sql/       建表脚本与后台菜单
docs/      接口契约 api.md、架构 architecture.md
```
- 若依源码都是**独立 git 仓**，各自有 .git，被上级 .gitignore 排除，不进主仓
- 包名统一 `com.szc.learning`，模块前缀 `core.*` / `feature.*`

## 技术选型（已定型，不要再讨论）
- Kotlin + XML View（不用 Compose）、MVVM、StateFlow、DataStore
- **不引 Hilt**，用 `AppContainer` 手写依赖容器
- Retrofit 2.11 + OkHttp 4.12 + Gson（不用 Moshi / kotlinx.serialization）
- 后端 **官方 RuoYi-Vue**（不是 yudao）。两者响应码、登录路径、token 位置都不同
- AGP 9 内置 Kotlin 编译，`libs.versions.toml` 里**刻意没有** kotlin 插件

## 与后端对齐的三条硬规则
1. 成功判定 `code == 200`（不是 0）
2. header `Authorization: Bearer <token>`
3. 登录过期是 **HTTP 200 + body code=401**，必须解析响应体

## 代码约定
- 网络层不抛异常，统一返回 `AppResult`（Failure 带业务码）
- Activity 不写业务逻辑、不直接调 Retrofit；ViewModel 不持 Context
- `feature` 之间禁止互相依赖，共享代码下沉 `core`
- 文案一律走 `strings.xml`，不硬编码（lint 会报 HardcodedText）

## 贪吃蛇 demo
- 已从构建摘除：`settings.gradle.kts` 的 `include(":feature:demo")` 是注释状态
- 恢复方式：取消该行 + 取消 `app/build.gradle.kts` 里对应依赖注释
- `SnakeRules` 是重写版（原文件被误删），行为以 `SnakeRulesTest` 的 8 条用例为准

## 操作纪律（本机特有）
- `adb` server 活不过一次 Bash 调用，安装/截图必须写在**同一个脚本**里跑完
- `MSYS_NO_PATHCONV=1` 时传给 `adb.exe` 的本地路径要用 `C:/` 形式
- 模拟器默认中文输入法，`adb shell input text` 前先 `ime set` 到 AOSP 键盘
- **动目录前先 `git status`**：untracked 文件不会被 git 保护，`rm -rf` 直接没

## 外卖 P2 架构红线（2026-11-17 定稿，改前先读 docs 理由）

- 层级判定：一级=底部 Tab，二级=列表/聚合，三级=详情/表单/一次完整业务动作
- **金额只在服务端算**，create 请求体不含任何金额字段
- **状态变更用 CAS**：`UPDATE ... WHERE status=原状态`，影响行数 0 → 报「状态已变更」
- 下单幂等靠 `clientToken` + Redis SETNX 5 分钟，异常时显式删占位
- 待支付超时用**惰性取消**（查详情/列表时判断），不写定时任务
- 订单时间轴读 `takeout_order` 的时间戳字段，**不读** status_log（审计流水会重复）
- 店铺评分**重算** `AVG(review.rating)`，不累加
- 购物车**不落库**，只存 DataStore；服务端只在 preview/create 时重算
- P2 **不拆 feature 模块**，UI 全在 `app/ui/*` 分包（结算域要读店铺域购物车）
- `takeout_shop.category` 文本保留，新增 `category_id`：筛选走 id，展示走文本
