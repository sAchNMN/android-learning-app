# App 架构（音乐版）

> 这份文档描述的是 **Aurora 音乐播放器** 的客户端结构。
> 外卖点餐时代的实现已经整体移除，仅保留在 git 历史里（`master` 分支 118a4be 之前）。

## 模块

```
android/
├── core:common    纯数据与工具：MusicTrack 统一模型、AppResult、Logger
├── core:network   Retrofit 唯一出口：ApiClient / AuthApi / MusicApi+Repository
├── app            UI + 播放：media3、玻璃控件、四个 tab、正在播放
└── feature:demo   贪吃蛇，未参与构建
```

依赖方向单向：`app → core:network → core:common`。`core` 之间不互相依赖。

## 播放链路

```
Fragment/Activity
   ↓ 只调方法，不碰 ExoPlayer
MusicViewModel (Activity 作用域，四个 tab 共享)
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

## 曲目模型

`MusicTrack` 是本地与云端的统一模型，靠 `source` 区分，`uri` 分别是
`content://media/external/audio/media/{id}` 和 `https://.../profile/music/xx.mp3`。

界面上任何地方都不写 `if (source == LOCAL)` —— 分叉只发生在数据层。

## 本地封面

Android 10 之后 MediaProvider 不再维护 `albumart` 表，
`content://media/external/audio/albumart/{albumId}` 查出来是空的。
封面实际在 mp3 的 ID3 里，只能通过 `ContentResolver.loadThumbnail(音频uri)` 取。

所以 `LocalMusicRepository` 把 `artUri` 设成**音频自己的 uri**，
由 `AudioArtFetcher`（注册进全局 Coil）拦截并换成缩略图。
取不到时用 `CoverArt` 按曲目哈希生成渐变封面，不留灰块。

## 视觉系统

见 `GlassView` / `AuroraBackground` / `GlassTabBar` / `GlassSegmented` / `SeekLine`。

核心思路：玻璃不用 shape drawable（XML 的 `<stroke>` 只能单色），
全部 Canvas 绘制五层叠加 —— 渐变填充、左上镜面高光、顶部薄雾、
对角线折射描边、底部内阴影。参数都开放成 XML 属性，默认值即标准玻璃。
