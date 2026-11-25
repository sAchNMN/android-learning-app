# 业务模块源码（外卖 + 音乐）

若依后端源码目录（`server/RuoYi-Vue/`）是独立 clone 的第三方仓库，被 `.gitignore` 排除。
如果业务代码只写在那个目录里，重新 clone 就全丢了。

所以这里保留一份副本，作为**版本控制中的唯一可信来源**。

## 目录

```
server/business/takeout/          对应包 com.ruoyi.takeout
├── controller/AppTakeoutController.java   App 端接口（/app/shop/**、/app/order/**）
├── domain/                                实体与请求/响应体
├── mapper/                                注解 SQL（无 XML）
└── service/TakeoutService.java            下单事务、支付

server/business/music/            对应包 com.ruoyi.music
├── controller/AppMusicController.java     App 端接口（/app/music/**）
├── domain/                                OnlineTrack / OnlinePlay / 音源配置 / 喜欢与歌单
├── mapper/                                biz_music_source、biz_music_user_like 的注解 SQL
├── service/                               云端曲库、推荐算法、喜欢/歌单同步
└── source/                                网易云、酷狗适配器 + 聚合/换源/缓存/账号能力
```

账号能力（推荐流、同步喜欢、我的歌单）是 `MusicSourceProvider` 的**默认方法**：
只有网易云覆写了它们，酷狗走默认实现（返回「暂不支持」），加新平台时按需覆写即可。

多音源的设计与接口见 [`docs/music-sources.md`](../../docs/music-sources.md)。

## 改完代码怎么生效

```bash
# 1. 同步到若依工程（两个模块都同步）
cp -r server/business/takeout/. server/RuoYi-Vue/ruoyi-admin/src/main/java/com/ruoyi/takeout/
cp -r server/business/music/. server/RuoYi-Vue/ruoyi-admin/src/main/java/com/ruoyi/music/

# 2. 停后端 → 打包 → 启动（jar 被占用时打包会失败）
cd server/RuoYi-Vue
mvn -q -DskipTests package
cd ../.. && ./server/start-backend.sh
```

反向操作（在若依工程里直接改完，回写到本目录）：

```bash
cp -r server/RuoYi-Vue/ruoyi-admin/src/main/java/com/ruoyi/takeout/. server/business/takeout/
cp -r server/RuoYi-Vue/ruoyi-admin/src/main/java/com/ruoyi/music/. server/business/music/
```

## 若依工程必须补的三处改动

这三处改在 `RuoYi-Vue/` 里，不入版本控制。**重新 clone 之后必须再改一遍**，否则编译不过或运行时出问题。

| 文件 | 改什么 | 漏了会怎样 |
| --- | --- | --- |
| `ruoyi-admin/pom.xml` | 加 `spring-boot-starter-websocket` 依赖 | `RoomWebSocketHandler` 找不到 `WebSocketSession`，编译失败 |
| `ruoyi-framework/.../config/SecurityConfig.java` | 放行 `/app/music/room/ws/**` | 一起听 WS 一直 401 重连，REST 接口却全正常 |
| `ruoyi-admin/src/main/resources/application.yml` | `ruoyi.profile` 改成 `C:/ruoyi/uploadPath` | 默认 `D:/ruoyi/uploadPath`，D 盘不存在时上传/封面写不进去 |

前两处的上下文理由写在 `RoomWebSocketConfig` 的类注释里，改之前先读那段。

## 为什么不做成独立 Maven 模块

做成 `ruoyi-takeout` 模块能彻底摆脱「复制」这一步，但要改 `pom.xml` 的
`modules`、加依赖、处理若依的包扫描——对课设级项目是过度工程。
副本 + 两行 `cp` 已经能保证代码不丢。
