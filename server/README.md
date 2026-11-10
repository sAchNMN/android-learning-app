# 服务端（RuoYi-Vue）

Android 与后台管理端的所有数据都来自这里。

## 三端划分

| 目录 | 角色 | 技术栈 |
| --- | --- | --- |
| `android/` | 移动端 | Kotlin + Retrofit，包名 `com.szc.learning` |
| `server/` | **服务端**（本目录） | RuoYi-Vue 3.9.2 / Spring Boot 4.1.0 / JDK 17+ |
| `admin/` | 后台管理端 | RuoYi-Vue3 3.9.2 / Vue 3.5.26 + Vite 6.4.1 |

本目录内容：

| 路径 | 说明 | 状态 |
| --- | --- | --- |
| `RuoYi-Vue/` | 若依服务端源码，独立 clone，不入版本控制 | `mvn package` 通过 |
| `docker-compose.yml` | MySQL + Redis（可选；本机 3306 已被占用） | — |

> RuoYi-Vue 从 3.9.x 起**只含后端**，Vue 前端拆到独立仓库 `RuoYi-Vue3`
> （`https://gitcode.com/yangzongzhuan/RuoYi-Vue3`，不在 gitee 的 y_project 下），
> 已放在仓库根的 `admin/`。管理端的启动说明见 `admin/README.md`。

## 1. 环境要求

| 组件 | 要求 | 本机现状 |
| --- | --- | --- |
| JDK | 17+（Spring Boot 4 要求） | JDK 21.0.12 ✓ |
| Maven | 3.8+ | 3.9.16 ✓ |
| Node.js | 18+（Vite 6） | v22.22.2 ✓ |
| MySQL | 8.0+ | 26.10 @3306 ✓ 已建库导表 |
| Redis | 5.0+ | 便携版 @6379 ✓（`server/redis/`） |

**Redis 是硬依赖**：若依的 token 存在 Redis（`TokenService` → `RedisCache`），
不起 Redis 后端能启动，但登录必定失败。

## 2. 环境准备（已完成，换机/重装时照此复现）

### 2.1 数据库

建库并导入四份脚本，顺序不能反（业务表依赖若依的 `sys_*`）：

```bash
MYSQL="/c/Program Files/MySQL/MySQL Server 26.10/bin/mysql.exe"

"$MYSQL" -uroot -p -e "CREATE DATABASE IF NOT EXISTS \`ry-vue\` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;"

"$MYSQL" -uroot -p ry-vue < RuoYi-Vue/sql/ry_20260417.sql   # 若依基础表
"$MYSQL" -uroot -p ry-vue < RuoYi-Vue/sql/quartz.sql         # 定时任务表
"$MYSQL" -uroot -p ry-vue < ../sql/01_biz_schema.sql         # 业务表
"$MYSQL" -uroot -p ry-vue < ../sql/02_biz_menu.sql           # 后台菜单
```

当前结果：**31 张若依表 + 5 张 biz 表 + 19 条菜单**。

数据库密码已写入 `RuoYi-Vue/ruoyi-admin/src/main/resources/application-druid.yml`。
该文件属于 gitignore 的第三方源码，密码不进主仓。

### 2.2 Redis

WSL 里 `sudo` 需要交互密码装不了，Docker 也没装，所以用了 **Windows 便携版**：
`server/redis/`（tporadowski/redis 5.0.14.1，解压即用，已 gitignore）。

不需要安装、不需要管理员权限。启动方式见下一节。

验证：`netstat -ano | findstr :6379` 能看到 LISTENING，或 `redis-cli ping` 返回 PONG。

## 3. 启动（顺序不能反）

### 3.1 服务端

```bash
# 1) Redis，占用一个终端窗口
./server/start-redis.sh

# 2) 服务端，另开一个终端
./server/start-backend.sh
```

**必须用 `start-backend.sh`，不要直接 `java -jar`。**
本机环境会把端口顶到 **8066**（被 WorkBuddy 服务占用），只靠 `application.yml`
里的 `server.port: 8080` 会启动失败。脚本里显式带 `--server.port=8080`，
命令行参数优先级最高，能压过那个外部覆盖源。

首次启动约 25 秒。验证：

```bash
curl --noproxy '*' http://localhost:8080/captchaImage
# 期望: {"msg":"操作成功","captchaEnabled":true,"uuid":"...","img":"..."}
```

`--noproxy '*'` 不能省：本机代理会拦截 localhost 请求，导致看到 502 却以为服务没起。

### 3.2 后台管理端

```bash
# 从仓库根执行
cd admin/RuoYi-Vue3
npm install          # 已装过，可跳过
npm run dev
```

默认访问 `http://localhost:80`（`vite.config.js` 里 `port: 80`）。
Windows 上 80 端口可能需要管理员权限，起不来就改 `vite.config.js` 的 `port` 为 8081。

前端通过 `/dev-api` 代理到 `http://localhost:8080`（`vite.config.js` 顶部 `baseUrl`）。

### 3.3 默认账号

`admin / admin123`

## 4. Android 端需要对齐的四件事

| 项 | 若依实际值 | 影响 |
| --- | --- | --- |
| 端口 | 8080 | 模拟器用 `http://10.0.2.2:8080/`，真机用电脑局域网 IP |
| context-path | `/` | 接口是 `/login` 而不是 `/prod-api/login` |
| 成功码 | `code == 200` | 不是 0 |
| 认证头 | `Authorization: Bearer <token>`（`token.header` 配置） | 少了 `Bearer ` 前缀后端查不到 token |

真机调试改 `android/app/build.gradle.kts` 里 debug 的 `BASE_URL` 为电脑局域网 IP，
例如 `http://192.168.1.10:8080/`，并放行 Windows 防火墙 8080。

## 5. 验证码

若依默认开启图形验证码，登录必须带 `code` + `uuid`。
Android 端已适配：进登录页先请求 `/captchaImage`，`captchaEnabled=true` 才显示输入框。

课设演示想省事：后台「参数设置」把 `sys.account.captchaEnabled` 改成 `false`，
Android 端会自动隐藏验证码框。

## 6. 账号体系的重要提醒

**若依只有后台账号（`sys_user`），没有面向 App 的 C 端用户表。**
直接用 `sys_user` 登录能跑通，但有后遗症：App 用户和运维账号混在一张表，
权限模型是「菜单 + 按钮」，与 App 页面权限不是一回事。

两种走法：

**方案 A（课设/演示，够用）**：直接用 `sys_user` 登录，建一个受限角色 `app_user`，
只授予必要权限。Android 端零改动。

**方案 B（正式项目，推荐）**：在 `ruoyi-system` 里加 `biz_app_user` 表（SQL 已备好），
写 `AppAuthController`，复用 `TokenService.createToken(LoginUser)` 签发 token。
这样 token 仍是若依的 Redis token，Android 端拦截器和 401 处理全不用改，
只需把 `/login`、`/getInfo` 换成 `/app/login`、`/app/getInfo`。

## 7. 上线前必改

- `application.yml` 的 `token.secret` 当前是 `abcdefghijklmnopqrstuvwxyz`，必须换成随机长串
- `application-druid.yml` 的 Druid 监控台账号 `ruoyi/123456`
- 数据库密码不要留在明文配置里，用环境变量或外部配置
- 关闭 Druid 监控台的公网访问
