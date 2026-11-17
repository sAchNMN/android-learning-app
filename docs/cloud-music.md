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

若依的 `application.yml` 里有 `ruoyi.profile`（默认 `D:/ruoyi/uploadPath`）。
把音频放进它的 `music/` 子目录：

```
$ruoyi.profile/music/01.mp3 ... 08.mp3
```

若依自带 `ResourcesConfig` 把 `/profile/**` 映射到这个目录，所以
`http://127.0.0.1:8080/profile/music/01.mp3` 直接可访问，**并且支持 Range 请求** ——
拖进度条、断点续传都是 Spring `ResourceHttpRequestHandler` 负责的。
自己写 `ServletOutputStream` 抄一遍只会把这些能力丢掉。

## 3. 放行接口

若依默认 `anyRequest().authenticated()`，`/app/music/**` 会被拦成 401。
在 RuoYi-Vue 的 `SecurityConfig` 里，紧挨着已有的匿名规则加一行：

```java
.antMatchers("/app/music/**").permitAll()
```

曲库是公开数据，不带登录态更符合语义，也免得答辩现场因为没登录一片空白。

## 4. App 侧不用改

`BuildConfig.BASE_URL` 指向后端即可。本机联调：

```bash
adb reverse tcp:8080 tcp:8080
./gradlew installDebug -PapiBaseUrl=http://127.0.0.1:8080/
```

后端没起来时，云端页只显示"连不上云端"，不崩溃、不白屏 —— 这是刻意的降级。

## 已知边界

- 目前只有"全量列表"一个接口。分页、搜索、上传都还没做：曲库规模在几百首以内时，
  一次拉全量比做分页更省事，也不会有"翻到第 3 页才搜得到"的割裂感。
- 封面走 `cover` 字段的相对路径，为空时客户端用 `CoverArt` 按曲目哈希生成渐变图，
  不会出现灰色占位块。
