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
