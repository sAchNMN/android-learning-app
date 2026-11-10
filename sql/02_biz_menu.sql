-- ============================================================
-- 学习助手 - 后台菜单与权限初始化
-- 前置：已导入若依基础表，且 sys_menu 存在
-- menu_id 使用 2000 段，避开若依自带的 1~1100
-- ============================================================

SET NAMES utf8mb4;

-- 一级目录
DELETE FROM `sys_menu` WHERE `menu_id` BETWEEN 2000 AND 2099;
INSERT INTO `sys_menu`
(`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `create_by`, `create_time`, `remark`)
VALUES
(2000, '学习管理', 0, 4, 'learning', NULL, '', 1, 0, 'M', '0', '0', '', 'education', 'admin', sysdate(), '学习助手业务菜单');

-- 课程管理
INSERT INTO `sys_menu`
(`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `create_by`, `create_time`, `remark`)
VALUES
(2001, '课程管理', 2000, 1, 'course', 'learning/course/index', '', 1, 0, 'C', '0', '0', 'learning:course:list', 'list', 'admin', sysdate(), ''),
(2002, '章节管理', 2000, 2, 'chapter', 'learning/chapter/index', '', 1, 0, 'C', '0', '0', 'learning:chapter:list', 'tree', 'admin', sysdate(), ''),
(2003, '题目管理', 2000, 3, 'question', 'learning/question/index', '', 1, 0, 'C', '0', '0', 'learning:question:list', 'form', 'admin', sysdate(), ''),
(2004, '学习记录', 2000, 4, 'record', 'learning/record/index', '', 1, 0, 'C', '0', '0', 'learning:record:list', 'chart', 'admin', sysdate(), '');

-- 按钮级权限：课程
INSERT INTO `sys_menu`
(`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `create_by`, `create_time`, `remark`)
VALUES
(2011, '课程查询', 2001, 1, '', '', '', 1, 0, 'F', '0', '0', 'learning:course:query', '#', 'admin', sysdate(), ''),
(2012, '课程新增', 2001, 2, '', '', '', 1, 0, 'F', '0', '0', 'learning:course:add',   '#', 'admin', sysdate(), ''),
(2013, '课程修改', 2001, 3, '', '', '', 1, 0, 'F', '0', '0', 'learning:course:edit',  '#', 'admin', sysdate(), ''),
(2014, '课程删除', 2001, 4, '', '', '', 1, 0, 'F', '0', '0', 'learning:course:remove','#', 'admin', sysdate(), ''),

-- 按钮级权限：章节
(2021, '章节查询', 2002, 1, '', '', '', 1, 0, 'F', '0', '0', 'learning:chapter:query', '#', 'admin', sysdate(), ''),
(2022, '章节新增', 2002, 2, '', '', '', 1, 0, 'F', '0', '0', 'learning:chapter:add',   '#', 'admin', sysdate(), ''),
(2023, '章节修改', 2002, 3, '', '', '', 1, 0, 'F', '0', '0', 'learning:chapter:edit',  '#', 'admin', sysdate(), ''),
(2024, '章节删除', 2002, 4, '', '', '', 1, 0, 'F', '0', '0', 'learning:chapter:remove','#', 'admin', sysdate(), ''),

-- 按钮级权限：题目
(2031, '题目查询', 2003, 1, '', '', '', 1, 0, 'F', '0', '0', 'learning:question:query', '#', 'admin', sysdate(), ''),
(2032, '题目新增', 2003, 2, '', '', '', 1, 0, 'F', '0', '0', 'learning:question:add',   '#', 'admin', sysdate(), ''),
(2033, '题目修改', 2003, 3, '', '', '', 1, 0, 'F', '0', '0', 'learning:question:edit',  '#', 'admin', sysdate(), ''),
(2034, '题目删除', 2003, 4, '', '', '', 1, 0, 'F', '0', '0', 'learning:question:remove','#', 'admin', sysdate(), ''),

-- 按钮级权限：学习记录
(2041, '记录查询', 2004, 1, '', '', '', 1, 0, 'F', '0', '0', 'learning:record:query', '#', 'admin', sysdate(), ''),
(2042, '记录导出', 2004, 2, '', '', '', 1, 0, 'F', '0', '0', 'learning:record:export','#', 'admin', sysdate(), '');

-- ------------------------------------------------------------
-- 建议：在「角色管理」里新建角色 app_user，
-- 授予 2001~2004 的查询权限即可（App 端只读）。
-- 不要把 app_user 配成 admin，否则 App 端能调后台所有接口。
-- ------------------------------------------------------------
