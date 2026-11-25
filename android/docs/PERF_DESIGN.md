# Aurora 音乐播放器 · 性能优化设计（功能零变更）

> 状态：**已实施**。编写于 2026-11-25，基于当日工作区（含未提交的队列编辑改动）；
> 同日按第 10 节顺序落地并逐项提交，第 9 节的改前 / 改后度量仍需设备或模拟器复测。
>
> 与 [`OPTIMIZATION_SPEC.md`](OPTIMIZATION_SPEC.md) 的关系：那份是功能任务书，其中与性能相关的
> T3（进度拆流）、T6、T8（R8）已落地，T4 大部分落地。本文不重复，只处理之后的性能问题。
>
> 引用约定：
> - app 模块路径省略前缀 `android/app/src/main/java/com/szc/learning/`；
>   core 模块写成 `core/common/.../`、`core/network/.../`；
> - 行号是编写时的快照，会漂移，动手前按函数名重新定位；
> - `music/ui/MusicViewModel.kt` 编写时正在被修改，只写函数名不写行号。

---

## 0. 硬约束

1. **功能零变更**。每一项只改「在哪个线程做、做几次、什么时候做」，不改「做什么」。
   会改变可观察结果的点全部集中在第 5 节，逐条确认后才做。
2. **不放宽防封规则**：`SourceThrottle` 的同域名串行、450ms 间隔、10 分钟风控冷却、
   ≤2KB 响应体扫描风控码，一条都不改。
3. 不改：探活的 2.5s 超时与 45s / 15s TTL；`FeedAudioCache` 每会话清空的语义；
   Gson 持久化类的字段名（它们是存档格式）。
4. 不引入实时模糊、不迁 Compose、不新增运行时依赖（Baseline Profile 例外，需单独批准）。
5. 一项一个 commit，可单独回滚；有风险的项留常量开关。
6. 工作区常有他人未提交改动：不要 `git stash` / `git reset` / `git checkout .`。

---

## 1. 总览

| # | 问题 | 触发 | 用户感受 | 风险 |
|---|---|---|---|---|
| P0-1 | 在线曲目逐首取址验证，被 450ms 节流串行化 | 推荐首屏、搜索、歌单详情、续流 | 等待数秒到数十秒 | 中 |
| P0-2 | 骨架屏 GONE 后逐帧空转 | 切走再切回推荐 / 本地 tab 之后 | 持续耗电 | 低 |
| P0-3 | 播放页每页在主线程软件绘制两张大占位图；每开一个 Activity 清空占位图缓存 | 上下滑切歌、打开任何页面 | 翻页掉帧 | 低～中 |
| P0-4 | 每个 Activity 一份 `MusicViewModel` | 打开二级页 | 重扫曲库、重复写盘、听歌时长翻倍 | 中 |
| P0-5 | 大队列：逐条 IPC 删除、O(n·m) 比对、每次切歌主线程整队序列化 | 千首队列 | 卡顿乃至冻结 | 低 |
| P1-1 | 看不见还在刷：隐藏播放页的进度、迷你条封面自转 | 播放中 | 耗电 | 低 |
| P1-2 | 无关事件导致整份播放状态重发；通知按钮无变化也重建 | 播放中 | 多余重绘 | 低 |
| P1-3 | 沉浸页每帧重画 3 张全屏径向渐变 | 停留沉浸页 | GPU 耗电 | 低 |
| P1-4 | 冷启动在主线程删除上次的音频缓存；数据库索引表累积 | 进程被杀后的冷启动 | 启动慢 | 低 |
| P1-5 | 后端探活在后台不停 | 进程存活期间 | 耗电 | 低 |
| P1-6 | Coil 全局软件位图 | 所有封面 | 内存、滑动 | 低 |
| P1-7 | 本地页 / 搜索页 / 推荐歌单在主线程整库计算 | 输入、切歌 | 输入卡顿 | 低 |
| P2 | 启动路径、重复铺底、收藏写盘、歌词整标签读取、若干小项 | — | — | 低 |

「大队列」不是边角场景：`MusicViewModel.playLocal()` 直接把整个曲库设为队列，
千首曲库点一首就是千首队列。

---

## 2. P0

### P0-1　取址验证：批量化 + 节流可取消

**现状**

- 网易云的搜索、日推、歌单解析出的曲目都是 `uri = ""`
  （`music/data/source/NeteaseClientSource.kt:328`）。
- 列表展示前都要过 `PlayableFilter.filter()`，调用方：
  `MusicViewModel.loadRecommendations`（20 首）、`searchOnline`、`playlistTracks`（整张歌单）、
  `appendToFeed`、`fallbackToCache`。
- `PlayableFilter` 对每首调一次 `router.play()`（`music/data/PlayableFilter.kt:75`），
  并发 8、单曲超时 8s、成功缓存 10 分钟、失败缓存 30 秒（`:99-102`）。
- 网易云每首一次 `/api/song/enhance/player/url?ids=[id]`（`NeteaseClientSource.kt:61`），
  拿不到地址再探一次外链（`:76`），两个请求都在 `music.163.com`；酷狗先 mobile 再 web
  （`KugouClientSource.resolve`）。
- 所有请求在 `SourceThrottle.acquire()` 里同域名串行、间隔 450ms
  （`music/data/source/SourceThrottle.kt:28`、`:47-61`）。
- 结论：**列表出现时间 ≈ 同一平台的请求数 × 450ms**。
  估算：51 首「我喜欢的音乐」首次打开约 23 秒。
- 超时形同虚设：`acquire()` 在 `synchronized` 里 `Thread.sleep`（`:56`），
  HTTP 用阻塞的 `execute()`（`SourceHttp.kt:66`、`:110`），都不响应协程取消。
  `withTimeout` 要等阻塞调用返回才能结束，返回后结果却按「已超时」丢弃、记成不可播并缓存 30 秒。
  多个页面同时请求同一平台时（推荐、歌单同步、账号资料、歌词），队列越长越容易误判。
- 作废的搜索、超时的验证照样把请求发出去，继续占后面的节流名额。

**方案**

1. **网易云批量取址**
   - `ClientSourceProvider` 新增 `resolveBatch(tracks, cookie): Map<String, OnlinePlayback>`，
     默认实现逐首调用 `resolve`（酷狗走默认实现）。
   - 网易云重写：按 ≤100 个 id 分块，一次请求 `ids=[id1,…,idN]`（同一接口、同一 `br` 参数），
     按响应 `data[]` 里的 `id` 对回曲目（不依赖返回顺序）。有 `url` 的直接算可播；
     没有的逐首走现有的外链探测与错误码翻译（与 `resolve` 的 1.2 / 1.3 分支共用同一段代码）。
   - `OnlineSourceRouter` 新增批量入口：客户端批量失败的曲目再逐首走 `fallbackPlay`，
     与现在的单曲路径一致。
   - `PlayableFilter.filter`：未命中缓存、需要取址的在线曲目按 `sourceKey` 分组，
     有批量能力的走批量；缓存 key、两档 TTL、并发上限不变；结果逐首写回缓存。
   - 单曲点播 `router.play()` 不动。
2. **节流改挂起**
   - 每个 host 一把 `Mutex`，用 `delay` 补足间隔；冷却判定与 `inspect()` 不变。
   - `acquire` 变 `suspend`；`SourceHttp.getText / getJson / probeAudio` 跟着变 `suspend`
     （调用方本来都在 `withContext(Dispatchers.IO)` 里，改动是机械的）。
   - HTTP 调用改为可取消：挂起等待回调，协程取消时 `call.cancel()`。

**为什么功能不变**：每首歌的可播判定来自同一接口的同一字段，判定与失败文案逻辑不变；
发给平台的请求只减不增，节流规则不放宽。

**残余**：酷狗没有批量接口，这部分仍受 450ms 节流约束。要更快只能改产品策略
（如先展示后验证），见第 6 节。

**验收**

- 单测：用录制的网易云响应，同一批 id 的批量结果与逐首结果逐项一致（可播与否、地址、失败文案）。
- 单测：风控响应（`"code":-460`、HTTP 403 / 429）仍触发 10 分钟冷却；协程取消后不再发请求
  （用 OkHttp Interceptor 伪造响应并计数，不新增测试依赖）。
- 实测：歌单详情 51 首、搜索 20 条、推荐首屏的「发起到列表出现」时间，改前改后对比。

**涉及文件**：`music/data/PlayableFilter.kt`、`music/data/OnlineSourceRouter.kt`、
`music/data/source/{ClientSourceProvider,NeteaseClientSource,KugouClientSource,SourceHttp,SourceThrottle}.kt`。

### P0-2　骨架屏 GONE 后逐帧空转

**现状**

- `ShimmerSkeletonView.onVisibilityChanged`（`music/ui/widget/ShimmerSkeletonView.kt:262-265`）
  按参数 `visibility == VISIBLE` 启动流光。这个参数是**发生变化的那个视图**（可能是祖先）的可见性，
  不是骨架自己的。
- 数据到位后骨架被设为 GONE（`music/ui/library/LibraryFragment.kt:142`、
  `music/ui/recommend/RecommendFragment.kt:269`）。切 tab 时 `MainActivity` 的 show/hide
  改的正是 Fragment 根视图：切走再切回来，祖先变 VISIBLE，已经 GONE 的骨架会重新启动
  `postOnAnimation` 循环（`:59-66`）。循环只检查是否 attached，于是此后每帧执行一次，
  直到视图销毁；在 App 内跳到别的页面时照跑。

**方案**：改用 `onVisibilityAggregated(isVisible)`（API 24+，同时覆盖自身、祖先与窗口可见性）
启停流光；或在启动时和每帧都判断 `isShown`。

**为什么功能不变**：骨架本来就不可见，画面零变化；加载中切走再切回，仍然继续流光。

**验收**：推荐 / 本地 tab 往返切换后，Perfetto 里不再有该视图的每帧回调；
冷启动骨架流光与淡出照旧；系统关闭动画时行为不变。

**涉及文件**：`music/ui/widget/ShimmerSkeletonView.kt`。

参照：`ImmersiveStageView.startIfVisible()` 有 `isShown` 判断（`music/ui/now/ImmersiveStageView.kt:171`），写法是对的。

### P0-3　封面占位图

**现状**

- `bindArt` 每次绑定先 `CoverArt.of(track.key, 视图宽度)` 现画一张渐变位图当占位
  （`music/ui/common/Artwork.kt:24`）：软件 Canvas、主线程。
- 播放页每页同时绑封面和全屏背景（`music/ui/player/PlayerAdapter.kt:262-263`，
  背景在 `item_player.xml` 里是 match_parent、alpha 0.30），即每页现画约 900px 与 1024px 两张，
  合计约 7～8MB。
- 缓存上限 6MB（`music/ui/common/CoverArt.kt:145`），两张互相挤掉：每次上下滑绑新页都在主线程重画，
  还会把列表行的小占位图一起挤出缓存。
- `ThemeManager.applyPalette()`（`ui/theme/ThemeManager.kt:113-120`）在各 Activity 的 `onCreate`
  里调用（全仓 10 处），每次都走 `CoverArt.configure()` → `cache.evictAll()`（`CoverArt.kt:75`）：
  打开任何页面，所有列表的占位图都要在主线程重画。

**方案**

1. （零风险）`configure()` 先算出新色板，与当前色板 `contentEquals` 相同就不清缓存。
2. （主修）新增 `CoverArtDrawable(seed)`：在 `onBoundsChange` 里用与 `CoverArt.of` 同一套
   哈希、色板、渐变坐标与色标建 Shader，`draw()` 直接画在硬件 Canvas 上。
   `bindArt` / `bindBackdropArt` 的 placeholder / error / fallback 改用它。
   不再分配位图，也不再软件光栅化。`GlassDialog`、`MineFragment` 直接用 `CoverArt.of` 的地方可保持不变。
3. （备选：主修截图比对不过时）占位图尺寸只取几个档位（如 128 / 256 / 512），按档缓存、按需缩放，
   缓存上限按档位重算。

**验收**

- 截图比对：一组种子在 120 / 280 / 720 / 1024 px、浅色 / 深色下，新旧占位图肉眼无差异。
- 播放页连续上下滑 20 次的 `gfxinfo framestats` 掉帧率改前改后对比；
  Perfetto 里翻页期间主线程不再出现 `Bitmap.createBitmap`。

**涉及文件**：`music/ui/common/{CoverArt,Artwork}.kt`。

### P0-4　全局状态只保留一份

**现状**

- 8 个 Activity 各自 `by viewModels()` 一份 `MusicViewModel`：`MainActivity`、`SearchActivity`、
  `TrackListActivity`、`ImmersivePlayerActivity`、`PlaylistDetailActivity`、`RankActivity`、
  `SourceActivity`、`AccountActivity`（四个 tab 的 Fragment 共用 `MainActivity` 那份）。
- 每份在 `init` 里挂常驻订阅：切歌时 `recentStore.record()` + `refreshRecent()`、
  `observeListeningTime()`、收藏流、收藏提示、推荐续流。这些订阅在 `viewModelScope` 里，
  不受页面可见性约束。构造时还在主线程读 `favoritesStore.keys()` / `tracks()`、`listenStatsStore.minutes()`。
- `SearchActivity.kt:123`、`TrackListActivity.kt:88` 各自 `loadLocal()`：每次打开都重扫 MediaStore，
  内存里多一份整库列表。
- 后果：N 份同时存活时，每次切歌写 N 次最近播放；**听歌时长被重复累计**
  （开着沉浸播放页时按 2 倍计）。项目记忆里「`MineFragment.onResume` 必须重读音源配置」也是这个根因。

**方案**

- `AppContainer` 新建 `MusicSession`（挂在 `appScope` 上），持有进程级状态：本地曲库与加载态、
  最近播放、收藏镜像、听歌分钟、音源配置；以及只该执行一次的副作用：切歌记最近播放、听歌时长累计。
- `MusicViewModel` 对外 API 一个不改：上述 `StateFlow` 改为转发 session；
  `loadLocal()` 改为「先给缓存、后台照常重扫、结果有变再发新值」，保留「打开页面会重扫」的语义。
- 页面私有状态（搜索结果、评论、榜单、刷歌流、账号页）留在 VM 里。
  这不是旧任务书附录 B 说的「拆 VM」大工程。

**风险**

- 属性初始化顺序：session 在 `AppContainer` 里构造，不读任何 VM，避开「init 读后声明属性」的坑。
- 听歌时长数字会变（修 bug），见第 5 节第 1 条；已拍板：历史值不动，只让增速恢复正常。
- 音源配置下沉后，`SourceActivity` 的改动主界面立刻可见；`MineFragment.onResume` 的重读变成无害冗余，保留。

**验收**

- 打开搜索页、列表页不再同步触发 MediaStore 查询（日志）；曲库新增文件后打开搜索页仍能出现（后台重扫生效）。
- 切歌一次只写一次最近播放。
- 单测复现「两个 VM 同时存活 → 时长 ×2」，改后为 ×1。

**涉及文件**：`AppContainer.kt`、新增 `music/data/MusicSession.kt`、`music/ui/MusicViewModel.kt`。

### P0-5　大队列链路

**现状**

- `PlayerController.clearQueue()`（`music/player/PlayerController.kt:224-236`，未提交的新代码）
  对每个索引调一次 `removeMediaItem`。每次都是一条跨进程命令 → Service 端一次 timeline 变化 →
  一次整队快照；控制端一次 `publish` 全量重发。2000 首队列清空 ≈ 2000 次 IPC + 2000 次整队序列化（O(n²)）。
- `removeUpcoming()`（`:160-175`）同样逐条删。
- `syncQueue()`（`:139`）用 `existing.any { tracks.none { … } }` 两两比对，O(n·m)，
  而且每次比较都重新拼 key 字符串。
- 快照：`PlaybackService` 在切歌、timeline 变化、暂停时调用 `persistSnapshot`
  （`music/player/PlaybackService.kt:107-131`），把整队 `toTrack` 一遍（`:299`），
  再在 `Dispatchers.Main.immediate` 的 `serviceScope`（`:134`）里写盘；
  `QueueSnapshotStore.write` 的 `gson.toJson`（`music/data/QueueSnapshotStore.kt:46`）因此跑在主线程，
  随后 DataStore 整文件重写。切歌那一帧正是封面、歌词切换最忙的时候。

**方案**

- 删除改区间：`clearQueue` = `removeMediaItems(playing + 1, count)` + `removeMediaItems(0, playing)`；
  `removeUpcoming` 把命中的索引合并成连续区间，从后往前按区间删。
- `syncQueue` 用 `HashSet<String>` 判断成员关系。
- 快照：
  1. `onTimelineChanged` 只在 `TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED` 时落盘
     （`SOURCE_UPDATE` 只是时长等元数据就绪，快照逐字节相同）；
  2. 与上次写入的（队列版本, index, position）相同就跳过（单曲循环的 transition 同理）；
  3. 主线程只取不可变的 `MediaItem` 列表与 index / position，`toTrack` 与 `toJson` 挪到 `Dispatchers.Default`；
  4. 300ms 内的多次写合并成一次，暂停、`onTaskRemoved`、`onDestroy` 立即 flush。
- 可选（需拍板，会改存档格式）：拆成 queue / cursor 两个 key，读不到 cursor 时回退旧格式。

**验收**

- 把「计算新队列」抽成纯函数，新旧实现在随机输入上结果逐项一致（JVM 单测）。
- 新旧快照写法对同一队列输出逐字节相同的 JSON。
- 2000 首队列：清空耗时与 IPC 次数（日志计数）改前改后对比；切歌时主线程 Trace 不再出现整队序列化。
- `adb shell am kill com.szc.learning` 后从通知栏续播正常。

**涉及文件**：`music/player/{PlayerController,PlaybackService}.kt`、`music/data/QueueSnapshotStore.kt`。

---

## 3. P1

### P1-1　看不见就不干活

- `PlayerFragment` 的进度订阅（`music/ui/player/PlayerFragment.kt:167-169`）没有 `isHidden` 判断；
  `playerState` 订阅里的 `adapter.setState()`（`:149`）在 `isHidden` 判断之前。
  隐藏时两处都跳过；`onHiddenChanged(false)`（`:334`）已经会补 `setState`，
  再补一句 `adapter.setPosition(viewModel.position.value)`。
- `MiniPlayerView` 的封面自转（`music/ui/widget/MiniPlayerView.kt:211-218`）是无限 `ObjectAnimator`，
  只在暂停播放和 detach 时停。在播放 tab（迷你条 GONE）或打开 App 内其它 Activity 时仍逐帧回调。
  改由 `onVisibilityAggregated` 控制：不可见就 pause，可见且在播就 resume，角度保留。
- 不用 `setMaxLifecycle` 降级隐藏的 tab：那会改变各 Fragment `onResume` 的触发时机
  （如 `MineFragment.onResume` 重读音源），属于行为变化。
- 已正确处理、不用动：`ImmersivePlayerActivity` 的呼吸动画（`:173-182`）、`ImmersiveStageView`、`GlassSurfaceView`。

### P1-2　只在离散事件重发状态

- `PlayerController.publish()`（`:477-505`）挂在 `onEvents` 上，每次都写
  `positionMs = player.currentPosition`。播放中位置一直在走，所以只要事件带了 isLoading、tracks、
  metadata 之类与 UI 无关的变化，data class 就不相等，整份状态重发：迷你条、播放页 2～3 个存活页、
  沉浸页都要重跑 render。
- 改为：events 含 `POSITION_DISCONTINUITY` / `MEDIA_ITEM_TRANSITION` / `IS_PLAYING_CHANGED` /
  `PLAYBACK_STATE_CHANGED` / `TIMELINE_CHANGED` 时才刷新 `positionMs`，否则沿用旧值，交给 StateFlow 去重。
  这正是 `music/player/PlayerUiState.kt:15-21` 注释承诺的语义；
  grep 确认没有 UI 读 `state.positionMs`（进度都走 `position` 流）。
- **先测后改**：临时日志统计每首歌 `publish` 的次数与 events 组成，确认收益再改，提交前删日志。
- 通知栏：`PlaybackService` 在每次 `onPlaybackStateChanged`（缓冲 ↔ 就绪）都 `setCustomLayout`
  （`:114-116`、`:244-257`），media3 会把新布局推给所有控制器、通知随之重建。
  记住上次的（mediaId, liked），相同就跳过。

### P1-3　沉浸页背景

- `ImmersiveStageView.onDraw`（`music/ui/now/ImmersiveStageView.kt:130-148`）每帧把 3 张全屏径向渐变全部重画，
  而渐变只在 `setColors` 换色时才变；只有 28 个光点需要逐帧推进。视图是 match_parent，
  停留在沉浸页期间持续逐帧绘制。
- 改为：静态渐变拆成独立 View 并开 `LAYER_TYPE_HARDWARE`（或在 `onSizeChanged` / `setColors` 时
  画进一张缓存位图），光点层单独每帧 invalidate。像素结果相同，
  每帧的全屏渐变填充从 3 遍降到 1 遍（合成一张纹理）。
- 顺手去掉 `onDraw` 里每帧的 `listOf(topShader, centerShader, bottomShader)` 分配。
- 验收：截图比对；沉浸页停留 1 分钟的 GPU 渲染耗时（`gfxinfo`）改前改后对比。

### P1-4　音频缓存初始化

- `FeedAudioCache` 构造时执行 `cacheDir.deleteRecursively()`（`music/player/FeedAudioCache.kt:42`）。
  构造发生在 `AppContainer` → `LearningApp.onCreate`，即**主线程、首帧之前**。
  进程被系统直接杀掉时 `onDestroy` 的清理没跑，残留最多 256MB。
- 只删文件不删库：`deleteRecursively` 不会清 ExoPlayer 数据库里旧缓存 UID 对应的索引表
  （`SimpleCache.delete(dir, databaseProvider)` 才会连表一起删），表会随启动次数累积。
  动手前先用 `sqlite3` 查看 `exoplayer_internal.db` 的表数确认。
- 改为：主线程把旧目录改名（瞬时完成），然后照常新建目录与 `SimpleCache`；
  IO 线程对改名后的目录调用 `SimpleCache.delete(oldDir, databaseProvider)`。
  新缓存照样从空开始，「不跨会话」的语义不变。
- 验收：预置 200MB 残留后冷启动 `am start -W` 对比；数据库表数不再随启动次数增长。

### P1-5　探活在后台暂停

- `OnlineSourceRouter.startWatching()` 的循环（`music/data/OnlineSourceRouter.kt:89-92`）在 `appScope` 里永不停，
  后端不可达时每 15s 一次。App 的常态就是后端不开，播放服务又让进程长期存活。
- 改为：`LearningApp` 用 `ActivityLifecycleCallbacks` 计数得到「有没有前台页面」（不引新依赖）；
  后台时循环挂起，回到前台立即探一次。`probeNow(force = false)` 的 TTL 语义不变，后台的按需调用照常现探。
- 已确认：外部直接读 `backendOnline.value` 的只有前台的 `MusicViewModel.searchOnline`，
  后台路径都走 `probeNow`。

### P1-6　Coil 硬件位图

- `LearningApp.kt:44` 全局 `allowHardware(false)`，注释理由是「玻璃高斯模糊要把容器画进软件画布」——
  这条路径已删除（`music/ui/glass/GlassSurfaceView.kt:27` 注释写明）。
- 已确认：全仓没有 `LAYER_TYPE_SOFTWARE`、`drawBitmap`、`BitmapShader`、`PixelCopy`、`drawToBitmap`；
  唯一读像素的 `CoverPalette` 在自己的请求上单独关了（`music/ui/common/CoverPalette.kt:102`）；
  通知栏封面走 media3 自己的 BitmapLoader。
- 改为：删除这一行。Coil 在 API 26+ 自动用硬件位图，24 / 25 与带 Transformation 的请求自动退回软件位图。
  保留常量开关。
- 验收：沉浸页、播放页取色、推荐 / 本地滑动、通知栏封面逐一回归；浏览封面后 `meminfo` 对比。

### P1-7　主线程整库计算

- `LibraryFragment.render()` 在主线程做过滤 + `groupBy` + `sortedBy`
  （`music/ui/library/LibraryFragment.kt:186-223`），搜索框输入（`:87`）与曲库更新都会走到这里。
- `SearchActivity.render()`（`music/ui/search/SearchActivity.kt:138-176`）：每次按键、每次在线状态变化
  （loading 起止、结果、错误）都调 `viewModel.search(query)`，在主线程过滤整库（`:140`）。
- `RecommendFragment.renderPlaylists()` → `Playlists.build()` 按歌手给整库分组
  （`music/ui/common/Playlists.kt:74-78`），由 `recent` 触发（`RecommendFragment.kt:175`），
  即每次切歌都跑，推荐 tab 隐藏时也跑。
- 统一改法：计算挪到 `Dispatchers.Default`，按输入（曲库实例、搜索词、视图模式）缓存结果，
  新输入到来就取消旧计算；`Playlists.build` 的歌手分组按曲库实例缓存，切歌只重算「最近播放」那部分。
  不加防抖，保持每个字都即时出结果。
- 验收：千首曲库下快速输入、切换视图、切歌时主线程无长帧；前后台两种实现输出一致（单测）。

---

## 4. P2

### 启动

- 权限已授予时，在 `onCreate` 就开始 `loadLocal`（IO 线程）；现在要等启动动画的 reveal 回调
  （`ui/main/MainActivity.kt:127-133`）。权限弹框仍等动画结束。
- `Application.onCreate` 把收藏 JSON 丢到后台预解析（`FavoritesStore.items` 是同步 lazy，
  主线程先到也只是等，不会比现在慢）。
- 不影响首帧的对象改成 lazy：`TogetherListenManager`、`SharedPlaylistStore`、`SourceAccountCache`、
  `RecommendCacheStore` 等。
- MediaController 连接推迟到首帧后：冷启动时播放器本来就是空队列，但连接前的命令会被静默丢弃——
  **必须同时实现「连接前命令排队」**，否则不做。
- Baseline Profile + profileinstaller：新增依赖、需要设备生成，需批准。

### 重复铺底

- 各配色主题都没覆盖 `windowBackground`（只有 `values/themes.xml:21` 一处），窗口背景永远是默认配色的
  canvas 色；每个 Activity 根布局又铺一层当前配色的 `?attr/auCanvas`；推荐页 Fragment 根布局再铺一层——
  推荐 tab 每帧是 3 层全屏同色填充。
- 不能直接删根背景（换了配色会变色）。做法：根布局已不透明铺满的 Activity 在 `setContentView` 之后
  `window.setBackgroundDrawable(null)`；删掉 `fragment_recommend.xml` 根上与 `MainActivity` 根同色的那层。
  深色页（沉浸页、播放器 tab）保留自己的背景。启动预览窗口不受影响。
- 验收：开发者选项「调试 GPU 过度绘制」前后对比；各配色 × 深浅色截图一致；键盘弹收、横竖屏无黑边。

### 收藏写盘

- 每次点红心都在主线程把全部收藏 `toJson`，再 `apply()` 整份写回（`music/data/FavoritesStore.kt:111-118`），
  页面 `onPause` / `onStop` 时系统的 QueuedWork 还要等它写完。
- 改为后台单线程合并写（内存仍是真相源），存储格式不变。

### 歌词读取

- `LyricsRepository` 把整个 ID3 标签读进一个数组（`music/data/LyricsRepository.kt:64-65`，上限 8MB），
  内嵌封面也在里面。
- 没有标签级 unsynchronisation 时，改为逐帧读帧头、`skip` 掉非歌词帧；有则退回现有写法。
  解析结果不变（单测覆盖 v2.2 / 2.3 / 2.4 样本）。

### 小项

- `MusicTrack.key`（`core/common/.../music/MusicTrack.kt`）每次访问都拼字符串，在 DiffUtil、
  队列比对等循环里反复调用：加 `@Transient` 的手写懒缓存字段。
  **不能用 `by lazy` 或属性初始化器**——Gson 反序列化不走构造器，会拿到 null。
- `FavoritesStore.keys()`（`:64`）每次重建整个 Set，`isFavorite()` 也走它：加随变更失效的缓存。
- 6 个独立的 `OkHttpClient`（`core/network/.../ApiClient.kt:31`、`music/data/source/SourceHttp.kt:35`、
  `music/data/OnlineSourceRouter.kt:72`、`music/data/room/RoomApi.kt:35`、`music/data/room/WanTransport.kt:54`、
  `music/data/local/KuwoDirectSource.kt:223`）：改为一个基础 client，其余用 `newBuilder()` 派生，
  共享连接池与线程池，超时各自保留。探活可以保留独立的连接池。
- 暂停时位置 ticker 仍每 800ms 唤醒一次主线程（`PlayerController.kt:437-452`，只为检查定时关闭）：
  改为挂起，等「开始播放」或「定时到点」二者之一。
- `setWakeMode(C.WAKE_MODE_NETWORK)`（`PlaybackService.kt:95`）对纯本地队列也持 WifiLock：
  按队列里有没有网络曲目在 LOCAL / NETWORK 间切换，混合队列保持 NETWORK。
- `AudioArtFetcher` 的回退路径用 `BitmapFactory.decodeByteArray` 全尺寸解码内嵌封面
  （`music/ui/common/AudioArtFetcher.kt:62-63`）：先 `inJustDecodeBounds`，再按请求尺寸算 `inSampleSize`。
- `QueueAdapter.kt:51`（未提交的新代码）用 `notifyDataSetChanged`：改 `ListAdapter` / DiffUtil，
  拖动排序的 `notifyItemMoved` 路径保留。
- `PlayableFilter` 的缓存（`:40`）与 `PlayerAdapter` 的歌词表（`:60`）只增不减：改成 LRU。

---

## 5. 会改变可观察结果的点（需拍板）

| # | 来自 | 变化 | 性质 |
|---|---|---|---|
| 1 | P0-4 | 「我的」累计听歌时长不再重复累计，增长会比现在慢 | 修 bug；**已拍板：历史值不动** |
| 2 | P0-1 | 原先因排队超时被误判「播不了」的歌会正常出现在搜索 / 推荐里 | 修 bug，结果可能变多 |
| 3 | P0-1 | 作废的搜索与验证请求不再发出 | 平台请求总数下降 |
| 4 | P0-5 | 队列变化后 300ms 内进程被杀，续播恢复上一版队列 | 持久化窗口 |
| 5 | P0-3 | 占位图改由 GPU 绘制，渐变抖动观感可能有极细微差异 | 需截图确认，不过就走备选方案 |
| 6 | P2 启动 | 曲库提前加载后，首次推荐更可能带上「最近播放」的歌手 | 现在取决于竞态 |

除这六点外，所有改动只影响「多快、多省」，不影响「做什么」。

### 第 1 条的决策记录（2026-11-25 已拍板）

**只修 bug，历史值不动。** 不清零，不按系数折半回溯。

理由与边界：

- `observeListeningTime()` 挂在 `MusicViewModel.init` 的 `viewModelScope`（Main.immediate），
  N 个 Activity 各自一份 VM 就有 N 份独立累加器，写同一个 `aurora_stats`。
  collect 全部串行在主线程，`addSeconds` 的读-改-写不会交错丢失，
  所以历史值精确等于「实际播放秒数 × 当时存活的 Activity 数」，不是模糊偏高。
- 倍率只来自打开二级页：四个 tab 是 Fragment，共用 `MainActivity` 那份 VM，日常切 tab 听歌不翻倍。
  翻倍的是开着沉浸播放页 / 搜索页 / 歌单详情 / 榜单页听歌，沉浸页最典型。
- 历史倍率无从反推（每个时刻存活的 Activity 数在 1～3 之间浮动），
  折半要假设恒定 2 倍，不准；清零则更像新 bug。听歌时长是软性数据，偏高不影响任何功能。
- 埋点不受影响：`listenEventReporter` 是 `AppContainer` 单例，`ListenTracker` 在它内部，
  不随 VM 复制，榜单数据干净。被污染的只有本机那个数字。

代码侧已在 `ListenStatsStore` 的类注释里写明这一点，避免以后有人把历史值当 bug 再「修」一次。

---

## 6. 考虑过但默认不做

| 项 | 不做的原因 |
|---|---|
| 播放页背景改用小图（`bindBackdropArt`） | 背景会更糊，改观感。另：源图很大时，全屏 centerCrop 的请求尺寸可达屏幕高度（约 2400px），如需省内存再单独决定 |
| 沉浸页光点降帧 | 改观感 |
| 推荐页先展示缓存再验证 | 改产品行为（会展示可能播不了的歌） |
| 后端离线时不发播放埋点 | 收益小，且探活状态有滞后窗口 |
| ExoPlayer audio offload | 与倍速、一起听 ±4% 纠偏冲突 |
| `play()` 的 MediaItem 构建挪到后台 | 会打乱「点播后立刻点下一首」的命令顺序 |
| 后端改动 | 当前量级不是瓶颈，见第 7 节 |

---

## 7. 已核查、不需要改

**UI**

- `LyricView`：每次 tick 只做一次二分查找，换行才更新样式；渐隐与缩放走 RenderNode 属性并带阈值。
- `MiniPlayerView.bind` 逐项比对，封面只在换歌时加载；`RingProgressView` 有 0.5% 重绘阈值。
- `SeekLine`、`TopEdgeFadeView`、`BottomEdgeFadeView` 都缓存了 Shader。
- `BottomNavBar` 的弹簧帧回调到静止就停；`invalidateGlass()` 只是重读配置并 invalidate。
- 玻璃面板静态绘制，没有逐帧循环；`ImmersivePlayerActivity` 在 `onPause` 停动画。
- `LaunchAnimationView` 一次性；`LoginBackdropView` 静态。
- item 布局最深 4 层；横滑列表均 `setHasFixedSize(true)`；推荐列表用 DiffUtil；`ListAdapter` 的 diff 在后台线程。

**数据层**

- `LocalMusicRepository` 在 IO 线程查询，投影精简。
- `LyricsRepository` 有 LRU(24)，在 IO 线程。
- `AudioArtFetcher` 主路径用 512px 缩略图。
- 网易云 / 酷狗客户端都在 IO 线程解析。
- 网络日志只在 debug 开启（HEADERS 级）。
- 账号资料有 Mutex 单飞 + 缓存。
- 一起听的轮询（心跳 2s、成员 5s、LAN 复核 15s、对时 3s、纠偏 500ms）全部挂在房间会话的 scope 上；
  WebSocket 重连指数退避，上限 15s。
- 播放埋点单消费者串行，连续失败 3 次静默 60s。

**后端（`server/business/music`）**

- 榜单有 30s 内存缓存；流水表有 `create_time`、`(song_key, create_time)` 索引；补展示字段是一次 IN 查询。
- 评论列表一条查询 + 一次计数，`(song_key, status, create_time)` 索引直接满足排序。
- 平台请求用 JDK `HttpClient` 单例（连接池、keep-alive）；搜索 4 线程并行扇出；缓存有 TTL 与 400 条上限。
- WebSocket 按会话加锁发送；房间在内存里，空房即删。
- 客户端已不再调用 `/recommend`，`RecommendService` 不在任何热路径上。
- 量上来以后可考虑：覆盖索引 `(create_time, song_key, weight)`；缓存溢出时整表 clear 改为 LRU。

---

## 8. 护栏

**表征测试（先写，锁住现有行为）**

- 批量与逐首取址：录制的平台响应下逐首判定一致。
- 队列删除 / 比对：抽成纯函数，新旧实现在随机输入上结果一致。
- 队列快照：新旧写法 JSON 逐字节相同。
- 曲库分组 / 搜索过滤：主线程版与后台版输出一致。
- 占位图：截图比对。
- 听歌时长：双 VM 场景复现 ×2。
- 现有 `testDebugUnitTest` 全绿。

**回归清单**（在仓库根 `docs/verify-checklist.md` 基础上追加）

- 千首曲库：播放全部、清空队列、拖动排序、移除
- `am kill` 后从通知栏 / 蓝牙续播
- 「歌曲推荐」模式播到队尾续流
- 一起听：建房、加入、纠偏
- 定时关闭（含播完当前）、倍速
- 通知栏红心与 App 内双向同步
- 后端离线全流程
- 快速输入搜索、歌单详情、推荐首屏
- 推荐 / 本地 tab 往返切换
- 各配色 × 深浅色

---

## 9. 度量

改前采基线，改后同条件复测；**没有收益就不合入**。

| 指标 | 方法 |
|---|---|
| 冷启动 | `adb shell am start -W -S`，10 次取中位数；Perfetto 看主线程上的 `Application.onCreate`、Service 创建、首帧 |
| 卡顿 | `adb shell dumpsys gfxinfo com.szc.learning framestats`：播放页上下滑、千首列表快速滑、切 tab、切歌 |
| 首屏内容时间 | 推荐 / 搜索 / 歌单详情：从发起请求到列表出现打点计时（仅 debug） |
| 主线程耗时 | debug 包给 `persistSnapshot`、`publish`、`render`、`CoverArt.of` 加 `Trace.beginSection` |
| 过度绘制 | 开发者选项「调试 GPU 过度绘制」 |
| 内存 | `dumpsys meminfo`：浏览封面后看 Graphics / Native |
| 耗电代理 | 后台 / 熄屏 30 分钟：`dumpsys batterystats` 唤醒次数、`dumpsys cpuinfo`、探活请求数 |
| 测试数据 | 脚本往 `/sdcard/Music` 推 2000 首带 ID3（含封面、歌词）的 mp3 |

---

## 10. 实施顺序

1. 基线度量 + 表征测试（无行为改动）
2. P0-2、P0-3 第 1 条（各几行，近乎零风险）
3. P0-5（赶在队列编辑功能提交之前）
4. P0-1
5. P1-1 → P1-7
6. P0-3 主修
7. P0-4（改动面最大；第 5 节第 1 条已拍板：历史值不动）
8. P2 按度量结果决定

---

## 附录：审计范围

- 逐文件阅读：
  - 播放链路：`PlayerController`、`PlaybackService`、`TrackMediaItem`、`QueueSnapshotStore`、
    `MusicViewModel`、`MainActivity`；
  - 全部自绘控件（`ui/widget`、`ui/glass`、`ImmersiveStageView`、`LaunchAnimationView`、`LoginBackdropView`）
    的绘制与动画路径；
  - 播放页、推荐页、本地页、我的、搜索页的订阅与渲染；
  - 图片管线：`Artwork`、`CoverArt`、`AudioArtFetcher`、`CoverImageView`；
  - 全部 Store、`OnlineSourceRouter`、`PlayableFilter`、`SourceHttp`、`SourceThrottle`、网易云 / 酷狗取址、
    `LyricsRepository`、`LocalMusicRepository`、`FeedAudioCache`、`ListenEventReporter`、`room/` 全部循环；
  - 主题与布局：窗口背景、根背景、item 嵌套深度；
  - 后端音乐模块：SQL、索引、缓存、HTTP 客户端、WebSocket。
- 需要真机实测才能定量的：`publish` 的实际频率（P1-2）、占位图单张绘制耗时（P0-3）、
  冷启动各段耗时（P2）、各项的耗电收益。
