# android-learning-app · Aurora 音乐

Android 客户端 + RuoYi-Vue 后端 + MySQL 的三层工程，当前业务只有一个：
**一个干净的音乐播放器**，没有短视频流，也没有视频素材依赖。

> 不做广告、不做会员/积分/签到、不做听书。
> 播放、推荐、本地、我的都是围绕「听歌」这一件事：第一个 tab 是整屏播放器，
> 上下滑切歌，并展示音频文件里的封面和内嵌歌词；推荐页从音源按口味取歌。

## 技术栈

| 层 | 技术 | 说明 |
| --- | --- | --- |
| 移动端 | Kotlin、AGP 9.3、compileSdk 37、minSdk 24 | XML View 体系，MVVM，无 Compose |
| 播放 | media3 ExoPlayer + MediaSessionService | 后台播放、通知栏/锁屏/耳机键 |
| 网络 | Retrofit 2.11 + OkHttp 4.12 + Gson | 统一信封解析、错误码映射 |
| 状态 | Kotlin Coroutines + StateFlow | 无 LiveData、无 Hilt |
| 图片 | Coil | 本地专辑图走自定义 Fetcher |
| 服务端 | RuoYi-Vue 3.9.2（Spring Boot 4.1 + MyBatis-Plus） | 独立部署，源码不入库 |
| 数据库 | MySQL 8.0+ | 字符集 utf8mb4 |
| 缓存 | Redis 5.0+ | 若依 token 存储，**必须启动** |

## 目录结构

```
.
├── android/                 【移动端】Gradle 根工程，Studio 打开这里
│   ├── app/                 壳 + 全部 UI：四个 tab、播放器、正在播放、搜索
│   ├── core/
│   │   ├── common/          MusicTrack 统一模型、结果封装、日志、View 扩展
│   │   └── network/         Retrofit、拦截器、音乐接口
│   └── feature/
│       └── demo/            贪吃蛇（已从构建摘除，代码保留）
├── server/                  【服务端】若依后端
│   ├── RuoYi-Vue/           源码（独立 clone，不入库）
│   ├── redis/               Windows 便携版 Redis（不入库）
│   ├── start-redis.sh       启动 Redis
│   ├── start-backend.sh     启动后端
│   └── docker-compose.yml   MySQL + Redis（备选，需 Docker）
├── admin/                   【后台管理端】RuoYi-Vue3
├── sql/                     业务表与初始化数据（音乐表在 05、音源 06、喜欢 07）
└── docs/                    接口契约、架构说明
```

## 四个 tab

| tab | 内容 |
| --- | --- |
| 播放 | 整屏竖向翻页播放器：大封面、内嵌歌词、进度/传输控制，上下滑切歌 |
| 推荐 | 每日推荐大卡、为你推荐（每行可点红心，实时同步）、来自音源的歌单、本机歌单 |
| 本地 | 歌曲/歌手/专辑/文件夹四个视图，搜索、排序、播放全部 |
| 我的 | 听歌统计、常用入口、我喜欢的音乐、最近播放、音源管理、音源同步 |

首页已经去掉：它原本只是「每日推荐 + 歌单 + 好歌」的堆叠，这些内容现在都在推荐页，
而且是真·在线推荐（按收藏与最近播放的口味，从网易云/酷狗取歌）。
默认落在推荐页，打开就有歌可听；进入时的入场动画沿用旧首页那套
「从下方飞入 + 同帧渐显、自上而下错峰」，并与启动页 logo 动画重叠而不是排队。

播放中的歌会在底部悬浮 mini player 上露出；点进正在播放是深色沉浸页，
封面虚化背景、队列、收藏、分享。搜索与收藏/最近/云端列表都是独立二级页。

### 推荐与收藏同步

- **口味**：客户端取收藏曲目与最近播放的歌手（收藏权重 ×2），传 3 个给后端；
  后端按歌手聚合搜索 + 补平台推荐流 + 去重 + 排除已喜欢，按天稳定洗牌。
- **本地保留**：推荐结果写进 `RecommendCacheStore`，断网时回放上一次并标注离线；
  收藏从「只存键」改为存完整曲目，在线曲目重装后也能还原。
- **点红心**：本机立即落盘 → `POST /app/music/like` 后端记录口味 →
  推给音源平台（网易云 `/api/radio/like`，需 Cookie）。平台失败只提示，不影响本机。
- **我的自动同步**：进「我的」页自动把后端喜欢并回本地、把平台歌单拉成卡片
  （5 分钟内不重复请求）；也有「同步音源」入口可手动强制。

### 在线音源

搜索页输入 ≥2 个字会先出「在线」结果（网易云 / 酷狗混排、带平台胶囊），
下面接「我的音乐」。点一首后由后端取播放地址，会员/版权不可播时自动换到
另一平台并提示换了谁；「我的 → 音源管理」控制平台开关、优先级和 Cookie。
Cookie 支持**半自动登录**：在 App 内打开平台网页登录，检测到登录态后自动保存，
不用再去浏览器 F12 复制。后端聚合的方式、接口字段和已知边界见
[`docs/music-sources.md`](docs/music-sources.md)。

### 封面与歌词检测

- **封面**：本地音频的封面在 ID3 的 `APIC` 帧里。`LocalMusicRepository` 把
  `artUri` 设成音频文件自己的 `content://` uri，由全局 Coil 的
  `AudioArtFetcher` 用 `loadThumbnail` 取出；取不到时退回
  `MediaMetadataRetriever.embeddedPicture`，再取不到才用 `CoverArt` 生成渐变图。
- **歌词**：`LyricsRepository` 按需读取 ID3v2 的 `USLT`（非同步）或
  `SYLT`（同步）帧，兼容 v2.2/v2.3/v2.4 和常见文本编码，也认
  `TXXX:LYRICS`。播放器页只解析当前曲目并预取前后页，结果按曲目缓存，
  不会在曲库加载时逐首打开文件。
- 云端曲目优先使用接口可选的 `lyrics` 字段；没有时显示「没有找到内嵌歌词」。
  播放器页最多显示 5 行，点歌词可查看全文。

## 快速开始

### 1. 中间件

本机 MySQL 跑在 3306，Redis 用便携版：

```bash
./server/start-redis.sh
```

首次建库导表：

```bash
MYSQL="/c/Program Files/MySQL/MySQL Server 26.10/bin/mysql.exe"
"$MYSQL" -uroot -p -e "CREATE DATABASE IF NOT EXISTS \`ry-vue\` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;"
"$MYSQL" -uroot -p ry-vue < server/RuoYi-Vue/sql/ry_20260417.sql
"$MYSQL" -uroot -p ry-vue < server/RuoYi-Vue/sql/quartz.sql
"$MYSQL" -uroot -p ry-vue < sql/05_music_schema.sql
"$MYSQL" --default-character-set=utf8mb4 -uroot -p ry-vue < sql/06_music_source.sql
```

云端曲库还要把 mp3 放到后端的 `profile/music/`，详见 [`docs/cloud-music.md`](docs/cloud-music.md)。

### 2. 起服务端

```bash
./server/start-backend.sh
```

**不要直接 `java -jar`**：本机环境会把端口顶到 8066（被其它服务占用），
脚本里显式带了 `--server.port=8080`。详见 [`server/README.md`](server/README.md)。

### 3. 跑客户端

用 Android Studio 打开 **`android/` 目录**（不是仓库根，Gradle 根在这里）。

```bash
cd android
./gradlew :app:installDebug
```

`installDebug` 会自动先跑 `adbReverse` 建好 `adb reverse tcp:8080 tcp:8080`，
再把包装进设备。**App 不需要登录**，启动直接进推荐页；没网时走本地曲库与推荐缓存。
装完如果推荐页是空的，先确认后端和 `adb reverse` 都在。

> 只编译不装包用 `assembleDebug`。模拟器跑的还是旧 APK 时，
> 以推荐页有没有出现在线歌曲（带平台胶囊）为准。

## 接口

- 曲库：`GET http://127.0.0.1:8080/app/music/tracks`，返回 10 首云端曲目；
  本地曲库走 MediaStore，与云端在数据层合并成统一的 `MusicTrack`。
- 在线音源：`/app/music/search`、`/play`、`/lyric`、`/sources`。App 只调本站接口，
  不直连平台，也不接触 Cookie 原文。
- 推荐与同步：`/app/music/recommend`、`/like`、`/likes`、`/playlists`、`/playlist`。
  App 没有登录体系，这几个接口用客户端生成的 `deviceId` 归属「我喜欢」。
- 地址：debug 默认 `http://127.0.0.1:8080/`，配套 `adb reverse tcp:8080 tcp:8080`。
  **不要用 `10.0.2.2`** —— 本机 QEMU 映射不生效，表现为 OkHttp 超时。
- 真机/无线调试：`./gradlew :app:installDebug -PapiBaseUrl=http://192.168.1.10:8080/`，
  并放行 Windows 防火墙 8080。

## UI 设计

设计基准是「白底 + 蓝 accent + 大字号中文标题」；底部导航和关键动效按
Liquid Glass 的层次做，但性能优先：**不采样、不模糊背后的内容**，只画半透明层。

| token | 值 | 用在哪 |
| --- | --- | --- |
| canvas | `#F5F6F8` | 页面底色 |
| surface | `#FFFFFF` | 卡片、mini player、底部导航 |
| ink | `#141419` | 主标题、曲名 |
| secondary | `#70707B` | 副标题、歌手 |
| accent | `#2F6BFF` | 主按钮、进度、选中态 |
| accent_soft | `#E9F0FF` | 标签底、浅色按钮 |
| dark canvas | `#0B0B11` | 正在播放 / 播放器 |

底部导航是自绘的悬浮液态玻璃（`BottomNavBar`）：半透明底 + 竖向柔光 +
边缘高光 + 半透明胶囊选中态。背后内容由系统直接合成，所以列表滚动、
封面切换时透出来的画面是实时的，不会因为反复截屏/模糊而掉帧。
播放器页整体切深色，导航条变深色玻璃；mini player、进度线、下划线 tab
也都是自绘控件（`MiniPlayerView` / `SeekLine` / `UnderlineTabs`）。

动效：
- 冷启动首屏用 `ShimmerSkeletonView` 流光骨架，内容加载后骨架淡出；
- 推荐页内容按 top → bottom 错峰入场，落点带 spring 回弹（`springEnter`）；
- 点赞/收藏用解析弹簧（`SpringValue`）做缩放 + 回弹，不用生硬的 scale 动画；
- 页面 edge-to-edge：内容可以滚到状态栏/导航栏下面，但顶部标题和底部内容
  通过 inset + 顶部渐隐避开系统栏，不会被通知栏或手势条挡住。

细节和取舍见 [`docs/music-architecture.md`](docs/music-architecture.md)。

## 开发约定

| 项 | 约定 |
| --- | --- |
| 包名 | `com.szc.learning`，子模块 `com.szc.learning.core.*` / `.music.*` |
| 命名 | 布局 `activity_*` / `fragment_*` / `item_*`，ViewModel 以 `ViewModel` 结尾 |
| 依赖方向 | `app → core:network → core:common`，`core` 之间不互相依赖 |
| 异常处理 | 网络层统一返回 `AppResult`，不向上抛异常 |
| 文案 | 所有用户可见文案进 `strings.xml`，不在代码里硬编码中文 |
| 提交信息 | Conventional Commits，描述用中文，如 `feat: 增加播放器竖滑切歌` |

## 关于 `feature/demo`

贪吃蛇是项目初期的练手模块，包含纯 Kotlin 规则层（8 条单测）与完全自绘的
`SnakeGameView`。已从构建中摘除，`settings.gradle.kts` 里的 `include(":feature:demo")`
是注释状态；恢复时取消该行注释，并同步取消 `app/build.gradle.kts` 里对应的依赖注释。