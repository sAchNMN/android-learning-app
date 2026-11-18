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

### 3.3 在线音源（网易云 / 酷狗聚合）

完整设计与 Mineradio 的对照见 `docs/music-sources.md`，这里只列契约。

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/app/music/search?keyword=晴天&limit=20&sources=netease,kugou` | 聚合搜索，单平台失败只进 `errors` |
| GET | `/app/music/play?source=netease&id=186016&title=晴天&artist=周杰伦&durationMs=269747&fallback=true` | 换取播放地址，可自动换源 |
| GET | `/app/music/lyric?source=netease&id=186016` | LRC 文本，可能为空串 |
| GET | `/app/music/sources` | 音源列表（不回传 Cookie，只给 `hasCookie`） |
| PUT | `/app/music/sources/{key}` | 改 `enabled` / `priority` / `cookie`，字段都可空 |

约定：

1. `/play` 失败时 HTTP 仍是 200，`data.playable=false`，用 `category` +
   `message` 表达原因（`login_required / vip_required / paid_required /
   trial_only / copyright_unavailable / url_unavailable`）。
2. 自动换源成功时 `data.switchedFrom` / `data.switchedTo` 非空，客户端 toast 提示。
3. 酷狗取址需要把搜索返回的 `hash / albumId / albumAudioId` 原样带回；网易云只需 `id`。
4. 搜索结果不落库；播放地址缓存 10 分钟，失败结果缓存 30 秒。

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
