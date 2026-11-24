# MEMORY.md — Aurora 音乐播放器项目长期约定

> 2026-11-17 起，项目由「外卖点餐」整体转为 **Aurora 音乐播放器**（期末答辩项目）。
> 外卖时代的 app 端代码已全部移除，只在 `master` 的 118a4be 之前的历史里。

## 当前形态
- App：本地曲库（MediaStore）+ 云端曲库（若依 `/app/music/tracks`）+ 在线音源（网易云/酷狗）三源
- 页面：**四个 tab：播放 / 推荐 / 本地 / 我的**，默认落推荐页。首页已于 2026-11-18 下线
- 播放页（2026-11-24 起）：**当前播放队列的镜像**，不再是进页面就实时推荐的刷歌流。
  `MusicViewModel.startFeed()` 优先把 `player.queue` 原样入流；外部点播后切回播放页
  由 `adoptQueueIfChanged()` 同步。只有切到**第 5 播放模式「歌曲推荐」**（`PlayMode.RECOMMEND`）
  且队列播到队尾（`hasNext==false`）才追加实时推荐；其余 4 态完全不推荐。
  历史上（11-18~11-24）是"刷歌流"，那段描述已作废。
- 播放模式现为 **5 态互斥**：顺序 → 列表循环 → 单曲循环 → 随机 → 歌曲推荐。
  第 5 态是 media3 两开关表达不了的第三态，靠 `PlayerUiState.recommendMode` 单独记；
  底层映射 REPEAT_MODE_OFF 才有明确队尾事件。`publish()` 整份重建 state 时必须把
  recommendMode 从旧 state 带过去，否则被 media3 回调冲掉。
- 音频缓存：`FeedAudioCache`（SimpleCache 256MB，`cacheDir/feed_audio`），播放边写盘 + 预取后两首，
  **不跨会话**（构造时删目录 + Service onDestroy 清）
- 音源歌单：点卡片进 `PlaylistDetailActivity` 看曲目，不再是一点即播
- 推荐：客户端算口味歌手 → 后端 `/app/music/recommend` 聚合搜索 + 平台推荐流；结果本地缓存兜底
- 收藏：本机（存完整曲目）→ 后端 `biz_music_user_like`（按 deviceId）→ 平台（仅网易云）
- 同步：**静默**（`MainActivity.onStart` + 推荐页/我的页，TTL 5 分钟），只有手动点同步才 toast
- 设计：**半透明面板（纯染色）+ 极光底**，浅色优先，深色可用。
  2026-11-18 一天里退了两级：iOS 液态玻璃（AGSL 折射+色散+触摸光斑）→ 高斯模糊
  （RenderNode 录制 + RenderEffect）→ **纯半透明染色**。最后一步是因为实测证明
  「背后内容实时采集」本身就是这台机器帧预算里最贵的那一项，换模糊算法救不回来。
  现在 `GlassSurfaceView` 不再有 `backdropSource`，`GlassBackdrop.kt` 已删，
  `GlassSpec` 只有 `frost`（**0~1 全域放开**，含义是染色浓度/不透明度，
  映射见 `GlassPainter`：浅色 0.45~0.96，深色 0.12~0.38），面板不透出背后内容
- 播放内核：media3 ExoPlayer + MediaSessionService（后台播放、通知栏、锁屏控制）
- 分支：`feat/music-glass` 是当前开发线；master 已收拢全部旧分支

## 玻璃 / 模糊效果的平台事实（2026-11-18 查证，影响后续技术选型）

- **Android 没有「同窗口 backdrop blur」的公共 API**。`RenderEffect.createBlurEffect` 只能模糊
  view **自身内容**，摸不到它背后的东西。想在同一个窗口里做出「背后内容透过玻璃」，
  只有一条路：把背后的 view 树录进 `RenderNode`，再挂 RenderEffect —— 也就是我们现在的做法。
- **跨窗口模糊是另一回事**：`Window#setBackgroundBlurRadius()` / `FLAG_BLUR_BEHIND`
  （API 31+）由 SurfaceFlinger 在合成阶段做，几乎零成本，但**只能模糊窗口下面的其他窗口**，
  不能模糊同一窗口内的内容。所以底部导航/迷你播放器这种"内容浮在内容上"的场景用不上，
  除非把它们做成独立窗口（Dialog / Popup），代价是动画和触摸都要自己管。
- **iOS 的 UIVisualEffectView 是合成器级能力**（Core Animation / Metal 在出帧时连续合成，
  layer 树节点级），app 只声明区域。这是架构差异，不是写法差异 ——
  中文社区复刻 iOS 液态玻璃的实测结论原话：「Android 不具有渲染引擎级的 backdrop 能力，
  手动捕获背景 bitmap 相对低效……这个是渲染架构的区别，没有可以解决的预期」。
  该作者每帧生成 SDF 的方案在高端机只有 **15~20fps**。
- **主流音乐 App 的毛玻璃基本是静态模糊图**：网易云播放页背景 = 封面图缩小 + StackBlur
  生成一张图，滚动/切歌之外根本不更新；QQ 音乐同理。所以它们"不卡"——
  因为它们做的不是实时模糊。这是最省的路，也是最常见的路。
- 真做实时 backdrop blur 的开源方案（haze、expo-blur、RN Liquid Glass）共同的三个招：
  **① 降采样捕获（captureScale 2~4）② 内容哈希脏检查（内容没变就不重绘）
  ③ 按 GPU 能力分级砍效果**。haze 还特别提示 clipPath 比 clipRect 慢很多。

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
- **轻提示一律 `Context.toast(...)` / `HudToast.show(...)`，不用系统 `Toast`**：
  系统 Toast 排队播放，连点会积压十几秒；`HudToast` 一页只复用一个胶囊，新提示直接打断旧提示
- **「命令 + 回执」不要回头读异步 state**：`MediaController` 是跨进程的，setter 后 state 要等
  binder 回调才更新，当帧读到的还是旧值。让命令方法返回新值（如 `cyclePlayMode(): PlayMode?`）
- **可点容器的背景一律用 `@drawable/bg_ripple_card`（20dp）/ `bg_ripple_row`（12dp），
  禁止 `?attr/selectableItemBackground`**：系统那个是矩形 ripple，长按会露出一块直角灰底，
  和封面圆角对不上。图标按钮用 `...Borderless`（无界圆形）没问题。
  ripple 的 mask 圆角必须引用 `@dimen/radius_lg` / `@dimen/radius_row`，与 `Shape.Cover.*` 同源

## 贪吃蛇 demo
- 已从构建摘除：`settings.gradle.kts` 的 `include(":feature:demo")` 是注释状态
- 恢复方式：取消该行 + 取消 `app/build.gradle.kts` 里对应依赖注释
- `SnakeRules` 是重写版（原文件被误删），行为以 `SnakeRulesTest` 的 8 条用例为准

## 离线优先架构（2026-11-18 起，已定型）

**App 在后端关闭时必须完整可用**，后端只提供「客户端物理上做不到」的能力。

- 音源配置（开关/优先级/Cookie）存客户端 `SourceConfigStore`，不再走 `/app/music/sources`。
  `PUT /sources/{key}` 已 @Deprecated，只在首次初始化时读一次后端配置
- 在线音源**全部依赖后端**（2026-11-18 更新，旧记录「酷我可客户端直连」已作废）：
  酷我客户端直连因 anti.s 接口失效（返回「请下载酷我 App」提示音）已**全链路停用** ——
  不搜、不播、不取歌词；后端 `/search` 返回的 kuwo 曲目也要在 `OnlineSourceRouter.search()`
  里整批剔掉，否则用户听到的是广告（URL 合法但内容是提示音，字符串校验挡不住）。
  网易云/酷狗/QQ 只有后端实现。**后端不可达 = 在线音源完全不可用**，
  `search()` 返回空 + `OFFLINE_HINT_NO_SOURCE`，刻意诚实降级，不假装没搜到
- 推荐离线降级：`LocalRecommendEngine` **只从本机曲库 + 收藏出歌**，不再做在线搜索；
  种子歌手优先、其余按当天 seed 洗牌，**不排除已红心**（本机模式下收藏就是曲库来源）。
  播放页 `fallbackFeed()` = 上次推荐缓存 + 本机/云端曲目 + 收藏。
- 收藏：本机为主，后端为备份；`syncLike` / `syncLibrary` 先探活，离线直接跳过，不弹「同步失败」
- **统一入口是 `OnlineSourceRouter`**，不要在 ViewModel 里再写 if 判断后端在不在
- **音源登录 Cookie 走「请求级透传」，不落后端库**（2026-11-18 定）：
  本机 `SourceConfigStore.cookiesJson()` → 在线请求的 `cookies` 查询参数 →
  后端 `RequestCookies.parse()` 只用于本次调用。改动这条链路前先读
  `server/business/music/source/RequestCookies.java` 的注释。
  `MusicSourceService` 的 search/play 缓存 key **必须带 Cookie 指纹**，
  否则游客态搜过一次、登录后搜同样的词会命中旧缓存。
  新旧端兼容：新端+旧后端，多余参数被忽略；旧端+新后端，回退库配置。
- 探活用 2.5s 超时的独立 OkHttp，打 `/app/music/sources`；在线 45s / 离线 15s 各探一次。
  **不能靠「请求失败了才降级」** —— `ApiClient` 的 connect timeout 是 15 秒，那样点一次搜索要等 15 秒
- **分享码是唯一必须依赖后端的功能**（短码要中心分配）。它的降级策略是**明确失败**，
  本地伪造一个码会让对方查出「码不存在」，比当场失败更糟
- **歌曲评论是第二个必须依赖后端的功能**（多人共享数据）。同样明确失败，不本地伪造。
  评论按 `songKey`（归一化「歌名|主歌手」）归属，不是平台 track_id —— 同一首歌在
  网易云和酷我共享评论区。归一化只在后端 `MusicCommentService.songKey` 做一处。
  **括号里的版本标识参与匹配**：`晴天 (Live)` 与 `晴天` 是两条独立评论流
  （与 `RecommendService` 去重规则一致）

## 四条踩过的崩溃 / UI 硬规则

1. **Gson 的 `getAsJsonObject(key)` / `getAsJsonArray(key)` 遇到 `"key": null` 会抛
   `ClassCastException`，不返回 null**。第三方接口限流时经常返回 `{"data":null}`。
   一律用 `objectOrNull` / `arrayOrNull`（先判 `isJsonObject` / `isJsonArray`）。
   已经因此崩过一次：酷我歌词 data:null → 一进播放页闪退
2. **降级路径（歌词、封面、推荐兜底）整体包 `runCatching`**。这些边缘功能不该有能力
   弄崩页面 —— 少一个歌词最多是「暂无歌词」，漏一个异常就是闪退
3. **`BottomSheetDialog.setContentView` 会把根布局 layoutParams 改写成
   MATCH_PARENT × WRAP_CONTENT**，XML 里的 `match_parent` 不算数；
   `isFitToContents` / `expandedOffset` 在 show 前后设都会被重新测量覆盖。
   要固定高度就用 `minimumHeight`。另外 **ADJUST_RESIZE 与 IME insets padding
   只能二选一**，叠加会把内容压成 0 高度
4. **绝不对 RecyclerView 的 child 做「先写 alpha=0 + 延迟动画归位」的入场**。
   child 是 ViewHolder.itemView，生命周期归 RV 管，后续 submitList 会重新绑定/拆装，
   动画中途被打断后 alpha=0 永久留下 → 那几行永远透明不见（2026-11-18 推荐页踩过：
   只有前 6 行消失，第 7 行往后正常，因为 `ROW_ENTRANCE_MAX=6` 只碰了前 6 个 child）。
   正解：要么动画整个容器（推荐页已改成整列一次 springEnter），要么在 Adapter.onBind
   里用 payload/标志位驱动一次性动画。另外 `springEnter` 已加超时归位兜底。
5. **ViewModel 顶部 `init` 块里不能读后面声明的 StateFlow**（2026-11-24 踩过）：
   Kotlin 属性按源码顺序初始化，类顶部 init 执行时后声明的字段还是 null，
   collect 一触发就 `MutableStateFlow.getValue() on null`，表现为启动即「屡次停止运行」。
   观察器要放到它依赖的那些字段**声明之后**另开 init 块。

## 本机联调（已定型）
- 默认账号 `admin` / `（若依默认口令）`（若依超管）
- **`10.0.2.2:8080` 在本机连不通**（App 侧 15s connect timeout，宿主机 curl 正常）。
  稳定走法：`-PapiBaseUrl=http://127.0.0.1:8080/` 打包 + `adb reverse tcp:8080 tcp:8080`
- `assembleDebug` 不装包，改完必须 `adb install -r`
- `ruoyi.profile` = `C:/ruoyi/uploadPath`（**不要放 D:**，那是可移动盘，抽走就 500）
- Redis 便携版已关 RDB 快照（`save ""`），否则 MISCONF 会把写命令全禁掉、若依起不来
- 打包前先杀 java 进程，否则 `ruoyi-admin.jar` 被占用、`mvn package` rename 失败
- 云端曲目 `url` 是站内相对路径，客户端必须补 baseUrl 再交给 ExoPlayer

## 后端源码有两份，改完必须 cp（2026-11-18 踩过）
- `server/business/music/` 是**权威工作副本**；`server/RuoYi-Vue/ruoyi-admin/src/main/java/com/ruoyi/music/`
  是编译用的副本，两者不会自动同步
- 只改前者就 `mvn package` → 编出来的 jar 是旧逻辑。已验证过一次：Controller 差 5 个接口、
  10 个 domain/service/mapper 文件完全缺失，`biz_music_user_like` 表也没建
- 必须：`cp -r server/business/music/. server/RuoYi-Vue/ruoyi-admin/src/main/java/com/ruoyi/music/`
  → 杀 java → `mvn -B -DskipTests package` → 重启
- **症状识别**：接口返回若依标准 401「请求访问：xxx，认证失败」。别急着查 token ——
  若依的 `PermitAllUrlProperties` 只收集真实注册过的方法，**接口不存在时也报 401**（Security
  先于 DispatcherServlet 拦截）。用 `javap -p` 比对 jar 里 Controller 的方法数最快
- 数据库 `ry-vue`，账号 `root` / 口令见本机 `application-druid.yml`；`sql/07_music_user.sql` 建 `biz_music_user_like`

## 操作纪律（本机特有）
- `adb` server 活不过一次 Bash 调用，安装/截图必须写在**同一个脚本**里跑完
- `MSYS_NO_PATHCONV=1` 时传给 `adb.exe` 的本地路径要用 `C:/` 形式
- 模拟器默认中文输入法，`adb shell input text` 前先 `ime set` 到 AOSP 键盘
- **动目录前先 `git status`**：untracked 文件不会被 git 保护，`rm -rf` 直接没
- **adb 调试截图不要放桌面**：一律输出到 `%TEMP%` 或项目内 `.verify-shots/`，验证完即清。
  曾一次在桌面留下 29 张 `_p2_*.png`（6 MB），事后要专门清理。
- `MainActivity` 若 `exported=false`，`adb shell am start` 会被拒；从 launcher intent
  或 exported 的入口页进，session 有效时会自动跳主页
