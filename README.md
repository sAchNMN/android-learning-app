# android-learning-app

Android 客户端 + RuoYi-Vue 后端 + MySQL 的三层工程骨架。
仓库按「一个仓管全栈」组织，三端各占一个顶层目录。

> **业务尚未确定。**
> 目前只实现了登录链路（登录 → 换 token → 拉用户信息 → 落地首页），
> 没有任何业务功能，也没有业务表。需求定下来之后再加。

## 技术栈

| 层 | 技术 | 说明 |
| --- | --- | --- |
| 移动端 | Kotlin、AGP 9.3、compileSdk 37、minSdk 24 | XML View 体系，MVVM，无 Compose |
| 网络 | Retrofit 2.11 + OkHttp 4.12 + Gson | 统一信封解析、Bearer 认证、错误码映射 |
| 状态 | Kotlin Coroutines + StateFlow + DataStore | 无 LiveData、无 Hilt |
| 服务端 | RuoYi-Vue 3.9.2（Spring Boot 4.1 + MyBatis-Plus） | 独立部署，源码不入库 |
| 管理端 | RuoYi-Vue3 3.9.2（Vue 3.5 + Vite 6 + Element Plus） | 若依自带后台页面 |
| 数据库 | MySQL 8.0+ | 字符集 utf8mb4 |
| 缓存 | Redis 5.0+ | 若依 token 存储，**必须启动** |

## 目录结构

```
.
├── android/                 【移动端】Gradle 根工程，Studio 打开这里
│   ├── app/                 壳：Application、依赖容器、登录页、首页
│   ├── core/
│   │   ├── common/          结果封装、UI 状态、日志、View 扩展
│   │   └── network/         Retrofit、拦截器、若依协议适配
│   └── feature/
│       └── demo/            贪吃蛇（已从构建摘除，代码保留）
├── server/                  【服务端】若依后端
│   ├── RuoYi-Vue/           源码（独立 clone，不入库）
│   ├── redis/               Windows 便携版 Redis（不入库）
│   ├── start-redis.sh       启动 Redis
│   ├── start-backend.sh     启动后端
│   └── docker-compose.yml   MySQL + Redis（备选，需 Docker）
├── admin/                   【后台管理端】RuoYi-Vue3
│   ├── RuoYi-Vue3/          源码（独立 clone，不入库）
│   └── README.md            管理端启动说明
├── sql/                     业务表脚本（待需求确定）
└── docs/                    接口契约、架构说明
```

## 快速开始

### 1. 中间件

本机已有 MySQL 26.10 跑在 3306，Redis 用便携版：

```bash
./server/start-redis.sh
```

建库导表（只在首次或重建时做）：

```bash
MYSQL="/c/Program Files/MySQL/MySQL Server 26.10/bin/mysql.exe"
"$MYSQL" -uroot -p -e "CREATE DATABASE IF NOT EXISTS \`ry-vue\` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;"
"$MYSQL" -uroot -p ry-vue < server/RuoYi-Vue/sql/ry_20260417.sql
"$MYSQL" -uroot -p ry-vue < server/RuoYi-Vue/sql/quartz.sql
```

### 2. 起服务端

```bash
./server/start-backend.sh
```

**不要直接 `java -jar`**：本机环境会把端口顶到 8066（被其它服务占用），
脚本里显式带了 `--server.port=8080`。详见 [`server/README.md`](server/README.md)。

### 3. 起管理端（可选）

```bash
cd admin/RuoYi-Vue3
npm install
npm run dev
```

默认 `http://localhost`。详见 [`admin/README.md`](admin/README.md)。

### 4. 跑客户端

用 Android Studio 打开 **`android/` 目录**（不是仓库根，Gradle 根在这里）。

```bash
cd android
./gradlew :app:assembleDebug
```

登录账号 `admin / admin123`。

## 接口约定

详见 [`docs/api.md`](docs/api.md)。三条容易踩的：

1. 成功码是 **`code == 200`**，不是 0
2. token 走 **`Authorization: Bearer <token>`**，`Bearer ` 前缀不能省
3. **登录过期是 HTTP 200 + body `code:401`**，只看 HTTP 状态码会漏判

模拟器访问后端：默认 `http://10.0.2.2:8080/`。
本机 QEMU 的该映射不生效，改用 `adb reverse tcp:8080 tcp:8080`
并以 `-PapiBaseUrl=http://127.0.0.1:8080/` 构建；真机用电脑局域网 IP。

## 开发约定

| 项 | 约定 |
| --- | --- |
| 包名 | `com.szc.learning`，子模块 `com.szc.learning.core.*` / `.feature.*` |
| 命名 | 布局 `activity_*` / `fragment_*` / `item_*`，ViewModel 以 `ViewModel` 结尾 |
| 依赖方向 | `app → feature → core`，`feature` 之间不允许互相依赖 |
| 异常处理 | 网络层统一返回 `AppResult`，不向上抛异常 |
| 提交信息 | Conventional Commits，描述用中文，如 `feat: 增加xx功能` |

分层依据、设计取舍、加新功能的步骤见 [`docs/architecture.md`](docs/architecture.md)。

## 关于 `feature/demo`

贪吃蛇是项目初期的练手模块，包含纯 Kotlin 规则层（8 条单测）与完全自绘的
`SnakeGameView`。已从构建中摘除，`settings.gradle.kts` 里的 `include(":feature:demo")`
是注释状态；恢复时取消该行注释，并同步取消 `app/build.gradle.kts` 里对应的依赖注释。
