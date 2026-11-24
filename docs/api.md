# 接口契约

本文件是 Android 端与若依后端的**唯一约定来源**。
任何一方改字段，先改这里，再改代码。

## 1. 通用约定

| 项 | 约定 |
| --- | --- |
| Base URL | `http://<host>:8080/`（若依默认端口，context-path 为空） |
| 请求体 | `Content-Type: application/json`，字段名**驼峰** |
| 响应体 | 统一 `AjaxResult` 信封，见下 |
| 成功判定 | **`code == 200`**（不是 0，也不要只看 HTTP 状态码） |
| 认证头 | `Authorization: Bearer <token>`，注意 `Bearer ` 前缀和空格 |
| 时间格式 | `yyyy-MM-dd HH:mm:ss`，字符串 |
| 分页 | 请求 `pageNum` / `pageSize`，响应 `{ total, rows }` |

### 成功响应

```json
{
  "code": 200,
  "msg": "操作成功",
  "data": { }
}
```

### 失败响应

```json
{
  "code": 500,
  "msg": "用户不存在/密码错误"
}
```

**关键坑**：若依的「未登录 / token 失效」返回的是 **HTTP 200 + body `code: 401`**，
不是 HTTP 401。所以 Android 端必须解析响应体判定登录态，
只校验 HTTP 状态码会把「登录过期」误判成「请求成功」。

## 2. 认证接口

### 2.1 获取验证码

`GET /captchaImage`（无需认证）

```json
{
  "code": 200,
  "captchaEnabled": true,
  "uuid": "9a1f1a0c8f2d4e5b8c7d6e5f4a3b2c1d",
  "img": "iVBORw0KGgoAAAANSUhEUg..."
}
```

- `captchaEnabled=false` 时后端已关闭验证码，登录请求不用带 `code` / `uuid`
- `img` 是不带前缀的 Base64 PNG

### 2.2 登录

`POST /login`（无需认证）

```json
{
  "username": "admin",
  "password": "admin123",
  "code": "3",
  "uuid": "9a1f1a0c8f2d4e5b8c7d6e5f4a3b2c1d"
}
```

响应（**token 平铺在顶层，不在 data 里**）：

```json
{
  "code": 200,
  "msg": "操作成功",
  "token": "eyJhbGciOiJIUzUxMiJ9...",
  "expires_in": 720
}
```

### 2.3 获取当前用户

`GET /getInfo`（需认证）

```json
{
  "code": 200,
  "msg": "操作成功",
  "user": {
    "userId": 1,
    "userName": "admin",
    "nickName": "若依",
    "avatar": "",
    "email": "ry@163.com",
    "phonenumber": "15888888888",
    "sex": "1",
    "status": "0",
    "createTime": "2024-01-01 00:00:00"
  },
  "roles": ["admin"],
  "permissions": ["*:*:*"]
}
```

`user` / `roles` / `permissions` 都是顶层字段，**不在 `data` 里**。

### 2.4 退出登录

`POST /logout`（需认证）

```json
{ "code": 200, "msg": "退出成功" }
```

服务端会删除 Redis 中的 token。客户端无论成功失败都应清本地会话。

## 3. 业务接口

### 3.1 外卖点餐

统一用 `/app/**` 前缀，与若依自带的 `/system/**`、`/monitor/**` 隔离。
用户身份从 token 里取（`SecurityUtils.getUserId()`），**不接受前端传 userId**，避免越权。

| 方法 | 路径 | 说明 | 入参 |
| --- | --- | --- | --- |
| GET | `/app/shop/list` | 营业中的店铺 | — |
| GET | `/app/shop/{shopId}` | 店铺详情 + 在售菜品 | — |
| POST | `/app/order/create` | 下单 | `{shopId, items:[{dishId,quantity}], receiver, phone, address, remark}` |
| GET | `/app/order/list` | 我的订单（按下单时间倒序） | — |
| GET | `/app/order/{orderId}` | 订单详情 + 明细 | — |
| POST | `/app/order/{orderId}/pay` | 模拟支付 | — |

订单状态：`0` 待支付 / `1` 已支付 / `2` 配送中 / `3` 已完成 / `4` 已取消。
当前只实现了 `0 → 1`（模拟支付），`2/3/4` 留给后台改状态。

下单会做四件事，全在一个事务里：
校验菜品属于该店铺 → 校验达到起送价 → 插订单与明细（菜名单价快照）→ 累加菜品销量。

### 3.2 约定

1. 返回值统一 `AjaxResult` 信封（`code`/`msg`/`data`），Android 端的统一解包逻辑不用改。
2. 金额一律后端用 `BigDecimal`、响应体是数字，客户端用 `Double` 接收。
3. 时间字段在 SQL 里 `date_format` 成 `yyyy-MM-dd HH:mm:ss` 字符串，避免时区与格式歧义。

### 3.3 在线音源（网易云 / 酷狗 / 酷我 / QQ 音乐聚合）

完整设计与 Mineradio 的对照见 `docs/music-sources.md`，这里只列契约。

`source` 取值：`netease`、`kugou`、`kuwo`、`qq`。

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/app/music/search?keyword=晴天&limit=20&sources=netease,kugou,kuwo,qq` | 聚合搜索，单平台失败只进 `errors` |
| GET | `/app/music/play?source=netease&id=186016&title=晴天&artist=周杰伦&durationMs=269747&fallback=true` | 换取播放地址，可自动换源 |
| GET | `/app/music/lyric?source=netease&id=186016` | LRC 文本，可能为空串 |
| GET | `/app/music/sources` | 音源列表（不回传 Cookie，只给 `hasCookie`）。**客户端已改为本机存，此接口只在首次初始化时用一次** |
| PUT | `/app/music/sources/{key}` | 改 `enabled` / `priority` / `cookie`。**已弃用**：配置改存客户端 `SourceConfigStore`，Cookie 不再经过服务器 |

约定：

1. `/play` 失败时 HTTP 仍是 200，`data.playable=false`，用 `category` +
   `message` 表达原因（`login_required / vip_required / paid_required /
   trial_only / copyright_unavailable / url_unavailable`）。
2. 自动换源成功时 `data.switchedFrom` / `data.switchedTo` 非空，客户端 toast 提示。
3. 酷狗取址需要把搜索返回的 `hash / albumId / albumAudioId` 原样带回；
   酷我只需 `id`（形如 `MUSIC_228908`）；QQ 音乐只需 `id`（即 songmid）；
   网易云只需 `id`。
4. 搜索结果不落库；播放地址缓存 10 分钟，失败结果缓存 30 秒。
5. 四个平台的匿名可播率差别很大，这是平台策略不是 bug：
   酷我最高（几乎都能取到地址），酷狗次之，网易云大量曲目要会员，
   QQ 音乐最严（热门歌 `payplay=1`，匿名取址返回空 purl，必须自己填 Cookie）。

### 3.4 推荐与「我的」同步

推荐页与「我喜欢」用的四个接口。App 没有登录体系，**`deviceId` 就是用户身份**
（客户端首次启动生成的 UUID），所以这几个接口不读 token，全在 `@Anonymous` 下。

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/app/music/recommend?deviceId=&artists=周杰伦,陈奕迅&seed=&limit=20` | 猜你喜欢：按口味歌手搜各音源 + 补平台推荐流 |
| POST | `/app/music/like` | 喜欢/取消喜欢，`{deviceId, source, id, title, artist, album, durationMs, cover, hash, albumId, albumAudioId, liked}` |
| GET | `/app/music/likes?deviceId=&limit=200` | 我喜欢的曲目（重装后恢复） |
| GET | `/app/music/playlists` | 各平台账号里的歌单（未登录的平台跳过） |
| GET | `/app/music/playlist?source=netease&id=123&limit=200` | 拉某个歌单的曲目 |

推荐算法（`RecommendService`）按顺序做五件事：

1. 取口味种子：客户端传的 `artists` 优先，没传就用后台喜欢表里「喜欢最多的歌手」
2. 用种子歌手当关键词走聚合搜索，天然同歌手/同风格
3. 不足条数时补各平台推荐流（网易云 `/api/personalized/newsong`，公开接口，无 Cookie 可用）
4. 去重（歌名 + 主歌手）并排除已经喜欢过的歌
5. 按 `seed` 稳定洗牌：不传按天固定，同一天多次进入顺序一致；点「换一批」传新 seed

`/like` 的返回要分两层看：

```json
{ "code": 200, "data": {
    "liked": true,
    "saved": true,
    "syncs": [{ "source": "netease", "ok": false, "message": "网易云未登录，已只记在本机与后台" }]
} }
```

- `saved` = 后端 `biz_music_user_like` 是否写入
- `syncs` = 各平台的同步回执，`ok=false` **不是接口失败**
- 规则：后端库先写，平台同步后补；平台没登录/风控失败都不影响「点红心」这个动作

平台能力现状（`MusicSourceProvider` 的默认方法决定，加平台时只覆写需要的）：

| 能力 | 网易云 | 酷狗 |
| --- | --- | --- |
| 推荐流 `dailyRecommend` | ✅ `/api/personalized/newsong` | — |
| 同步喜欢 `like` | ✅ `/api/radio/like`（需 Cookie + csrf_token） | — |
| 我的歌单 `myPlaylists` | ✅ `/api/user/playlist` | — |
| 歌单曲目 `playlistTracks` | ✅ `/api/v6/playlist/detail` | — |

酷狗这几项要签名/token 才能调，没有可靠公开接口，所以留空实现：
客户端会显示「酷狗暂不支持同步喜欢，已记在本机与后台」，**不假装成功**。

### 3.5 歌单分享码

**这是全项目唯一必须依赖服务端的功能**：短码要在中心分配，别的设备才查得到。
收藏、推荐、音源配置都已下沉到客户端，只有这一件事客户端做不到。

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/app/music/share` | 生成 6 位码，body `{deviceId, title, tracks:[…]}` |
| GET | `/app/music/share/{code}` | 按码取歌单，不存在/已过期都返回 `code != 200` |

生成响应：

```json
{ "code": 200, "data": {
    "code": "A7K2M9",
    "title": "我喜欢",
    "trackCount": 12,
    "createTime": "2026-11-18 15:02:11",
    "expireTime": "2026-12-18 15:02:11",
    "viewCount": 0,
    "tracks": [ { "id": "MUSIC_228908", "title": "晴天", "source": "ONLINE", "sourceKey": "kuwo", "hash": "MUSIC_228908", "…": "…" } ]
} }
```

约定：

1. **`tracks` 原样存、原样返回**，后端不解析内部字段。跨音源曲目带 `hash` /
   `albumId` / `albumAudioId` 这些平台私有参数，后端一旦理解这套结构，
   客户端每加字段都要改两边。
2. 码是 6 位，字符集 `23456789ABCDEFGHJKMNPQRSTUVWXYZ`（**去掉 0/O/1/I/L**，
   用户会口头念码、手抄码）。唯一索引 + 撞码重试保证不重。
3. 上限：单次 200 首（超出截断不报错）、payload 512KB、有效期 30 天。
4. `source` 用字符串 `ONLINE` / `CLOUD` / `LOCAL`，**不用枚举序号** ——
   序号会随枚举顺序变化，是跨版本协议最容易踩的坑。
5. 客户端提交前会过滤掉 `LOCAL` 曲目：`content://` 指的是分享者手机上的文件，
   对方拿到只会是一首点开就报错的歌。
6. 导入方把结果落在本机 `SharedPlaylistStore`，之后**离线也能看到自己导入过什么**；
   码本身仍在服务端。

### 3.6 歌曲评论

第二件**必须依赖服务端**的功能。评论是多人共享的数据，单机的「评论」只能自言自语。

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/app/music/comments?title=&artist=&deviceId=&limit=20&offset=0` | 列表，带本人的 `liked` / `own` |
| POST | `/app/music/comments` | 发表，见下方 body |
| POST | `/app/music/comments/{id}/like` | 点赞 / 取消，body `{deviceId, liked}` |
| DELETE | `/app/music/comments/{id}?deviceId=` | 只能删自己的 |

发表 body：

```json
{ "deviceId": "a1b2c3", "deviceName": "V2419A", "title": "晴天", "artist": "周杰伦",
  "sourceKey": "kuwo", "trackId": "MUSIC_228908", "content": "前奏一响就是整个夏天" }
```

列表响应：

```json
{ "code": 200, "data": {
  "total": 12, "hasMore": true,
  "items": [ { "id": 1, "deviceName": "V2419A", "content": "…",
               "likeCount": 3, "liked": false, "own": true,
               "createTime": "2026-11-18 15:09:03" } ]
} }
```

约定：

1. **评论按 `songKey`（归一化「歌名|主歌手」）归属，不是按平台 `trackId`**。
   用户在网易云听《晴天》和在酷我听到的是同一批评论 —— 评论评价的是「这首歌」，
   不是「某个平台的一条曲目记录」。
2. **归一化只在服务端做一处**（`MusicCommentService.songKey`），客户端传原始
   `title` / `artist`。两端各写一套正则，迟早在全角括号或 `feat.` 上对不齐，
   同一首歌会分裂成两批评论。
3. **括号里的版本标识参与匹配**：`晴天 (Live)` 与 `晴天` 是两条独立评论流。
   和 `RecommendService` 的去重规则一致 —— 那边也把它们当两首歌。
4. 点赞用**唯一键 + `INSERT IGNORE`**，不是「先查再写」。连点两下时先查后写会
   两个请求都读到「没赞过」，然后一条撞唯一键直接 500。重复点赞是幂等的。
5. 频控：同一设备 **60 秒内最多 3 条**。
6. 响应**不回传 `deviceId`**（那是能冒充别人点赞/删评论的凭证），只给 `own`。
7. `deviceName` 为空时客户端显示「匿名听众」。App 没有登录体系，昵称默认取设备型号。
8. 离线时这个功能**明确失败**，不做本地伪造：本机编不出别人的评论。

## 3.7 客户端离线能力矩阵

本节描述 App 在**后端关闭**时的行为，是「离线优先」改造的验收标准。

| 能力 | 后端在线 | 后端关闭 |
| --- | --- | --- |
| 本地曲库（MediaStore） | ✅ | ✅ |
| 云端曲库 `/tracks` | ✅ | ❌ 列表为空，不报错 |
| 搜索 | 四平台聚合 | 仅酷我（客户端直连） |
| 在线播放 | 四平台 + 自动换源 | 仅酷我曲目可播，其它平台提示连不上后端 |
| 歌词 | 后端优先 | 酷我直连，拿不到显示「暂无歌词」 |
| 收藏 | 本机 + 后端 + 平台同步 | 本机生效，同步静默跳过 |
| 推荐 | 后端聚合 + 平台推荐流 | `LocalRecommendEngine` 本机算 |
| 音源开关 / Cookie | 本机 | 本机 |
| 分享码 | ✅ | ❌ 明确失败（码必须在服务端） |

判活方式：`OnlineSourceRouter` 每 15–45 秒探一次 `/app/music/sources`（2.5 秒超时），
不是靠「请求失败了才降级」—— 后端没起时那样要等 15 秒 connect timeout。

## 4. 错误码对照

| code | 含义 | Android 端处理 |
| --- | --- | --- |
| 200 | 成功 | 正常渲染 |
| 401 | 未登录 / token 失效 | 清本地会话，跳登录页 |
| 403 | 无访问权限 | 提示「没有权限」，不跳登录 |
| 404 | 接口不存在 | 提示检查后端版本或路径 |
| 500 | 服务端异常 | 直接展示 `msg`（若依的 `msg` 通常已中文化） |
| -1 | 客户端未知错误 | 兜底提示 |
| -2 | 网络不可达 | 提示检查网络 |

## 5. 联调自检清单

按顺序排错，能省一半时间：

1. 浏览器直接访问 `http://localhost:8080/captchaImage` 有没有 JSON 返回？
   - 没有 → 后端没起来，或端口不是 8080
2. App 侧 `BASE_URL` 对不对？debug 默认 `http://127.0.0.1:8080/`，需要 `adb reverse tcp:8080 tcp:8080`
   （`./gradlew :app:installDebug` 会自动执行）。别用 `10.0.2.2`，本机该映射不通
3. 登录返回 200 但 token 为空 → 请求打到了别的服务，检查 `BASE_URL`
4. 登录成功但 `/getInfo` 返回 401 → 拦截器没带 `Bearer ` 前缀
5. 首页一直显示「网络连接失败」→ 真机没把电脑 IP 加进防火墙白名单
