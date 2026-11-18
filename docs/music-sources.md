# 多音源接入（Mineradio 方案移植）

> 目标：把 Mineradio 那套「一个播放器接多家音乐平台」的做法，落到
> Aurora 音乐现有的 `Android + RuoYi` 结构里。当前已接 **网易云音乐 / 酷狗音乐**，
> 打通了 **搜索 → 取播放地址 → 自动换源 → 音源管理**。

## 1. Mineradio 是怎么接音源的

Mineradio 是 Electron 桌面端，但它的网络请求并不在渲染进程里直接发，而是：

```
渲染进程（public/js）  →  本地 Node HTTP 服务（server.js）  →  各平台
```

`server.js` 里是一个手写的路由分发器（不是 Express），每个平台一个模块：

| 平台 | 实现 | 关键点 |
| --- | --- | --- |
| 网易云 | `NeteaseCloudMusicApi` 包 + `server.js` 的 netease 分支 | 登录 Cookie 存本机；搜索/歌单/歌词/取址都走同一套 cookie |
| QQ 音乐 | `server.js` 的 qq 分支 + `qq-vip-api.js` | `u.y.qq.com/cgi-bin/musicu.fcg` 取 vkey；`qq-vip-api.js` 只负责「会员权益判定」 |
| 酷狗 | `kugou-api.js` | 搜索 `song_search_v2`；取址 `getSongInfo`（md5(hash+"kgcloud")）和网页版 H5 签名；歌词 `krcs` |
| 汽水 | `qishui-api.js` + 音频解密器 | 有自己的 passport 登录与 DRM，移植成本最高 |
| Spotify | `spotify-api.js` | 后续版本已移除 |

关键设计（也是我们照搬的部分）：

1. **统一模型**：前端只认 `source + songId + title/artist/album/duration`，
   平台差异（酷狗 hash、QQ songmid、汽水 songId）收在适配器里。
2. **统一信封**：取址失败不是 HTTP 500，而是 `playable=false + category + message`。
   `category` 有 `login_required / vip_required / paid_required / trial_only /
   copyright_unavailable / provider_limited / url_unavailable`。
3. **播放失败自动换源**：`11-provider-fallback.js` 拿原曲的「歌名 + 歌手 + 时长」
   到其它平台搜同名同歌手版本，命中才切，并提示「已从 A 换到 B」。
4. **登录态只在本地**：Cookie 存在本机用户目录，不进仓库、不上传。
5. **平台能力表**：`/api/platform/capabilities` 告诉前端每个平台支持哪些操作
   （收藏/评论/歌单/上报），前端不猜。

## 2. 本工程的落点

```
Android（core:network / app）
    │  只调自家接口，不出现任何平台字段
    ▼
RuoYi 后端（server/business/music）
    ├── MusicSourceService   聚合、缓存、自动换源、配置
    ├── MusicSourceProvider  平台 SPI
    ├── NeteaseSourceProvider
    └── KugouSourceProvider
    ▼
网易云 / 酷狗
```

工程内文件：

| 文件 | 作用 |
| --- | --- |
| `server/business/music/source/MusicSourceProvider.java` | 平台 SPI：`search / resolve / lyric` |
| `server/business/music/source/NeteaseSourceProvider.java` | 网易云公开接口：搜索、`enhance/player/url`、outer 兜底、歌词 |
| `server/business/music/source/KugouSourceProvider.java` | 酷狗：搜索、移动/网页双取址、`krcs` 歌词 |
| `server/business/music/source/MusicSourceService.java` | 并行搜索、轮询合并、同名同歌手换源、TTL 缓存 |
| `server/business/music/controller/AppMusicController.java` | `/app/music/{search,play,lyric,sources}` |
| `android/core/network/music/OnlineDtos.kt` | 统一 DTO |
| `android/app/.../ui/search/SearchActivity.kt` | 在线/云端/本地三路结果 |
| `android/app/.../ui/source/SourceActivity.kt` | 音源管理页 |

### 与 Mineradio 的差异

| 项 | Mineradio | 本工程 |
| --- | --- | --- |
| 网关位置 | 本机 Node | 自建 RuoYi |
| 登录 | 扫码 / 内置登录窗 | 粘贴浏览器 Cookie（B/S 结构下更实际） |
| 音频流 | 直连平台 URL | 同样直连，不做音频代理 |
| 平台范围 | 网易云/QQ/酷狗/汽水 | 网易云/酷狗（首批） |
| 换源判定 | 歌名 + 歌手集合 + 版本词 + 时长 | 歌名（含去括号版本）+ 歌手交集 + 8 秒时长容差 |

## 3. 接口契约（已并入 `docs/api.md`）

### 3.1 聚合搜索

`GET /app/music/search?keyword=晴天&limit=20&sources=netease,kugou`

```json
{
  "code": 200,
  "data": {
    "keyword": "晴天",
    "tracks": [
      {
        "source": "netease",
        "id": "186016",
        "title": "晴天",
        "artist": "周杰伦",
        "album": "叶惠美",
        "durationMs": 269747,
        "coverUrl": "https://p1.music.126.net/...",
        "playable": true,
        "hash": null,
        "albumId": null,
        "albumAudioId": null
      }
    ],
    "errors": { "酷狗音乐": "搜索失败：..." }
  }
}
```

单平台失败只体现在 `errors` 里，不影响 `tracks`；App 会在「在线」标题后追加
「XX 暂不可用」，列表照常展示另一个平台的结果。

> 网易云的搜索接口只给 `album.picId`，不给 `picUrl`。服务端按网易云 CDN 规则
> 用 `picId` 拼出 `https://p1.music.126.net/{encrypt(picId)}/{picId}.jpg`，
> 否则 App 里的网易云结果会全是灰块。`encrypt` 是 picId 与固定串逐位异或后
> 取 MD5、再 Base64（`/`→`_`、`+`→`-`），只影响封面，不涉及播放鉴权。

### 3.2 取播放地址

`GET /app/music/play?source=netease&id=186016&title=晴天&artist=周杰伦&durationMs=269747&fallback=true`

成功：

```json
{
  "code": 200,
  "data": {
    "source": "netease",
    "id": "186016",
    "url": "https://m701.music.126.net/....mp3",
    "playable": true,
    "quality": "exhigh",
    "switchedFrom": null,
    "switchedTo": null
  }
}
```

失败（仍是 HTTP 200）：

```json
{
  "code": 200,
  "data": {
    "source": "netease",
    "id": "210049",
    "playable": false,
    "category": "vip_required",
    "message": "网易云这首歌需要会员权限"
  }
}
```

自动换源：

```json
{
  "code": 200,
  "data": {
    "source": "kugou",
    "id": "8909E1...",
    "url": "https://sharefs.kugou.com/....mp3",
    "playable": true,
    "switchedFrom": "netease",
    "switchedTo": "kugou"
  }
}
```

酷狗取址需要把搜索时拿到的 `hash / albumId / albumAudioId` 原样带回；
网易云只用 `id`。

### 3.3 音源管理

`GET /app/music/sources`

```json
{
  "code": 200,
  "data": [
    { "key": "netease", "label": "网易云音乐", "enabled": true, "priority": 10, "hasCookie": false },
    { "key": "kugou", "label": "酷狗音乐", "enabled": true, "priority": 20, "hasCookie": true }
  ]
}
```

`PUT /app/music/sources/{key}`，三个字段都可空：

```json
{ "enabled": true, "priority": 10, "cookie": "MUSIC_U=..." }
```

- `cookie` 传空串 = 清除
- 响应永远不返回 cookie 原文，只返回 `hasCookie`

### 3.4 歌词

`GET /app/music/lyric?source=netease&id=186016` → `data` 是 LRC 文本（可能为空串）。
App 的正在播放页显示第一句作为预览，点一下看全文；本地/云端仍走 ID3 解析。
换源后的曲目用的是**新平台**的 `source/id`，歌词也按新平台取。

## 4. 使用步骤

1. 建表（只需一次）：

   ```bash
   MYSQL="/c/Program Files/MySQL/MySQL Server 26.10/bin/mysql.exe"
   "$MYSQL" --default-character-set=utf8mb4 -uroot -p ry-vue < sql/06_music_source.sql
   ```

   Windows 下 MySQL 客户端默认字符集可能不是 utf8mb4，漏了
   `--default-character-set=utf8mb4` 会报 `Data too long for column 'label'`。

2. 同步业务代码并重启后端（见 `server/business/README.md`）。

3. App「我的 → 音源管理」里确认两个平台已启用；需要会员曲目时填入对应平台的 Cookie。

4. 搜索页输入 ≥2 个字：先出「在线」结果（带网易云/酷狗胶囊），后面接「我的音乐」。

### Cookie 怎么拿

App 内已经做了半自动登录：**我的 → 音源管理 → 点「登录 Cookie」→ 登录获取（推荐）**。
弹出的网页就是平台自己的登录页（网易云支持手机号/二维码，酷狗点右上角登录），
登录成功后 App 从 WebView 的 Cookie 罐里读取登录票据，自动保存到音源配置并返回。
账号密码只在平台页面里输入，App 不读取、不转发；Cookie 只发给我方后端，
接口永远只回 `hasCookie`，不回 Cookie 原文。

- 只读取白名单里的鉴权/设备 Cookie（网易云 `MUSIC_U` 等，酷狗 `KuGoo/kg_mid/token` 等），
  不把统计 Cookie 一起带走。
- 换账号或登录态失效：重新进登录页，点右上角「清除登录状态」后再登录。
- 仍然保留「手动粘贴 Cookie」作为兜底：浏览器登录对应平台 → F12 → Network →
  任意请求 → 复制请求头里的 `Cookie` 整段。
- 只填自己账号的，不要共用；泄露等于把账号交出去。

## 5. 已知边界

- **匿名请求只能播免费曲目**。网易云对会员曲返回 `code=-110`，酷狗对付费曲返回
  `需要付费`；填 Cookie 后由平台自己决定给不给地址，服务端不做任何绕过。
- 平台接口会变。改一个平台只需要动对应的 `*SourceProvider`，聚合层和客户端不用改。
- 搜索结果不落库，播放地址只缓存 10 分钟（平台 URL 本身有时效）。
- 自动换源只换「录音版本一致」的曲目，不对 Live/伴奏/翻唱做模糊匹配。
- 在线结果目前**点一首播一首**（单曲入队）。换源会把 `source/id` 换成新平台，
  整页结果不能直接当播放队列用；要做连续播放，需要在取址层做惰性 `MediaSource`
  或在播完时回调服务端再取下一首。
- 当前不代理音频流：如果某个 CDN 以后加了 Referer 校验，需要在后端加一个
  白名单音频代理，或在 ExoPlayer 侧加请求头。

## 6. 合规

本项目不是任何音乐平台的官方客户端。多音源接入仅用于个人学习与自有账号的
播放辅助，不提供绕过付费、破解音质、重新分发音乐内容的能力。
请遵守各平台用户协议与版权规则；公开部署前请自行评估所在地区的法律要求。