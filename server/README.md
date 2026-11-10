# 后端接入说明（RuoYi-Vue + RuoYi-Vue3）

Android 端不承载业务逻辑，所有数据来自若依。

本目录存放两个独立仓库的克隆，都不入主仓版本控制（见 `.gitignore`）：

| 目录 | 内容 | 版本 | 状态 |
| --- | --- | --- | --- |
| `RuoYi-Vue/` | **服务端**（Spring Boot 多模块） | 3.9.2，Spring Boot 4.1.0 | 已克隆，`mvn package` 通过 |
| `RuoYi-Vue3/` | **后台管理端**（Vue3 + Element Plus） | 3.9.2，Vue 3.5.26 / Vite 6.4.1 | 已克隆，`npm install` 完成 |

> 注意：RuoYi-Vue 从 3.9.x 起**只含后端**，Vue 前端拆到独立仓库 `RuoYi-Vue3`
> （`https://gitcode.com/yangzongzhuan/RuoYi-Vue3`，不在 gitee 的 y_project 下）。

## 1. 环境要求

| 组件 | 要求 | 本机现状 |
| --- | --- | --- |
| JDK | 17+（Spring Boot 4 要求） | JDK 21.0.12 ✓ |
| Maven | 3.8+ | 3.9.16 ✓ |
| Node.js | 18+（Vite 6） | v22.22.2 ✓ |
| MySQL | 8.0+ | **26.10 已在 3306 运行**，root 密码未知 |
| Redis | 5.0+ | **缺失**，必须补上 |

**Redis 是硬依赖**：若依的 token 存在 Redis（`TokenService` → `RedisCache`），
不起 Redis 后端能启动，但登录必定失败。

## 2. 补齐依赖

### 2.1 MySQL

两种用法，选一种。

**A. 复用在跑的 MySQL 26.10（推荐，省资源）**

需要 root 密码。导入脚本（在 `server/` 目录下执行）：

```bash
MYSQL="/c/Program Files/MySQL/MySQL Server 26.10/bin/mysql.exe"

# 建库
"$MYSQL" -uroot -p -e "CREATE DATABASE IF NOT EXISTS \`ry-vue\` DEFAULT CHARSET utf8mb4 COLLATE utf8mb4_general_ci;"

# 若依基础表（文件名随版本变化，以实际为准）
"$MYSQL" -uroot -p ry-vue < RuoYi-Vue/sql/ry_20260417.sql
"$MYSQL" -uroot -p ry-vue < RuoYi-Vue/sql/quartz.sql

# 本项目业务表
"$MYSQL" -uroot -p ry-vue < ../sql/01_biz_schema.sql
"$MYSQL" -uroot -p ry-vue < ../sql/02_biz_menu.sql
```

然后把 `RuoYi-Vue/ruoyi-admin/src/main/resources/application-druid.yml`
里的 `password` 改成你的真实密码（当前是占位值 `password`）。

**B. 用 Docker（需先装 Docker Desktop）**

本机 3306 已被占用，必须改端口：

```bash
cd server
# 把 docker-compose.yml 的 "3306:3306" 改成 "3307:3306"
docker compose up -d
```

再同步把 `application-druid.yml` 的 url 端口改为 3307。

### 2.2 Redis

三个选项，任选：

```bash
# 选项 1：WSL2 Ubuntu（本机已有 WSL，最省事，推荐）
wsl -d Ubuntu -- sudo apt update
wsl -d Ubuntu -- sudo apt install -y redis-server
wsl -d Ubuntu -- sudo service redis-server start

# 选项 2：Windows 原生（Memurai 或 Redis for Windows 的 msi 安装包）

# 选项 3：Docker
docker run -d --name ruoyi-redis -p 6379:6379 redis:7-alpine
```

本机 `.wslconfig` 是 `networkingMode=mirrored` + `hostAddressLoopback=true`，
WSL 里监听的 6379 在 Windows 侧 `localhost:6379` **直接可达**，不需要端口转发。

验证：`netstat -ano | findstr :6379` 能看到 LISTENING 才算好。

## 3. 启动

### 3.1 服务端

```bash
cd server/RuoYi-Vue
mvn clean package -DskipTests
java -jar ruoyi-admin/target/ruoyi-admin.jar
```

默认端口 **8080**，`context-path` 为 `/`，接口直接挂在根路径。

### 3.2 后台管理端

```bash
cd server/RuoYi-Vue3
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
