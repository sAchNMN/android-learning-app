# 云端曲库接入

App 端已经全部就绪：`MusicRepository` 拉 `/app/music/tracks`，ExoPlayer 直接流式播放返回的
`url`。剩下的只有后端三步：建表、放文件、放行接口。

## 1. 建表

```bash
mysql -uroot -p ry-vue < sql/05_music_schema.sql
```

表 `biz_music_track` 只存元信息和**相对路径**（`/profile/music/01.mp3`），
不存绝对地址 —— 换域名或挂 CDN 时不用动数据。

## 2. 放音频文件

若依的 `application.yml` 里有 `ruoyi.profile`。**本机已改成 `C:/ruoyi/uploadPath`**：
原来指向的 `D:` 是可移动盘，盘一抽走 `/profile/**` 就 500（实测过）。
把音频放进 profile 的 `music/` 子目录：

```
$ruoyi.profile/music/01.mp3 ... 10.mp3
```

若依自带 `ResourcesConfig` 把 `/profile/**` 映射到这个目录，所以
`http://127.0.0.1:8080/profile/music/01.mp3` 直接可访问，**并且支持 Range 请求** ——
拖进度条、断点续传都是 Spring `ResourceHttpRequestHandler` 负责的。
自己写 `ServletOutputStream` 抄一遍只会把这些能力丢掉。

演示用的 10 首是从 `C:\Users\34759\Music` 的 FLAC 转成 192k mp3 的（文件名与库里的
`title`/`artist`/`duration_ms` 一一对应，见 `sql/05_music_schema.sql` 的 INSERT）。

## 3. 放行接口（已在代码里做好）

若依默认 `anyRequest().authenticated()`，`/app/music/**` 会被拦成 401。
但**不需要改 `SecurityConfig`**：`AppMusicController` 类上已有 `@Anonymous`，
若依的 `PermitAllUrlProperties` 启动时会自动把它的路径加进放行列表。

这样放行规则跟着业务代码走 —— `server/RuoYi-Vue/` 是 gitignore 的第三方源码，
改了 `SecurityConfig` 重新 clone 就丢，`@Anonymous` 不会。

曲库是公开数据，不带登录态更符合语义，也免得答辩现场因为没登录一片空白。

## 4. App 侧不用改

`BuildConfig.BASE_URL` 指向后端即可。本机联调：

```bash
adb reverse tcp:8080 tcp:8080
./gradlew installDebug -PapiBaseUrl=http://127.0.0.1:8080/
```

后端没起来时，云端页只显示"连不上云端"，不崩溃、不白屏 —— 这是刻意的降级。

**`url` 必须补成绝对地址再交给播放器**（`MusicRepository.absolute()`）。
后端下发的是 `/profile/music/01.mp3` 这种站内相对路径，原样传给 ExoPlayer 会被当成
**本地文件路径**，报 `FileNotFoundException: /profile/music/01.mp3` —— 这一条是实跑时踩到的。

## 已知边界

- 目前只有"全量列表"一个接口。分页、搜索、上传都还没做：曲库规模在几百首以内时，
  一次拉全量比做分页更省事，也不会有"翻到第 3 页才搜得到"的割裂感。
- 封面走 `cover` 字段的相对路径，为空时客户端用 `CoverArt` 按曲目哈希生成渐变图，
  不会出现灰色占位块。
- 歌词可选：接口可以额外下发 `lyrics` 字符串，客户端有则直接显示；
  没有时不会去请求额外接口，播放器页显示「没有找到内嵌歌词」。
  本地 MP3 的 `USLT` / `SYLT` 歌词由客户端自行解析，不经过服务端。
