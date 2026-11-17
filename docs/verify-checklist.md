# P2 后端上线自检清单

分 6 步，每步都有「怎么验」和「失败了看什么」。按顺序做，不要跳。

## 当前验证状态（截至 2026-11-17）

| 项 | 状态 | 怎么验的 |
| --- | --- | --- |
| 客户端编译 | ✅ 通过 | `./gradlew :app:assembleDebug`，无警告 |
| 后端 语法 | ✅ 通过 | `bash server/verify-compile.sh`，60 个文件 |
| 后端 **mvn 编译** | ✅ 通过 | `mvn -DskipTests -o package` → BUILD SUCCESS |
| SQL 执行 | ✅ 完成 | 12 张 takeout_* 表，规格 7 组 / 选项 19 条 / 热搜 8 条 |
| 接口联通 | ✅ **22/22 PASS** | `bash docs/verify-api.sh`，含幂等与 CAS 断言 |
| 装机主链路 | ❌ 未做 | 客户端与后端接口版本需先对齐（见文末「分支现状」） |

### 实测中发现并修掉的问题

| 问题 | 现象 | 修法 |
| --- | --- | --- |
| `RedisTemplate` 泛型不匹配 | 启动失败：`No qualifying bean of type RedisTemplate<String, Object>` | 若依注册的是 `RedisTemplate<Object, Object>`，泛型不协变，字段类型必须跟着改 |

---

## 分支现状（重要）

工作区 `workbuddy/master-ea349285` 与 `master` 在 `bd7e534` 之后分叉：

| 分支 | 内容 | 位置 |
| --- | --- | --- |
| `master` | 顾客端改为**底部导航 + Fragment + 全局购物车**，后端停在 P1 | `C:/Users/34759/Desktop/android-demo/android-learning-app` |
| `workbuddy/master-ea349285` | P2 全套页面（**Activity 架构**）+ 后端 P2（37 个类） | 本工作区 |

**后端已经验证可用**（P2 代码 + 12 张表 + 22 个接口全通），但它目前只存在于本工作区和
Desktop 的 `RuoYi-Vue/ruoyi-admin/.../com/ruoyi/takeout`（直接覆盖进去的，未提交）。

**客户端两边不能直接合**：master 用 Fragment + 底部导航，本工作区用独立 Activity，
同名类（`ShopDetailActivity`、`CartStore`、`OrderListViewModel`）语义不同。

装机验证前必须先决定走哪套客户端架构，否则装上的 APK 与后端接口对不上。


---

## 步骤 1：拿到若依源码

```bash
cd server
git clone https://gitcode.com/yangzongzhuan/RuoYi-Vue.git
cd RuoYi-Vue && mvn -q -DskipTests package
```

代码要放进 RuoYi 里才能跑（它不在这个仓库的构建体系里）：

```bash
# 把业务代码挂进 admin 模块
cp -r server/business/takeout RuoYi-Vue/ruoyi-admin/src/main/java/com/ruoyi/
```

> `TakeoutApplication` 需要扫到 `com.ruoyi.takeout` 包。若依默认 `@SpringBootApplication`
> 在 `com.ruoyi` 下，会自动扫到，不用额外配置。

**预期**：`BUILD SUCCESSFUL`。

**失败看**：`package com.ruoyi.takeout` 是否在 `com.ruoyi` 之下（必须是子包才会被扫到）。

---

## 步骤 2：执行 SQL

四份脚本**必须按顺序**执行，03 依赖 01 建的表：

```bash
MYSQL="/c/Program Files/MySQL/MySQL Server 26.10/bin/mysql.exe"

"$MYSQL" -uroot -p ry-vue < sql/01_takeout_schema.sql
"$MYSQL" -uroot -p ry-vue < sql/02_takeout_data.sql
"$MYSQL" -uroot -p ry-vue < sql/03_takeout_p2_schema.sql
"$MYSQL" -uroot -p ry-vue < sql/04_takeout_p2_data.sql
```

### 验一下是否真的生效

```sql
-- 应该 12 张 takeout_* 表（01 的 4 张 + 03 的 8 张）
select table_name from information_schema.tables
where table_schema = 'ry-vue' and table_name like 'takeout%'
order by table_name;

-- 这几个列必须存在，缺了就是 03 没跑
select column_name from information_schema.columns
where table_schema = 'ry-vue' and table_name = 'takeout_dish'
  and column_name in ('has_spec', 'sort_no');

select column_name from information_schema.columns
where table_schema = 'ry-vue' and table_name = 'takeout_order'
  and column_name in ('address_id', 'item_count', 'expect_time',
                      'deliver_time', 'receive_time',
                      'cancel_reason', 'cancel_time', 'review_status');

-- 规格与热搜词要有数据
select count(*) from takeout_dish_spec;
select count(*) from takeout_dish_spec_option;
select keyword from takeout_hot_keyword order by sort_no;
```

**预期**：12 张表；`has_spec` / `sort_no` 存在；订单表 8 个新列齐全；规格与热搜词非空。

**注意**：`03` 开头会 `DROP TABLE`，重复执行会清空 P2 业务数据。

---

## 步骤 3：启动后端

```bash
bash server/start-redis.sh     # 若依的 token 存 Redis，不起来登录必失败
bash server/start-backend.sh
```

**预期**：日志出现 `Started RuoYiApplication`，端口 8080。

**失败看**：

| 现象 | 原因 |
| --- | --- |
| `Unable to connect to Redis` | Redis 没起 |
| `Access denied for user 'root'` | `application-druid.yml` 里的密码与本机 MySQL 不一致 |
| `Table 'ry-vue.takeout_shop' doesn't exist` | 步骤 2 没做，或库名不对 |

---

## 步骤 4：接口冒烟

```bash
bash docs/verify-api.sh
# 后端不在本机时：
BASE=http://192.168.x.x:8080 bash docs/verify-api.sh
```

脚本按客户端真实顺序打一遍：登录 → 首页 → 店铺列表/详情 → 搜索 → 收藏 → 地址 → 结算预览 → 下单 → 幂等 → 订单 → 支付。

**预期**：全部 PASS，末尾 `结果：N 通过，0 失败`。两处额外断言：

- `重复 clientToken 被拒绝` —— 幂等生效
- `重复支付被拒绝` —— 状态机 CAS 生效

**失败看**：

| FAIL 项 | 大概率原因 |
| --- | --- |
| 登录失败 | Redis 没起；`admin/admin123` 被改过 |
| `/app/home/index` | `takeout_hot_keyword` 表不存在（03 没跑） |
| `/app/shop/1` | `has_spec` / `sort_no` 列不存在（03 没跑） |
| `/app/order/preview` 报「请选择…」 | 说明脚本挑的菜有必选规格——这其实是**正确行为**，说明规格校验生效了 |
| `/app/order/create` 报「还差 ¥x 起送」 | 起送价高于所选菜品金额，正常 |
| 任一接口 500 且日志出现 `Invalid bound statement` | 若依的 `@MapperScan` 没扫到 `com.ruoyi.takeout.mapper` |

---

## 步骤 5：装机走主链路

```bash
cd android && ./gradlew :app:installDebug
adb shell am start -n com.szc.learning/.ui.login.LoginActivity
```

按这条路径走，每一步都有明确的检查点：

| # | 操作 | 检查点 |
| --- | --- | --- |
| 1 | 登录 admin/admin123 | 进首页，问候语显示「早上好，管理员」 |
| 2 | 首页看金刚区 | 分类 Chip 有内容；推荐店铺 5 家，按评分降序 |
| 3 | 点首页搜索框 | 进搜索页，热搜词与数据库一致 |
| 4 | 点热搜词「牛肉面」 | 结果列表有店铺 |
| 5 | 从搜索进店 | 店铺详情左分类右菜品，**初始滚动到对应分类** |
| 6 | 左栏点「小菜」 | 右侧滚到小菜分组，左栏高亮跟随 |
| 7 | 右侧手动滚到底 | 左栏高亮跟着变，不抖 |
| 8 | 点有规格的菜「＋」 | 弹规格窗，必选组未选时按钮置灰 |
| 9 | 选「大份/微辣」加入 | 行上显示「已选：大份/微辣」，单价 +4 |
| 10 | 点无规格的菜「＋」 | 直接加车，不弹窗 |
| 11 | 杀进程重进 | **购物车还在**（DataStore 恢复） |
| 12 | 底部合计不到起送价 | 结算按钮灰，提示「差 ¥x 起送」 |
| 13 | 加够金额去结算 | 金额与店铺详情底部一致 |
| 14 | 结算页换地址 | 金额重新拉一次（服务端算），不是本地变 |
| 15 | 提交订单 | 跳收银台，倒计时 15 分钟 |
| 16 | 确认支付 | 弹「支付成功」→ 查看订单 |
| 17 | 订单详情 | 时间轴：已下单 → 已支付 |
| 18 | 等 1 分钟再看详情 | 状态自动变「配送中」，时间轴多一条「商家已接单」 |
| 19 | 确认收货 | 状态变已完成，时间轴到「已送达」 |
| 20 | 我的 → 待支付 | 空列表提示「这个分类下还没有订单」 |
| 21 | 长按收藏页的店 | 弹取消收藏，确认后从列表消失 |

### 额外验两条边界

| 场景 | 预期 |
| --- | --- |
| 下单后立刻再点一次提交 | 「订单正在提交，请勿重复点击」（幂等） |
| 结算页停留超过 15 分钟再提交 | 「还差」或「订单状态已变更」——订单已被惰性取消 |

---

## 步骤 6：若依后台确认数据

后台 `admin/` 起来后，直接查库更快：

```sql
-- 订单与明细对得上
select o.order_id, o.order_no, o.item_count, o.pay_amount, o.status,
       (select count(*) from takeout_order_item i where i.order_id = o.order_id) as items
from takeout_order o order by o.order_id desc limit 5;

-- 状态流转有记录
select * from takeout_order_status_log order by log_id desc limit 10;

-- 销量被正确增减（取消订单会扣回）
select dish_id, dish_name, sales from takeout_dish where dish_id in (1,2,3);
```

**检查点**：`item_count` == 明细里数量之和；每笔订单至少有「下单」一条日志；支付后多一条「支付」。

---

## 已知的演示期妥协（不是 bug）

| 现象 | 原因 |
| --- | --- |
| 支付后 1 分钟自动变「配送中」 | `markDelivered()` 模拟商家接单，后台接单上线后删掉 |
| 店铺详情收藏按钮点了立刻变「已收藏」 | 乐观更新，接口失败会弹错但不回滚（数据下次进店会纠正） |
| 首页定位固定「郑州市金水区」 | 定位能力未接入 |
| 菜品搜索接口有、客户端没入口 | 客户端搜索页只做了店铺搜索，菜品需要另一种列表布局 |
