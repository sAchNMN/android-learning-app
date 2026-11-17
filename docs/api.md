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

### 3.1 外卖点餐（最小闭环版）

> 本节是 P1 已完成的最小闭环。P2 在 **3.3** 做了扩展：
> `/app/shop/list` 与 `/app/order/list` 废弃，`/app/shop/{shopId}` 与 `/app/order/{orderId}`
> 的返回结构有变更，`/app/order/create` 增加 `clientToken` / `addressId` / `expectTime`。
> 联调时以 3.3 为准。

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

### 3.3 P2 接口扩展（二级 / 三级页面）

页面与接口的对应关系见 [`page-design.md`](page-design.md) 第 6 节，服务端实现见
[`server-logic.md`](server-logic.md)。

**旧接口处置**：`GET /app/shop/list`、`GET /app/order/list` 保留一版，标记废弃。
客户端 P2 全部改用分页接口，下一版删掉旧的。

#### A. 首页与店铺

**`GET /app/home/index`** — 首页聚合，一次请求渲染整个一级页

```json
{
  "code": 200,
  "data": {
    "categories": [ { "categoryId": 2, "categoryName": "面食", "icon": "ic_cat_noodle" } ],
    "hotKeywords": ["牛肉面", "沙拉", "水煮牛肉"],
    "recommendShops": [ { "shopId": 1, "shopName": "老王牛肉面", "rating": 4.8, "monthSales": 1286, "deliveryTime": 25, "minAmount": 15.00, "deliveryFee": 3.00, "status": "0" } ]
  }
}
```

`recommendShops` 取评分降序前 10 条（营业中）。

**`GET /app/shop/category/list`** — 分类列表

```json
{ "code": 200, "data": [ { "categoryId": 1, "categoryName": "快餐便当", "icon": "ic_cat_fastfood" } ] }
```

**`GET /app/shop/page`** — 店铺分页（L1）

| 参数 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| categoryId | Long | 否 | 不传/0 表示全部分类 |
| keyword | String | 否 | 店铺名模糊匹配 |
| sort | String | 否 | `default` 综合 / `rating` 评分 / `fast` 送达最快 / `cheap` 起送最低 |
| pageNum | int | 否 | 默认 1 |
| pageSize | int | 否 | 默认 10，上限 50 |

```json
{
  "code": 200,
  "data": {
    "total": 3,
    "rows": [ { "shopId": 1, "shopName": "老王牛肉面", "category": "面食", "rating": 4.8,
                "monthSales": 1286, "deliveryTime": 25, "minAmount": 15.00,
                "deliveryFee": 3.00, "openTime": "09:00-21:30", "status": "0", "image": null } ]
  }
}
```

**`GET /app/shop/{shopId}`** — 店铺详情（D1），结构相对 3.1 版**有变更**

```json
{
  "code": 200,
  "data": {
    "shop": { "shopId": 1, "shopName": "老王牛肉面", "rating": 4.8, "monthSales": 1286,
              "minAmount": 15.00, "deliveryFee": 3.00, "deliveryTime": 25,
              "openTime": "09:00-21:30", "status": "0", "description": "手工拉面，牛骨慢炖六小时" },
    "favorited": false,
    "groups": [
      {
        "category": "招牌面",
        "dishes": [
          {
            "dishId": 1, "dishName": "红烧牛肉面", "description": "大块牛腩，汤浓面劲",
            "price": 22.00, "sales": 328, "hasSpec": true,
            "specs": [
              { "specId": 1, "specName": "份量", "required": true, "multi": false,
                "options": [ { "optionId": 1, "optionName": "标准份", "priceDelta": 0.00, "isDefault": true } ] }
            ]
          }
        ]
      }
    ]
  }
}
```

`groups` 的顺序由服务端按菜品 `sort_no` 决定，**不要**在客户端再排序。

**`GET /app/search/hot`** — 热搜词

```json
{ "code": 200, "data": ["牛肉面", "麻辣烫", "沙拉"] }
```

**`GET /app/search`** — 搜索（L2）

| 参数 | 必填 | 说明 |
| --- | --- | --- |
| keyword | 是 | 最长 20 字，超长服务端截断 |
| type | 是 | `shop` / `dish` |
| pageNum / pageSize | 否 | 同店铺分页 |

`type=shop` 的 `rows` 结构与 `/app/shop/page` 一致；
`type=dish` 返回：

```json
{ "dishId": 5, "dishName": "麻婆豆腐", "shopId": 2, "shopName": "川味小厨",
  "price": 26.00, "sales": 187, "hasSpec": true }
```

#### B. 地址簿

**`GET /app/address/list`** — 地址列表，默认地址排第一，其余按更新时间倒序

```json
{
  "code": 200,
  "data": [
    { "addressId": 3, "receiver": "张三", "phone": "13800138000",
      "province": "河南省", "city": "郑州市", "district": "金水区",
      "detail": "文化路 1 号", "fullAddress": "河南省郑州市金水区文化路 1 号",
      "tag": "家", "isDefault": "1" }
  ]
}
```

`fullAddress` 由服务端拼好，客户端直接展示，避免各页面重复拼字符串。
`isDefault` 是字符串 `"1"` / `"0"`（沿用若依 `char(1)` 风格，不做 Boolean 转换），
客户端用 `isDefault == "1"` 判断。

**`GET /app/address/{addressId}`** — 地址详情（D5 回填）。不存在返回 500「地址不存在」。

**`POST /app/address`** — 新增

```json
{ "receiver": "张三", "phone": "13800138000", "province": "河南省", "city": "郑州市",
  "district": "金水区", "detail": "文化路 1 号", "tag": "家", "isDefault": "1" }
```

返回 `{ "code": 200, "data": { "addressId": 4 } }`。

`PUT /app/address` 的请求体在新增基础上加 `addressId`（必填）。
两个接口的校验规则、`isDefault` 传参格式完全一致。

**`PUT /app/address`** — 修改，请求体在新增基础上加 `addressId`。

**`DELETE /app/address/{addressId}`** — 逻辑删除。删除默认地址时，服务端自动把剩余最新一条设为默认。

**`POST /app/address/{addressId}/default`** — 设为默认，成功后其它地址 `isDefault` 自动置 false。

地址校验规则（前后端共用）：收货人 2–20 字、手机 `^1[3-9]\d{9}$`、详细地址 5–128 字、上限 20 条。

#### C. 订单

**`POST /app/order/preview`** — 结算预览（D2 进入时）

```json
{
  "shopId": 1,
  "items": [ { "dishId": 1, "quantity": 1, "optionIds": [12, 15] } ],
  "addressId": 3
}
```

响应字段：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| items | array | 逐行 `dishName` / `specText` / `price`（含规格加价）/ `quantity` / `subtotal` |
| totalAmount | decimal | 商品小计 |
| deliveryFee | decimal | 配送费 |
| payAmount | decimal | 实付 = 商品小计 + 配送费 |
| minAmount | decimal | 店铺起送价 |
| shortOfMin | decimal | 还差多少起送，0 表示已达 |
| defaultAddress | object | 未传 `addressId` 时返回默认地址，无地址为 null |
| expectOptions | array | `{ value, label, expectTime }`，第一项固定为 `asap` |

**`POST /app/order/create`** — 下单（D2 提交）。请求体比 3.1 版**多三个字段**：

```json
{
  "clientToken": "b3f1c2d4-8a7e-4f31-9c2b-1d5e6f7a8b9c",
  "shopId": 1,
  "items": [ { "dishId": 1, "quantity": 1, "optionIds": [12, 15] } ],
  "addressId": 3,
  "expectTime": "asap",
  "remark": "少放辣"
}
```

- `clientToken`：客户端进入结算页时生成一次，失败重试复用，服务端 5 分钟内去重
- `addressId`：允许为空（用默认地址），但**不接收 `receiver/phone/address` 明文**
  （3.1 版的三字段保留兼容一版，有 `addressId` 时以地址簿为准）
- 响应 `data` 为完整订单详情（结构同 `GET /app/order/{orderId}`）

**`GET /app/order/page`** — 订单分页（L4）

| 参数 | 说明 |
| --- | --- |
| status | 空=全部；单值 `0`/`3`/`4`；多值 `1,2`。只允许 0-4，其它值返回 500 |
| pageNum / pageSize | 默认 1 / 10，上限 50 |

`rows` 每项：

```json
{ "orderId": 12, "orderNo": "2026111709001234", "shopId": 1, "shopName": "老王牛肉面",
  "totalAmount": 32.00, "deliveryFee": 3.00, "payAmount": 35.00, "itemCount": 3,
  "status": "0", "statusText": "待支付", "createTime": "2026-11-17 09:00:12",
  "reviewStatus": "0" }
```

`statusText` 由服务端下发，客户端不再自己 `when(status)` 翻译（两边翻译迟早不一致）。

**`GET /app/order/{orderId}`** — 订单详情（D4），在 3.1 版基础上加三个字段

```json
{
  "code": 200,
  "data": {
    "order": { "orderId": 12, "orderNo": "2026111709001234", "status": "2", "statusText": "配送中",
               "shopName": "老王牛肉面", "receiver": "张三", "phone": "13800138000",
               "address": "河南省郑州市金水区文化路 1 号", "remark": "少放辣",
               "totalAmount": 32.00, "deliveryFee": 3.00, "payAmount": 35.00, "itemCount": 3,
               "createTime": "2026-11-17 09:00:12", "payTime": "2026-11-17 09:01:03",
               "deliverTime": "2026-11-17 09:03:20", "receiveTime": null,
               "cancelReason": null, "expectTime": "2026-11-17 09:40:00" },
    "items": [ { "dishName": "红烧牛肉面", "specText": "大份/微辣", "price": 26.00,
                 "quantity": 1, "subtotal": 26.00 } ],
    "reviewed": false,
    "timeline": [
      { "status": "0", "label": "已下单", "time": "2026-11-17 09:00:12" },
      { "status": "1", "label": "已支付", "time": "2026-11-17 09:01:03" },
      { "status": "2", "label": "商家已接单", "time": "2026-11-17 09:03:20" }
    ]
  }
}
```

`timeline` 只包含已发生的节点，最后一项即当前状态。

**`POST /app/order/{orderId}/pay`** — 模拟支付。语义从 3.1 版收紧：

- 条件：`status='0'` 且下单未超 15 分钟
- 重复支付：返回 500「订单状态已变更，请刷新后重试」
- 响应 `{ "code": 200, "msg": "支付成功" }`

**`POST /app/order/{orderId}/cancel`** — 取消

```json
{ "reason": "不想要了" }
```

只有 `status` 为 `0` 或 `1`（且商家未发货）可取消，否则 500「订单当前状态不可取消」。

**`POST /app/order/{orderId}/confirm`** — 确认收货，仅 `status='2'` 可用。

**`POST /app/order/{orderId}/review`** — 提交评价（D6）

```json
{ "rating": 5, "tasteRating": 5, "packRating": 4, "deliveryRating": 5,
  "tags": ["分量足", "味道好"], "content": "汤很浓，面也劲道", "isAnonymous": false }
```

- 仅 `status='3'` 且 `reviewStatus='0'` 可评价
- 重复评价返回 500「该订单已评价」

#### D. 收藏

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/app/favorite/list` | 收藏的店铺，结构同 `/app/shop/page` 的 `rows` |
| POST | `/app/favorite/{shopId}` | 收藏（重复收藏返回 200，幂等） |
| DELETE | `/app/favorite/{shopId}` | 取消收藏（未收藏也返回 200，幂等） |

#### E. 新增业务响应的通用点

1. 所有 `status` 都成对下发 `statusText`，客户端不再维护状态文案映射
2. 所有金额字段保留两位小数，服务端已 `setScale(2)`
3. 列表接口一律 `{ total, rows }`，详情接口一律裸对象，不包一层 `data.data`
4. 业务失败统一 `code=500` + 可直接展示的 `msg`，**不要把校验细节编码成不同 code**
   （客户端不需要按 code 分支，只需要展示；401 除外）

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
2. 模拟器里 `10.0.2.2` 能不能通？真机要用电脑局域网 IP，不能用 `localhost`
3. 登录返回 200 但 token 为空 → 请求打到了别的服务，检查 `BASE_URL`
4. 登录成功但 `/getInfo` 返回 401 → 拦截器没带 `Bearer ` 前缀
5. 首页一直显示「网络连接失败」→ 真机没把电脑 IP 加进防火墙白名单

P2 的接口多了，再加四条：

6. `/app/shop/page` 返回的 `rows` 是 `null` 而不是空数组 → 服务端 `PageHelper` 与查询之间有别的 SQL
7. 结算页显示"还差 ¥3 起送"但按钮能点 → 客户端没用 `shortOfMin`，自己拿 `Double` 算的
8. 同一订单连点两次支付都提示成功 → 支付 SQL 少写了 `and status = '0'`，必现重复支付
9. 店铺详情的分类顺序与后台不一致 → 服务端用了 `GROUP BY` 分组，组顺序不稳定，改用 `LinkedHashMap` 按菜品顺序建组
