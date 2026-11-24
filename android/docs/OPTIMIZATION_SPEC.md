# Aurora 音乐播放器 · 汽水音乐化优化设计文档

> 给执行 AI 的说明：本文档是**完整任务书**。请先读「0. 通用规则」，再按「任务清单」的顺序逐项做。
> 每项任务独立可交付、可单独提交。做完一项、编译通过、自测通过，再做下一项。

---

## 0. 通用规则（必读）

### 0.1 项目事实
- Gradle 根目录是 `android/`（不是仓库根）。模块：`app`、`core:common`、`core:network`。
- 包名 `com.szc.learning`，音乐相关代码在 `app/src/main/java/com/szc/learning/music/`。
- `minSdk 24`，`targetSdk/compileSdk 37`，Java 11，ViewBinding 开启，**没有用 Compose**，UI 是传统 View + Fragment。
- 播放内核：media3 1.11.1（ExoPlayer + MediaSessionService）；图片：Coil 2.7.0；依赖版本在 `android/gradle/libs.versions.toml`（版本目录）。
- 架构：`PlaybackService`（持有 ExoPlayer + MediaSession）←→ `PlayerController`（MediaController 封装，向 UI 暴露 `StateFlow<PlayerUiState>`）←→ `MusicViewModel`（1400+ 行，业务总入口）←→ Activity/Fragment。
- 设计风格：**半透明纯染色面板 + 极光底，浅色优先，深色可用**。2026-11-18 已明确放弃实时玻璃/实时模糊（太费帧预算），**不要重新引入 backdrop 实时采集**。静态模糊图（一次性生成、不随滚动更新）是允许的。
- 代码注释与文案用**中文**，与现有风格一致；注释写「为什么」，不写「做了什么」。

### 0.2 禁止事项
- 不要重构与任务无关的代码，不要顺手改格式、改命名。
- 不要恢复已删除的文件（`GlassBackdrop.kt`、外卖相关代码）。
- 不要改 `/app/music/*` 后端接口契约（后端是另一端，在 `server/`）。
- 不要引入新的 DI 框架、不要把项目迁到 Compose。
- 不要提交 `.workbuddy/`、`local.properties`、任何 Cookie / 密钥。
- 新增第三方依赖必须在对应任务里列明；本文档**只批准了** `androidx.palette:palette-ktx`。其他需要依赖的地方用平台 API 实现。

### 0.3 工作方式
1. 开始前先 `git status`，工作区有大量**他人未提交改动**。**不要 `git stash` / `git reset` / `git checkout .`**。只改任务涉及的文件。
2. 每项任务动手前，先**读**任务里列出的文件，确认文档描述与代码一致；不一致以代码为准，并在最终说明里指出。
3. 每项任务完成后执行：
   ```
   cd android
   ./gradlew :app:assembleDebug
   ./gradlew :app:testDebugUnitTest
   ```
   Windows 下用 `gradlew.bat`。编译不过不得进入下一项。
4. 有设备/模拟器时，用 `./gradlew installDebug` 手动验证「验收标准」里的行为；**没有设备就明说「未做真机验证」**，不要声称验证过。
5. 每项任务一个 commit，信息格式：`feat(music): ...` / `perf(music): ...` / `fix(music): ...`。**不要 push**，不要自行合并分支。
6. 最终汇报必须包含：改了哪些文件、哪些验收项已验证、哪些没验证、遗留问题。

### 0.4 已知的陷阱（来自项目记忆，务必注意）
- `PlayerController.publish()` 会**整份重建** `PlayerUiState`。新增任何字段时，必须把需要保留的值从旧 state 带过去（现有 `recommendMode` 就是这样，漏掉会被 media3 回调冲掉）。
- 播放页是「播放队列的镜像」，不是刷歌流；改播放页相关逻辑时别破坏 `MusicViewModel.startFeed()` / `adoptQueueIfChanged()` 的语义。
- `FeedAudioCache` 是 SimpleCache 单实例（256MB，`cacheDir/feed_audio`），Service 与 ViewModel **共用同一个实例**；不要再 new 第二个指向同一目录的 SimpleCache，会崩。
- 切歌时异步结果可能晚到（歌词、封面取色）：回调里必须校验 `currentTrack?.key == 发起时的 key`，否则会把旧歌的结果盖到新歌上。`NowPlayingActivity.loadLyric` 已有这个写法，照抄。

---

## 任务清单总览

| # | 任务 | 优先级 | 预估改动 | 依赖 |
|---|------|--------|----------|------|
| T1 | 播放页封面取色渐变背景（兼容低版本） | P0 | 中 | palette |
| T2 | 通知栏/锁屏：喜欢按钮 + 当前歌词 + 防熄屏掉线 | P0 | 中 | 无 |
| T3 | 进度流拆分，降低 `render()` 频繁重绑 | P1 | 中 | 无 |
| T4 | `notifyDataSetChanged` 全部换成 DiffUtil | P1 | 小-中 | 无 |
| T5 | 定时关闭 + 倍速 | P1 | 中 | 无 |
| T6 | 迷你播放器：封面圆环进度 + 左右滑切歌 | P2 | 中 | 无 |
| T7 | 歌词视图增强（非当前行渐隐缩放、手动滚动回弹） | P2 | 中 | 无 |
| T8 | release 构建体检（R8、allowBackup、BASE_URL） | P1 | 小 | 无 |
| T9 | PlaybackService 健壮性（进程被杀后恢复播放） | P2 | 中 | 无 |

**推荐执行顺序：T8 → T1 → T2 → T3 → T4 → T5 → T6 → T7 → T9。**
（T8 最小且独立，先做能尽早暴露 R8 对现有代码的影响。）

---

## T1　播放页封面取色渐变背景

### 现状
`ui/now/NowPlayingActivity.kt`：
- `render()` 里换歌时 `binding.nowBackdrop.bindArt(track, sizePx = 1400)`，再 `applyBackdropBlur()`。
- `applyBackdropBlur()` 在 API < 31 直接 `return`，**低版本背景就是一张放大的清晰原图**；API ≥ 31 用 `RenderEffect.createBlurEffect(48f, 48f)`，对 1400px 大图做 GPU 模糊，偏贵。
- 页面深色是刻意的，背景应「跟着封面慢慢换色」，现状没有颜色过渡。

### 目标（汽水音乐的做法）
背景 = **封面主色派生的纵向渐变 + 一层低透明度、预先缩小的模糊封面**；换歌时颜色在约 600ms 内平滑过渡。所有 API 级别效果一致。

### 设计
1. 新增依赖：`androidx.palette:palette-ktx`（在 `libs.versions.toml` 加条目，`app/build.gradle.kts` 引用）。
2. 新建 `ui/common/CoverPalette.kt`：
   ```kotlin
   data class CoverColors(val top: Int, val bottom: Int)   // 都已压暗，保证白字可读

   object CoverPalette {
       /** 从 Bitmap 取色。在后台线程调用。 */
       suspend fun extract(bitmap: Bitmap): CoverColors
       val Fallback: CoverColors   // 取色失败/无封面时的默认深色（取自当前主题的深色底）
   }
   ```
   - 先把 bitmap 缩到 ≤ 96px 再喂 `Palette.from(...).maximumColorCount(16)`，避免大图耗时。
   - 取色优先级：`darkVibrantSwatch` → `vibrantSwatch` → `dominantSwatch` → Fallback。
   - **压暗规则**：转 HSL，`lightness` 限制在 `0.12 ~ 0.28`，`saturation` 限制在 `≤ 0.65`。`bottom` 比 `top` 再暗约 40%。必须保证白色文字与背景对比度 ≥ 4.5:1（写一个单元测试覆盖这条规则）。
3. 取封面 Bitmap：Coil 2.7 用 `ImageRequest` + `allowHardware(false)` + `size(96, 96)` 经 `imageLoader.execute(request)` 取；**沿用项目现有的封面 Fetcher**（`ui/common/AudioArtFetcher.kt`、`Artwork.kt`、`CoverArt.kt`），先读这三个文件，复用 `bindArt` 里构造 request 的方式（本地 content:// 与在线 http:// 走同一套）。
4. 背景由两层 View 构成（改 `res/layout/activity_now_playing.xml`）：
   - 底层 `nowBackdropGradient`：纯 View，背景是 `GradientDrawable`（TOP_BOTTOM，两色），颜色由 `ValueAnimator` + `ArgbEvaluator` 过渡，时长 600ms，`DecelerateInterpolator`。
   - 上层 `nowBackdrop`（原有 ImageView）：保留，但改为**预模糊小图 + `alpha = 0.35f`**：
     - 请求尺寸改为 `sizePx = 160`（不再 1400），`scaleType=centerCrop`，放大铺满。
     - **不再使用 `RenderEffect`**，删除 `applyBackdropBlur()` 与相关 import / scale 设置。小图放大本身就是柔化；如需更糊，用 Coil 的自定义 `Transformation` 在小图上做一次 StackBlur（自己实现，约 60 行，半径 ≤ 16，仅在 160px 图上运行）。
   - 再叠一层现有的深色渐变蒙版（若布局里已有就保留）。
5. 换歌流程（`render()` 的 `if (boundKey != track.key)` 分支内）：
   ```
   boundKey = track.key
   bindArt(cover) ; bindArt(backdrop, 160)
   lifecycleScope.launch {
       val colors = CoverPalette.extract(...)          // IO/Default 线程
       if (currentTrack?.key != track.key) return@launch   // 竞态校验（见 0.4）
       animateBackdropTo(colors)
   }
   ```
6. 取色结果按 `track.key` 做 LRU 缓存（`LruCache<String, CoverColors>`，容量 32），切回上一首时秒切。
7. 顺手：进度条、播放按钮等强调色**可选**用 `colors.top` 提亮后着色（不强制；做的话必须保证对比度，且不要动 `ThemeManager` 全局主题）。

### 验收标准
- [ ] API 24~30 设备/模拟器上背景与 API 31+ 视觉一致，无「清晰大图」背景。
- [ ] 连续快速切歌 10 次，背景颜色最终与**当前歌**封面一致（无旧歌颜色残留）。
- [ ] 没有封面 / 封面加载失败 → 使用 Fallback，不崩溃、不闪白。
- [ ] 白色标题/歌手/歌词在任何封面（含纯白封面、纯黑封面）上都清晰可读。
- [ ] 单元测试：`CoverPaletteTest` 覆盖压暗规则（输入纯白、纯黑、高饱和红，输出 lightness/saturation 在范围内）。
- [ ] `grep -rn "RenderEffect" app/src/main/java/com/szc/learning/music/ui/now` 无结果。

### 涉及文件
`ui/now/NowPlayingActivity.kt`、`res/layout/activity_now_playing.xml`、新增 `ui/common/CoverPalette.kt`、`gradle/libs.versions.toml`、`app/build.gradle.kts`、新增测试。

---

## T2　通知栏 / 锁屏：喜欢按钮、歌词、防熄屏掉线

### 现状
`player/PlaybackService.kt` 只有 `MediaSession.Builder(this, player).build()`，通知栏是 media3 默认样式；没有自定义按钮；没有 `setWakeMode`。

### 目标
1. 通知栏与锁屏有「喜欢」按钮，状态与 App 内收藏一致。
2. 通知栏副标题展示当前歌词句（有时间码歌词时），否则显示歌手。
3. 熄屏后在线播放不被系统 Wi-Fi/CPU 休眠掐断。

### 设计
1. **防熄屏掉线**（最简单，先做）：`ExoPlayer.Builder` 增加 `.setWakeMode(C.WAKE_MODE_NETWORK)`。Manifest 已有 `WAKE_LOCK` 权限，无需再加。
2. **喜欢按钮**：
   - 定义自定义命令 `SessionCommand("aurora.toggle_like", Bundle.EMPTY)`。
   - `MediaSession.Builder(...).setCallback(object : MediaSession.Callback { onConnect(...) { 返回 AcceptedResultBuilder，把该命令加入 availableSessionCommands } ; onCustomCommand(...) { 切换喜欢 } })`。
   - 用 `setCustomLayout(ImmutableList.of(likeButton))` 放按钮；`CommandButton` 的图标用现有 `R.drawable.ic_heart` / `ic_heart_filled`（已存在）。喜欢状态变化时调用 `session.setCustomLayout(...)` 刷新。
   - **收藏的唯一真相源是 `MusicViewModel` 里的 `_favorites`（`toggleFavorite(track)` / `isFavorite(key)`）**，它属于 UI 层 ViewModel，Service 里拿不到。解决办法：把「切换喜欢」下沉到 `AppContainer` 里已有的 `FavoritesStore`（`music/data/FavoritesStore.kt`）或其上层仓库。**先读 `FavoritesStore.kt`、`AppContainer.kt`、`MusicViewModel.toggleFavorite` 的实现**，确定一条 Service 与 ViewModel 都能调用的路径；ViewModel 里的 `_favorites` 要能感知 Service 触发的变化（让 ViewModel 订阅 Store 的 Flow，而不是自己维护副本——若 Store 暂无 Flow，则加一个）。
   - 不要在 Service 里依赖任何 Activity/ViewModel 实例。
   - 后端同步（`biz_music_user_like`、网易云平台）逻辑**保持原样**：只要走与 `toggleFavorite` 相同的底层调用即可，不要新写一套。
3. **歌词副标题**：
   - 在 `MediaMetadata` 里更新 `subtitle` 或 `artist`。更新频率必须节流：**仅当歌词句变化时**更新（`LyricView` 里已有 `activeIndex` 逻辑，参考其算法），不是每个进度 tick。
   - 实现位置：`PlayerController`（它在 UI 进程侧持有 `MediaController`）无法直接改 Session 的 metadata；改为在 Service 侧自己持有当前歌曲的 `LyricSheet` 并监听 `player.currentPosition`——代价大。**折中方案（本任务采用）**：Service 暴露一个自定义命令 `aurora.set_subtitle`（参数为字符串），`PlayerController` 在歌词行变化时发送，Service 收到后 `player.replaceMediaItem(index, item.buildUpon().setMediaMetadata(新 metadata).build())`。注意 `replaceMediaItem` 会触发 `onEvents`，确认不会造成播放中断或队列重置（用当前项做替换时 ExoPlayer 不应重建 source；若发现会卡顿/重缓冲，**放弃本子项**并在汇报中说明，不要硬上）。
   - 此子项风险较高，列为**可选**；T2 的 1、2 两项必须完成。
4. 通知渠道：先确认 media3 默认通知渠道名称是否为中文友好；如要自定义，用 `DefaultMediaNotificationProvider.setChannelName(...)`，文案放 `strings.xml`。

### 验收标准
- [ ] 通知栏出现心形按钮；在通知栏点它，App 内播放页的心形同步变化；反过来亦然。
- [ ] 锁屏界面（Android 13+ 的媒体控件）有该按钮。
- [ ] 连续播放在线曲目，熄屏 10 分钟后仍不断（真机验证；无设备则标注未验证）。
- [ ] 切歌后心形状态立刻对应新歌。
- [ ] 手动点收藏仍会走后端同步，行为与改动前一致。

### 涉及文件
`player/PlaybackService.kt`、`player/PlayerController.kt`、`AppContainer.kt`、`music/data/FavoritesStore.kt`、`ui/MusicViewModel.kt`（`toggleFavorite` 周边）。

---

## T3　进度流拆分

### 现状
`PlayerController` 里 `startPositionTicker()`：播放中每 **350ms**、空闲每 800ms 往 `PlayerUiState` 里写一次 `positionMs`。`NowPlayingActivity` 和其他页面订阅整个 `playerState`，每次都跑一遍 `render(state)`——包括 `setImageResource`、`getString`、`contentDescription` 赋值、文字重设。

### 目标
进度变化只刷新「进度相关」的控件；其余 UI 只在对应字段变化时更新。

### 设计
1. `PlayerUiState` **删除** `positionMs`，新增在 `PlayerController` 上单独暴露：
   ```kotlin
   val position: StateFlow<Long>      // 播放进度，ticker 写这里
   ```
   `progress` 计算属性搬到用到它的地方（`durationMs` 保留在 `PlayerUiState`，两者组合计算）。
   - 如果这样改动面过大（`positionMs` 被很多地方引用），采用**保守方案**：保留 `positionMs` 字段，但 ticker 只更新独立的 `position` Flow，`PlayerUiState.positionMs` 仅在 seek / 切歌 / 暂停等**离散事件**时更新。先 `grep -rn "positionMs" app/src` 评估引用数量再决定，在汇报里写明选了哪种。
2. `NowPlayingActivity`：
   - `render(state)` 里去掉进度相关赋值；新增第二个 collector 订阅 `position`，只更新 `nowPosition` 文字、`nowSeek.progress`、`nowLyric.updateProgress`。
   - `render(state)` 内对 `setImageResource` / `contentDescription` 增加「值变化才设置」判断（保存上一次的 `isPlaying`、`playMode`）。
   - 用户正在拖动进度条时，不要被 ticker 覆盖（`SeekLine` 已有 `onSeekFinished`，先读它是否有 dragging 标志，没有就加）。
3. 对其他订阅 `playerState` 的地方（`MiniPlayerView.bind`、`PlayerFragment`、`RecommendFragment` 等）同样 `grep` 检查，按相同方式处理。
4. 歌词平滑度：`LyricView` 行级高亮 350ms 粒度够用，不要为此缩短 ticker 间隔。

### 验收标准
- [ ] 播放中 `render()` 不再每 350ms 被调用（加临时日志/断点验证，**提交前删掉日志**）。
- [ ] 进度条、时间文字、歌词高亮行为与改动前一致，拖动进度条不抖动。
- [ ] 暂停时进度条不再有任何周期刷新。
- [ ] 现有单元测试全部通过；若有测 `PlayerUiState.progress` 的用例要同步更新。

### 涉及文件
`player/PlayerController.kt`、`player/PlayerUiState.kt`、`ui/now/NowPlayingActivity.kt`、`ui/widget/MiniPlayerView.kt`、`ui/player/PlayerFragment.kt`，及其他引用点。

---

## T4　`notifyDataSetChanged` → DiffUtil

### 现状（`grep -rn notifyDataSetChanged app/src/main` 为准）
- `ui/player/PlayerAdapter.kt:66`（`submit`，还会 `holders.clear()`）
- `ui/track/SectionHeaderAdapter.kt:21`
- `ui/widget/LyricView.kt:223, 236`

### 设计
1. **PlayerAdapter**（ViewPager2 竖滑）：改用 `DiffUtil.calculateDiff` + `dispatchUpdatesTo`，比较依据 `track.key`（已有 `setHasStableIds(true)`，`getItemId` 用 `key.hashCode()`）。
   - **删掉 `holders.clear()`**，改为在 `onViewRecycled` / `onViewDetachedFromWindow` 里维护 `holders`（先读 `holders` 的用途——`setActive` 遍历它——确保换成增量维护后 `setActive` 仍对所有当前 holder 生效）。
   - 队列只是尾部追加时，应只触发 `notifyItemRangeInserted`，**当前页不得被重建**（这是播放页「推荐追加」场景，重建会闪一下）。
2. **SectionHeaderAdapter**：内容通常只有一项，用 `notifyItemChanged(0)` 或按实际情况；先读文件再定。
3. **LyricView**：歌词整份替换（切歌）本来就是全量变化，`notifyDataSetChanged` 在此**是合理的**。改动：保留调用，但加 `@Suppress("NotifyDataSetChanged")` 与一行注释说明原因；`showPlaceholder` 里若 `lines` 已为空且 placeholder 变化，用 `notifyItemChanged` 即可。**不要强行 DiffUtil 化歌词。**
4. `RecommendTrackAdapter` 已经在注释里说明了用 DiffUtil 的原因，参照它的写法保持风格一致。

### 验收标准
- [ ] 播放页队列尾部追加歌曲时，当前页面不闪烁、不回到第一页。
- [ ] 外部点播换队列后，播放页位置正确（沿用 `adoptQueueIfChanged` 语义）。
- [ ] `grep -rn "notifyDataSetChanged" app/src/main` 只剩带 `@Suppress` 且有注释的位置。

---

## T5　定时关闭 + 倍速

### 目标
播放页菜单里提供：定时关闭（15 / 30 / 60 分钟、播完当前歌曲、关闭）和倍速（0.75 / 1.0 / 1.25 / 1.5 / 2.0）。

### 设计
1. **倍速**：`PlayerController.setSpeed(speed: Float)` → `controller.setPlaybackSpeed(speed)`；`PlayerUiState` 新增 `speed: Float = 1f`（**注意 `publish()` 整份重建**：从 `player.playbackParameters.speed` 读取即可，无需从旧 state 带）。
2. **定时关闭**：
   - 逻辑放 `PlayerController`（或新建 `player/SleepTimer.kt`，由 PlayerController 持有）。用协程 `delay` 倒计时，到点调用 `controller.pause()`，并可做 3 秒音量淡出（`setVolume` 已存在）。
   - 「播完当前歌曲」模式：监听 `onMediaItemTransition` / `onPlaybackStateChanged`，当前歌结束时暂停，**不能**让下一首先响一下。实现提示：用 `player.pauseAtEndOfMediaItems = true`（media3 1.x 提供）。
   - 状态暴露：`PlayerUiState` 增加 `sleepTimerEndAtMs: Long?`（null 表示未设置）或独立 StateFlow；UI 显示剩余时间。
   - **定时计时必须在 Service 进程生命周期内可靠**：`PlayerController` 的 scope 属于 UI 侧，App 被划掉后计时会丢。若要可靠，把计时放到 `PlaybackService` 内，经自定义 SessionCommand 设置（与 T2 的自定义命令机制一致）。**推荐直接放 Service**。
3. UI：`NowPlayingActivity` 底栏现有按钮已很多，放在**「更多」入口**或长按 `nowMode` 之类？请**不要**随意挤占现有按钮。方案：在顶部栏（`nowTopBar`，目前有收起按钮）右侧加一个「⋯」按钮，弹出一个与现有风格一致的底部面板（参考 `showQueueSheet` / `CommentSheet` 的实现与样式，**先读这两个文件**），内含两组选项。文案放 `strings.xml`，图标新增矢量图放 `res/drawable`。
4. 生效后用 `HudToast` 给文字回执（项目现有做法，见 `NowPlayingActivity.toast`）。

### 验收标准
- [ ] 设 1 分钟定时（开发期可临时加调试入口，提交前移除）→ 到点音乐淡出并暂停。
- [ ] 「播完当前歌曲」：当前歌结束后暂停，下一首不出声。
- [ ] 倍速切换立即生效，切歌后保持所选倍速；音调不变（ExoPlayer 默认保持音高）。
- [ ] App 从最近任务划掉后（Service 仍在播），定时关闭仍然生效。
- [ ] 面板暗色/浅色主题下均正常。

---

## T6　迷你播放器：圆环进度 + 滑动切歌

### 现状
`ui/widget/MiniPlayerView.kt`（`bind(...)` 在第 66 行附近）；布局 `res/layout/view_mini_player.xml`。**先完整读这两个文件。**

### 设计
1. 封面外围加一圈**圆环进度**（自定义 `View`，`ui/widget/RingProgressView.kt`，`onDraw` 画底环 + 进度弧，`Paint` 在构造时创建，不在 `onDraw` 里 new）。进度由 T3 的 `position` Flow 驱动（若 T3 未做，用 `state.progress`）。
2. 封面在播放时缓慢旋转（`ObjectAnimator` 360°、20s、线性、无限重复）；暂停时**停在当前角度**（`pause()`/`resume()`，API 24 的 `ObjectAnimator.pause()` 需 API 19+，OK）；View detach 时取消动画，避免泄漏。
3. 左右滑动切歌：给 mini 条根 View 加 `GestureDetector`，水平 fling 超过阈值（`dp(48)` 且速度足够）调用 `viewModel.previous()/next()`；与点击进入播放页的 click 不冲突（点击用 `onSingleTapUp`）。切换时加一个小的位移 + 淡入淡出动画（可复用 `ui/motion/Spring.kt` 里的弹簧工具，先读它）。
4. 不要新增布局层级过深；该条常驻底部，注意绘制开销（圆环用 `invalidate()` 仅在进度变化 ≥ 0.5% 时触发）。

### 验收标准
- [ ] 播放时封面旋转、环形进度推进；暂停时停住不归零。
- [ ] 左滑下一首、右滑上一首；点击仍然进入播放页。
- [ ] 旋转屏幕/Activity 重建后动画状态正确，无泄漏（`LeakCanary` 非必需，但检查 detach 时已 cancel）。

---

## T7　歌词视图增强

### 现状
`ui/widget/LyricView.kt`（583 行，RecyclerView 实现，带 `activeIndex`、`resetBrowsing()`、`applyCenterPadding()`、`onLineClick`）。**先通读。**

### 设计（只做下列三点，不改架构）
1. **非当前行渐隐/缩放**：当前行 `alpha=1, scale=1.0`；距离当前行 1 行 `alpha≈0.6`，2 行 `≈0.4`，更远 `≈0.28`；缩放可选 `1.0 → 0.94`。用 RecyclerView 的 `onScrolled` 里按每个子 View 到视口中心的距离统一计算（连续函数，别用离散 if），保证滚动中也平滑。
2. **手动滚动后回弹**：用户拖动浏览歌词后，停手 **3 秒**自动平滑滚回当前行（现有 `resetBrowsing()` 看看是否已有类似逻辑，有则只补缺口）。回弹期间若用户再次触摸，取消回弹。
3. **当前行切换动画**：滚动到新当前行使用 `smoothScrollToPosition` + 自定义 `LinearSmoothScroller`（`calculateDtToFit` 使目标行居中，`calculateSpeedPerPixel` 控制约 300ms 完成）。

### 不做
逐字（卡拉 OK）高亮——需要 QRC/YRC 数据，当前后端接口只给 LRC，**本期不做**；仅在汇报中提一句「后端若提供逐字时间轴可后续扩展」。

### 验收标准
- [ ] 歌词滚动流畅（无明显掉帧），非当前行层次分明。
- [ ] 手动滚动 3 秒后自动回到当前行；点击某行仍可 seek。
- [ ] 无时间码的纯文本歌词（`timed=false`）行为不变：静态居中、点一下看全文。
- [ ] 歌词为空 / 占位状态不崩溃。

---

## T8　release 构建体检

### 现状（`app/build.gradle.kts`）
```kotlin
release {
    buildConfigField("String", "BASE_URL", "\"https://api.example.com/\"")
    optimization { enable = false }
}
```
`AndroidManifest.xml` 里 `android:allowBackup="true"`，应用内存放 Cookie 登录态与 deviceId。

### 设计
1. 开启 R8：先读现有 `optimization` 块是 AGP 哪个版本的 DSL，用对应的写法开启（传统写法是 `isMinifyEnabled = true; isShrinkResources = true; proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")`；本项目 AGP 较新，**以 `libs.versions.toml` 里 AGP 版本与官方文档为准**，不要凭记忆写）。
2. 在 `app/proguard-rules.pro` 补 keep 规则，重点：
   - Gson 反序列化的数据类（`core/common/music/MusicTrack` 及所有被 Gson/Retrofit 解析的 model；先 `grep -rn "Gson\|@SerializedName" app core` 找全）。
   - Retrofit 接口（`core:network`）。
   - media3、Coil 一般自带 consumer rules，无需重复，**除非实测报错**。
3. `AndroidManifest.xml`：`allowBackup="false"`；若要保留备份，需配 `dataExtractionRules` / `fullBackupContent` 排除含 Cookie 的 DataStore 文件。**默认直接设 false。**
4. `BASE_URL`：**不要自行编造域名**。保持占位，但在 `app/build.gradle.kts` 里让 release 的 BASE_URL 可通过 `-PapiBaseUrlRelease=` 覆盖，未提供时 `gradle` 配置阶段打印一条 warning（不要让构建失败）。
5. 用 `./gradlew :app:assembleRelease`（无签名也能出 unsigned apk）验证 R8 过得去；**用该包在设备上冒烟**：能启动、能搜索、能播放、收藏同步、歌词显示。无设备则明说未验证，并列出需要人工冒烟的清单。

### 验收标准
- [ ] `assembleRelease` 成功，无 R8 missing class 错误（有 warning 需逐条评估，不能全局 `-dontwarn **`）。
- [ ] release 包体积相对改动前减小（汇报前后数字）。
- [ ] 冒烟：序列化相关功能（推荐、收藏、登录 Cookie）在 release 包里正常——这是 R8 最容易搞坏的地方。
- [ ] `allowBackup=false`。

---

## T9　PlaybackService 健壮性

### 现状
- `onDestroy` 里 `feedCache.clear()`：缓存不跨会话（这是**有意的设计**，见项目记忆；**本任务不改此行为**）。
- 没有 `onPlaybackResumption`：进程被系统杀掉后，蓝牙耳机/通知栏的播放键无法恢复上次队列。

### 设计
1. 在 `MediaSession.Callback` 里实现 `onPlaybackResumption(mediaSession, controller)`（media3 ≥ 1.2 提供，1.11.1 可用；**以实际 API 签名为准**，用 IDE/源码确认）。
2. 需要把「最近一次播放队列 + 当前索引 + 位置」落盘：
   - 复用现有 `RecentStore`（`music/data/RecentStore.kt`）或新建一个小的 DataStore 文件；**先读 `RecentStore`**，若它只存「最近播放曲目」而不含队列/位置，再新增 `QueueSnapshotStore`（`datastore-preferences` + Gson，与项目已有依赖一致）。
   - 保存时机：队列变化、切歌、暂停、`onTaskRemoved`、`onDestroy`。位置只在暂停/销毁时存，别每个 tick 写盘。
3. 恢复时用 `MediaSession.MediaItemsWithStartPosition(items, startIndex, startPositionMs)` 返回。`MediaItem` 的构造复用 `PlayerController.toMediaItem()` 的逻辑（它是 `private`，需要提取到公共位置，例如 `player/MediaItems.kt`，并让 `PlayerController` 也改用它，避免两份逻辑漂移）。
4. 在线曲目的播放 URL 可能过期：恢复时只恢复**元数据**，URL 的获取沿用项目现有的解析路径（先读 `MusicTrack` 与 `toMediaItem` 如何得到 URI，若在线曲目的 URL 是播放前动态解析的，恢复时要走同一解析；解析不了则只恢复本地/云端曲目，并在汇报中说明限制）。

### 验收标准
- [ ] 播放中用 `adb shell am kill com.szc.learning`（或开发者选项杀进程）后，点蓝牙/通知栏播放键能续播上次那首（本地曲目必须成功；在线曲目按上述限制）。
- [ ] 正常使用路径（不杀进程）行为不变，不出现额外写盘风暴（查看 DataStore 写入频率）。

---

## 附录 A：最终汇报模板

```
## 完成情况
- T?: 已完成 / 部分完成 / 未做（原因）

## 改动文件
- ...

## 验证
- 编译：assembleDebug ✅ / assembleRelease ✅
- 单元测试：通过 N 个
- 真机/模拟器验证：[已验证项] / [未验证项及原因]

## 与文档不一致的地方
- ...（代码实际情况与本文档描述不同之处）

## 风险与遗留
- ...
```

## 附录 B：不在本期范围（别做）
逐字歌词、听歌识曲、悬浮桌面歌词、跨会话音频缓存/离线下载、实时玻璃/实时 backdrop 模糊、迁移 Compose、重构 `MusicViewModel`（1400+ 行，值得拆，但属于独立大工程，需要单独立项）。
