# 学习助手

Android 客户端 + RuoYi-Vue 后端 + MySQL 的三层学习类应用。
仓库按「一个仓管全栈」组织，Android、后端接入、数据库脚本、接口文档各占一层目录。

## 技术栈

| 层 | 技术 | 说明 |
| --- | --- | --- |
| 移动端 | Kotlin、AGP 9.3、compileSdk 37、minSdk 24 | XML View 体系，MVVM，无 Compose |
| 网络 | Retrofit 2.11 + OkHttp 4.12 + Gson | 统一信封解析、Bearer 认证、错误码映射 |
| 状态 | Kotlin Coroutines + StateFlow + DataStore | 无 LiveData、无 Hilt |
| 服务端 | RuoYi-Vue 3.9.2（Spring Boot 4.1 + MyBatis-Plus） | 独立部署，不在本仓库存源码 |
| 管理端 | RuoYi-Vue3 3.9.2（Vue 3.5 + Vite 6 + Element Plus） | 后台运营页面 |
| 数据库 | MySQL 8.0+ | 字符集 utf8mb4 |
| 缓存 | Redis 7 | 若依 token 存储，**必须启动** |

## 目录结构

三端各占一个顶层目录，辅助资源放 `sql/` 与 `docs/`。

```
.
├── android/                 【移动端】Gradle 根工程，Studio 打开这里
│   ├── app/                 壳：Application、依赖容器、登录页、首页
│   ├── core/
│   │   ├── common/          结果封装、UI 状态、日志、View 扩展
│   │   └── network/         Retrofit、拦截器、若依协议适配
│   └── feature/
│       └── demo/            贪吃蛇（已从构建摘除，代码保留）
├── server/                  【服务端】RuoYi-Vue 接入说明
│   ├── RuoYi-Vue/           若依后端源码（独立 clone，不入库）
│   └── docker-compose.yml   MySQL + Redis（可选）
├── admin/                   【后台管理端】RuoYi-Vue3
│   ├── RuoYi-Vue3/          若依 Vue3 前端源码（独立 clone，不入库）
│   └── README.md            管理端启动说明
├── sql/                     业务表结构与后台菜单脚本
└── docs/                    接口契约、架构说明
```

## 快速开始

### 1. 启动中间件

```bash
cd server
docker compose up -d
```

MySQL 在 `3306`（root/root，库 `ry-vue`），Redis 在 `6379`。

### 2. 起后端

后端是独立服务，源码单独 clone（`server/RuoYi-Vue/` 已被 gitignore）：

```bash
cd server
git clone https://gitee.com/y_project/RuoYi-Vue.git
cd RuoYi-Vue
mvn clean package -DskipTests
java -jar ruoyi-admin/target/ruoyi-admin.jar
```

建库与导表的完整步骤见 [`server/README.md`](server/README.md)。

### 3. 跑客户端

用 Android Studio 打开 **`android/` 目录**（不是仓库根目录，Gradle 根在这里）。

```bash
cd android
./gradlew :app:assembleDebug
```

安装后启动即是登录页。默认账号 `admin / admin123`。

### 4. 起后台管理端（可选）

```bash
cd admin/RuoYi-Vue3
npm install
npm run dev
```

默认 `http://localhost`（dev 端口 80，绑不上就改 `vite.config.js` 的 `server.port`）。
详见 [`admin/README.md`](admin/README.md)。

## 接口约定

详见 [`docs/api.md`](docs/api.md)。三条容易踩的：

1. 成功码是 **`code == 200`**，不是 0
2. token 走 **`Authorization: Bearer <token>`**，`Bearer ` 前缀不能省
3. **登录过期是 HTTP 200 + body `code:401`**，只看 HTTP 状态码会漏判

模拟器访问宿主机后端用 `http://10.0.2.2:8080/`；
真机改成电脑局域网 IP，并放行防火墙 8080 端口。

## 开发约定

| 项 | 约定 |
| --- | --- |
| 包名 | `com.szc.learning`，子模块 `com.szc.learning.core.*` / `.feature.*` |
| 命名 | 布局 `activity_*` / `fragment_*` / `item_*`，ViewModel 以 `ViewModel` 结尾 |
| 依赖方向 | `app → feature → core`，`feature` 之间不允许互相依赖 |
| 异常处理 | 网络层统一返回 `AppResult`，不向上抛异常 |
| 提交信息 | Conventional Commits，描述用中文，如 `feat: 增加课程列表页` |

架构分层、设计取舍、加新功能的步骤见 [`docs/architecture.md`](docs/architecture.md)。

## 关于 `feature/demo`

贪吃蛇是项目初期的练手模块，包含纯 Kotlin 规则层（8 条单测）与完全自绘的
`SnakeGameView`（棋盘让开系统栏、蛇头靠近时悬浮信息淡出）。

它已从构建中摘除，`settings.gradle.kts` 里的 `include(":feature:demo")` 是注释状态。
需要恢复时：取消该行注释，并同步取消 `app/build.gradle.kts` 里对应的依赖注释。
