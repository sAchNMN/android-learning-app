# SQL 脚本

**业务表尚未设计。** 这里暂时只有约定，没有脚本。

若依自带的表结构在 `server/RuoYi-Vue/sql/`：

| 文件 | 内容 |
| --- | --- |
| `ry_20260417.sql` | 系统表（用户、角色、菜单、字典、参数等） |
| `quartz.sql` | 定时任务表 |

## 加业务表时

1. 放在本目录，命名 `01_xxx_schema.sql`（结构）、`02_xxx_data.sql`（初始化数据）
2. 字段风格跟若依保持一致：`create_by` / `create_time` / `update_by` / `update_time` / `remark` / `del_flag`
3. 表名加业务前缀，避免和 `sys_*`、`gen_*`、`qrtz_*` 撞
4. 同步更新 `server/README.md` 里的导入顺序
