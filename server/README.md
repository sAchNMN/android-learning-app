# 后端接入说明（RuoYi-Vue）

Android 端不承载业务逻辑，所有数据来自若依。本目录只放**接入配置与说明**，
不放若依源码——若依是独立服务，几万个文件、独立的 Maven 生命周期，
塞进 Android 仓库只会让两边都难维护。

## 1. 技术栈与版本要求

| 组件 | 要求 | 说明 |
| --- | --- | --- |
| RuoYi-Vue | 官方仓库当前稳定版 | 前后端分离版，不是 RuoYi 单体版（Thymeleaf） |
| JDK | 17 及以上 | RuoYi-Vue 3.9.x 起基于 Spring Boot 3.x |
| MySQL | 8.0 | 字符集 `utf8mb4`，库名建议 `ry-vue` |
| Redis | 5.0+ | **必须有**，token 存在 Redis 里，不起 Redis 无法登录 |
| Maven | 3.8+ | 后端构建 |
| Node.js | 18+ | 只有要跑后台管理前端时才需要 |

官方仓库：`https://gitee.com/y_project/RuoYi-Vue`（或 GitHub 同名镜像）

## 2. 目录约定

```
server/
├── README.md            # 本文件
├── docker-compose.yml   # MySQL + Redis，一条命令拉起依赖
└── .gitignore           # 忽略本地 clone 的若依源码
```

若依源码请 clone 到 `server/RuoYi-Vue/`，该目录已被 `.gitignore` 排除，
不会污染本仓库。

```bash
# 在 server/ 目录下执行
git clone https://gitee.com/y_project/RuoYi-Vue.git
```

## 3. 启动顺序

```bash
# 1. 起 MySQL 与 Redis
cd server
docker compose up -d
docker compose ps          # 等 mysql 状态变成 healthy

# 2. 导入若依基础表（必须先于业务表）
#    sql/ry_*.sql 来自若依源码仓库的 sql/ 目录
mysql -h127.0.0.1 -uroot -proot ry-vue < RuoYi-Vue/sql/ry_20250522.sql
mysql -h127.0.0.1 -uroot -proot ry-vue < RuoYi-Vue/sql/quartz.sql

# 3. 导入本项目业务表
mysql -h127.0.0.1 -uroot -proot ry-vue < ../sql/01_biz_schema.sql
mysql -h127.0.0.1 -uroot -proot ry-vue < ../sql/02_biz_menu.sql

# 4. 起后端
cd RuoYi-Vue
mvn clean package -DskipTests
java -jar ruoyi-admin/target/ruoyi-admin.jar
```

后端默认端口 **8080**，与 Android 的 `BASE_URL` 对应。

## 4. 必须确认的三处配置

改 `ruoyi-admin/src/main/resources/application.yml`（或 `application-druid.yml`）：

1. **数据库连接**：`spring.datasource.druid.master.url` 指向 `jdbc:mysql://127.0.0.1:3306/ry-vue`
2. **Redis**：`spring.data.redis.host=127.0.0.1`、`port=6379`（密码为空则 `password:` 留空）
3. **文件上传路径**：`ruoyi.profile` 指向服务器上一个可写目录

## 5. Android 端需要对齐的四件事

| 项 | 若依默认值 | 影响 |
| --- | --- | --- |
| 端口 | 8080 | 模拟器用 `http://10.0.2.2:8080/`，真机用电脑局域网 IP |
| context-path | 空 | 接口直接挂在根路径，`/login` 而不是 `/prod-api/login` |
| 成功码 | `code == 200` | 不是 0，不是 HTTP 200 的字面复用 |
| 认证头 | `Authorization: Bearer <token>` | 少了 `Bearer ` 前缀后端查不到 token |

真机调试时把 `android/app/build.gradle.kts` 里 debug 的 `BASE_URL`
改成电脑的局域网地址，例如 `http://192.168.1.10:8080/`，
并确认 Windows 防火墙放行 8080。

## 6. 验证码

若依默认开启图形验证码，登录请求必须带 `code` + `uuid`。
Android 端已自动适配：进登录页先请求 `/captchaImage`，
`captchaEnabled=true` 才显示验证码输入框。

课设演示想省事，可以在后台「参数设置」里把
`sys.account.captchaEnabled` 改成 `false`，Android 端会自动隐藏验证码框。

## 7. 账号体系的重要提醒

**若依只有后台账号（`sys_user`），没有面向 App 的 C 端用户表。**
直接用 `sys_user` 登录能跑通，但有两个后遗症：

- App 用户和后台运维账号混在一张表里，权限模型是「菜单 + 按钮」，跟 App 页面权限不是一回事
- 每加一个 App 用户，就得在后台配角色、配菜单，运营成本高

两种走法：

**方案 A（课设/演示，够用）**：直接用 `sys_user` 登录，建一个受限角色
（例如 `app_user`），只授予必要权限。Android 端零改动。

**方案 B（正式项目，推荐）**：在 `ruoyi-system` 里加 `biz_app_user` 表，
写一个 `AppAuthController`，复用若依的 `TokenService.createToken(LoginUser)`
签发 token。这样：

- token 仍然是若依的 Redis token，Android 端的拦截器、401 处理全部不用改
- 只需要把 `/login`、`/getInfo` 的路径换成 `/app/login`、`/app/getInfo`
- Android 端改一处常量即可

`sql/01_biz_schema.sql` 里已经带了 `biz_app_user` 建表语句，
选方案 B 时直接导入。
