# App 架构（音乐版）

> 这份文档描述 **Aurora 音乐播放器** 的客户端结构。
> 外卖点餐时代的实现已经整体移除，仅保留在 git 历史里（`master` 分支 118a4be 之前）。

## 模块

```
android/
├── core:common    纯数据与工具：MusicTrack 统一模型、AppResult、Logger
├── core:network   Retrofit 唯一出口：ApiClient / AuthApi / MusicApi+Repository
├── app            UI + 播放：media3、五个 tab、播放器竖滑、正在播放
└── feature:demo   贪吃蛇，未参与构建
```

依赖方向单向：`app → core:network → core:common`。`core` 之间不互相依赖。

## 播放链路

```
Fragment/Activity
   ↓ 只调方法，不碰 ExoPlayer
MusicViewModel (Activity 作用域，五个 tab 共享)
   ↓
PlayerController  ←── StateFlow<PlayerUiState> ──→ UI 订阅
   ↓ MediaController（异步连接）
PlaybackService : MediaSessionService
   ↓
ExoPlayer
```

三个关键决定：

1. **播放器在 Service 里**，不在 Activity。Activity 销毁播放不断，通知栏/锁屏/耳机键
   全部由 `MediaSessionService` 接管，UI 一行通知代码都不用写。
2. **UI 不接触 MediaController**。连接是异步的，直接暴露会让每个页面都要处理空窗期；
   `PlayerController` 把状态摊平成 `PlayerUiState` 纯数据，未连上时所有操作静默忽略。
3. **位置靠轮询**。播放位置没有事件回调，`PlayerController` 播放时 350ms 轮询一次、
   暂停时降到 800ms。

## 播放器竖滑

`PlayerFragment` = 竖向 `ViewPager2` + `PlayerAdapter`，一页一首歌。
数据来自 `MusicViewModel.allTracks()`，播放器页和其他页面用的是同一个队列、
同一个 `MusicTrack.key`，滑到哪首就播哪首，收藏状态也天然一致。

这里**没有视频，也没有每页一个播放器**：

- 音频只由全局 `PlayerController` 播放；页面只订阅 `PlayerUiState`，
  不创建 `ExoPlayer`、不碰 `SurfaceView`。
- 翻页到新曲目时调 `viewModel.play(allTracks, index)`；如果当前已经在播这首，
  就只同步状态，不重新起播，避免滑回当前页时从头开始。
- 外部换歌（上一首/下一首/通知栏）时，`playerState.track.key` 变化会反向同步
  `ViewPager2` 的页码，页码和全局播放状态不会脱节。
- 离开播放器 tab 不停音频：音频的生命周期归 Service，播放器页只负责显示。
- 一页只加载当前曲目的歌词，并预取前后页；所有页面共用 `offscreenPageLimit = 1`，
  没有视频解码器，滑动的成本只有封面和文字。

## 曲目模型

`MusicTrack` 是本地与云端的统一模型，靠 `source` 区分，`uri` 分别是
`content://media/external/audio/media/{id}` 和 `https://.../profile/music/xx.mp3`。

界面上任何地方都不写 `if (source == LOCAL)` —— 分叉只发生在数据层。

## 本地封面与歌词

Android 10 之后 MediaProvider 不再维护 `albumart` 表，
`content://media/external/audio/albumart/{albumId}` 查出来是空的。
封面实际在 mp3 的 ID3 里，只能通过 `ContentResolver.loadThumbnail(音频uri)` 取。

所以 `LocalMusicRepository` 把 `artUri` 设成**音频自己的 uri**，
由 `AudioArtFetcher`（注册进全局 Coil）拦截并换成缩略图；`loadThumbnail`
失败时再用 `MediaMetadataRetriever.embeddedPicture` 解 `APIC`。
取不到时用 `CoverArt` 按曲目哈希生成渐变封面，不留灰块。

歌词藏在 ID3v2 的 `USLT` / `SYLT` 帧里，Android 的 MediaStore 和
`MediaMetadataRetriever` 都不暴露。`LyricsRepository` 只做这一件事：
按需打开音频文件，解析 ID3v2.2/2.3/2.4 帧头，处理 unsynchronization、
常见文本编码和 `TXXX:LYRICS`，把结果按 `MusicTrack.key` 缓存。

- 不在 `LocalMusicRepository.load()` 里逐首解析歌词：几千首会明显拖慢进资料库。
- 播放器页进入某页时解析当前曲目，并预取前后页；缓存命中后切页无 IO。
- 云端曲目的 `lyrics` 是接口可选字段；没有就显示占位文案，不发额外请求。

## 视觉系统

设计基准是「白底 + 蓝 accent + 大字号中文标题」，底部导航和关键动效
按 Liquid Glass 的层次做，但**性能优先：不采样、不模糊背后内容**。

- tokens 全在 `res/values/colors.xml`：canvas `#F5F6F8`、surface `#FFFFFF`、
  ink `#141419`、secondary `#70707B`、accent `#2F6BFF`、accent_soft `#E9F0FF`、
  hairline `#E9EAEE`、播放器深色 `#0B0B11`。
- 底部导航是自绘的悬浮液态玻璃（`BottomNavBar`），不用 Material
  `BottomNavigationView`，因为要自由控制悬浮、squircle 圆角、播放器深色变体
  和胶囊弹簧。玻璃只画「半透明底 + 竖向柔光 + 边缘高光 + 半透明胶囊」，
  **不把 `tabContainer` 画进 bitmap，也不做 box blur**：背后的列表/封面由
  SurfaceFlinger 直接合成，滚动时透出的内容是实时的，不会因为每帧截屏而卡。
- 首屏：`ShimmerSkeletonView` 用 `Choreographer` 驱动一条 1.5s 的斜向流光；
  数据到位后骨架先等一帧真实内容 bind 完成，再淡出，避免中间出现空白。
- 入场：`springEnter` 把标题、搜索框、hero、快捷入口和列表行按 20–330ms
  错峰延迟，从屏幕下方 34–56dp 处带回弹落位；落点用解析 spring，而不是
  `DecelerateInterpolator`。
- 按压/点赞：`SpringValue` 和 `springPop` / `springScaleTo` 用
  `ω0 = 2π/duration`、`ζ = 1 − bounce` 求解，支持 retarget 和初速度；
  Reduce Motion / 系统动画时长为 0 时自动跳过。
- `MiniPlayerView` / `SeekLine` / `UnderlineTabs` 都是自绘控件；
  进度线、收藏心形、搜索/返回图标全部是 vector drawable。
- 正在播放页与播放器页用深色底 + 白色文字；播放器页的状态栏/导航栏图标随 tab 切白。
- 页面 edge-to-edge：`enableEdgeToEdge` + 透明系统栏，内容可滚到状态栏/导航栏
  下面；顶部标题用 inset 让开状态栏，底部列表留 156dp + navigationBars inset，
  再叠一条 `TopEdgeFadeView` 渐隐，避免文字和系统图标糊在一起。
- 所有用户可见文案在 `res/values/strings.xml`，不在代码里硬编码中文。