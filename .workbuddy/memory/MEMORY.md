# MEMORY.md — Aurora 音乐播放器项目长期约定

> 2026-11-17 起，项目由「外卖点餐」整体转为 **Aurora 音乐播放器**（期末答辩项目）。
> 外卖时代的 app 端代码已全部移除，只在 `master` 的 118a4be 之前的历史里。

## 当前形态
- App：本地曲库（MediaStore）+ 云端曲库（若依 `/app/music/tracks`）+ 在线音源（网易云/酷狗）三源
- 页面：**四个 tab：播放 / 推荐 / 本地 / 我的**，默认落推荐页。首页已于 2026-11-18 下线
- 推荐：客户端算口味歌手 → 后端 `/app/music/recommend` 聚合搜索 + 平台推荐流；结果本地缓存兜底
- 收藏：本机（存完整曲目）→ 后端 `biz_music_user_like`（按 deviceId）→ 平台（仅网易云）
- 设计：iOS 液态玻璃 + 极光底，浅色优先，深色可用
- 播放内核：media3 ExoPlayer + MediaSessionService（后台播放、通知栏、锁屏控制）
- 分支：`feat/music-glass` 是当前开发线；master 已收拢全部旧分支

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

## 本机联调（已定型）
- 默认账号 `admin` / `admin123`（若依超管）
- **`10.0.2.2:8080` 在本机连不通**（App 侧 15s connect timeout，宿主机 curl 正常）。
  稳定走法：`-PapiBaseUrl=http://127.0.0.1:8080/` 打包 + `adb reverse tcp:8080 tcp:8080`
- `assembleDebug` 不装包，改完必须 `adb install -r`
- `ruoyi.profile` = `C:/ruoyi/uploadPath`（**不要放 D:**，那是可移动盘，抽走就 500）
- Redis 便携版已关 RDB 快照（`save ""`），否则 MISCONF 会把写命令全禁掉、若依起不来
- 打包前先杀 java 进程，否则 `ruoyi-admin.jar` 被占用、`mvn package` rename 失败
- 云端曲目 `url` 是站内相对路径，客户端必须补 baseUrl 再交给 ExoPlayer

## 操作纪律（本机特有）
- `adb` server 活不过一次 Bash 调用，安装/截图必须写在**同一个脚本**里跑完
- `MSYS_NO_PATHCONV=1` 时传给 `adb.exe` 的本地路径要用 `C:/` 形式
- 模拟器默认中文输入法，`adb shell input text` 前先 `ime set` 到 AOSP 键盘
- **动目录前先 `git status`**：untracked 文件不会被 git 保护，`rm -rf` 直接没
- **adb 调试截图不要放桌面**：一律输出到 `%TEMP%` 或项目内 `.verify-shots/`，验证完即清。
  曾一次在桌面留下 29 张 `_p2_*.png`（6 MB），事后要专门清理。
- `MainActivity` 若 `exported=false`，`adb shell am start` 会被拒；从 launcher intent
  或 exported 的入口页进，session 有效时会自动跳主页
