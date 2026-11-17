# SQL 脚本

本目录是业务表的版本化脚本。当前有效业务是**云端曲库**；
外卖点餐时代的 `01`~`04` 脚本保留在历史里，表结构仍在，但 App 端已经不再使用。

| 文件 | 内容 |
| --- | --- |
| `05_music_schema.sql` | `biz_music_track` 云端曲库表 + 8 条演示数据（接入步骤见 `docs/cloud-music.md`） |

历史脚本（外卖点餐，已下线）：

| 文件 | 内容 |
| --- | --- |
| `01_takeout_schema.sql` | 四张业务表：`takeout_shop` / `takeout_dish` / `takeout_order` / `takeout_order_item` |
| `02_takeout_data.sql` | 店铺与菜品的初始化数据 |

前置条件是先导入若依自带的表结构，脚本在 `server/RuoYi-Vue/sql/`：

| 文件 | 内容 |
| --- | --- |
| `ry_20260417.sql` | 系统表（用户、角色、菜单、字典、参数等） |
| `quartz.sql` | 定时任务表 |

完整导入顺序见仓库根 `README.md` 的「快速开始」。

## 加业务表时

1. 放在本目录，命名 `01_xxx_schema.sql`（结构）、`02_xxx_data.sql`（初始化数据）
2. 字段风格跟若依保持一致：`create_by` / `create_time` / `update_by` / `update_time` / `remark` / `del_flag`
3. 表名加业务前缀，避免和 `sys_*`、`gen_*`、`qrtz_*` 撞
4. 同步更新 `server/README.md` 里的导入顺序
