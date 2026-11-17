# SQL 脚本

业务表按阶段分脚本，往后新增业务仍用 `0N_xxx_schema.sql` / `0N_xxx_data.sql` 成对命名。

| 文件 | 内容 | 状态 |
| --- | --- | --- |
| `01_takeout_schema.sql` | 外卖 4 张基础表：店铺、菜品、订单、订单明细 | 已执行 |
| `02_takeout_data.sql` | 3 家店铺 + 11 道菜品 | 已执行 |
| `03_takeout_p2_schema.sql` | P2 新增 8 张表 + 4 张表扩字段 | 待执行 |
| `04_takeout_p2_data.sql` | 分类、规格、热搜词、店铺分类回填 | 待执行 |

若依自带的表结构在 `server/RuoYi-Vue/sql/`：

| 文件 | 内容 |
| --- | --- |
| `ry_20260417.sql` | 系统表（用户、角色、菜单、字典、参数等） |
| `quartz.sql` | 定时任务表 |

## 导入顺序（不能改）

```bash
MYSQL="/c/Program Files/MySQL/MySQL Server 26.10/bin/mysql.exe"
"$MYSQL" -uroot -p ry-vue < server/RuoYi-Vue/sql/ry_20260417.sql
"$MYSQL" -uroot -p ry-vue < server/RuoYi-Vue/sql/quartz.sql
"$MYSQL" -uroot -p ry-vue < sql/01_takeout_schema.sql
"$MYSQL" -uroot -p ry-vue < sql/02_takeout_data.sql
"$MYSQL" -uroot -p ry-vue < sql/03_takeout_p2_schema.sql
"$MYSQL" -uroot -p ry-vue < sql/04_takeout_p2_data.sql
```

`03` 的 **ALTER 段落只能执行一次**，重跑会报 `Duplicate column name`。
新增列已生效时只重跑 `CREATE TABLE` 段落即可。

## 表清单（12 张）

| 表 | 阶段 | 说明 |
| --- | --- | --- |
| `takeout_shop` | P1 | 店铺（P2 加分类、月售、营业时段、头图） |
| `takeout_dish` | P1 | 菜品（P2 加规格标记、排序） |
| `takeout_order` | P1 | 订单（P2 加地址、件数、时间戳、取消信息、评价状态） |
| `takeout_order_item` | P1 | 订单明细（P2 加规格快照） |
| `takeout_shop_category` | P2 | 店铺分类（金刚区 / 筛选） |
| `takeout_dish_spec` | P2 | 菜品规格组 |
| `takeout_dish_spec_option` | P2 | 菜品规格选项（含加价） |
| `takeout_address` | P2 | 收货地址簿 |
| `takeout_order_status_log` | P2 | 订单状态流转日志（审计用） |
| `takeout_review` | P2 | 订单评价 |
| `takeout_shop_favorite` | P2 | 店铺收藏 |
| `takeout_hot_keyword` | P2 | 热搜关键词 |

设计理由见 [`docs/data-model.md`](../docs/data-model.md)。

## 加业务表时

1. 放在本目录，按阶段编号，命名 `0N_xxx_schema.sql`（结构）、`0N_xxx_data.sql`（初始化数据）
2. 字段风格跟若依保持一致：`create_by` / `create_time` / `update_by` / `update_time` / `remark` / `del_flag`
3. 表名加业务前缀，避免和 `sys_*`、`gen_*`、`qrtz_*` 撞
4. 同步更新 `server/README.md` 里的导入顺序
