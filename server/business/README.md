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
├── domain/                                OnlineTrack / OnlinePlay / 音源配置
├── mapper/MusicSourceConfigMapper.java    biz_music_source 的注解 SQL
└── source/                                网易云、酷狗适配器 + 聚合/换源/缓存
```

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

## 为什么不做成独立 Maven 模块

做成 `ruoyi-takeout` 模块能彻底摆脱「复制」这一步，但要改 `pom.xml` 的
`modules`、加依赖、处理若依的包扫描——对课设级项目是过度工程。
副本 + 两行 `cp` 已经能保证代码不丢。
