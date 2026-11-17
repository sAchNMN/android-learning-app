-- ============================================================
-- 外卖点餐 P2 - 初始化数据
-- 前置：01_takeout_schema.sql + 02_takeout_data.sql + 03_takeout_p2_schema.sql
--
-- 不预置收货地址与订单：它们跟 sys_user.user_id 绑定，
-- 由客户端新增，预置了也登录不上。
-- ============================================================

SET NAMES utf8mb4;

-- ------------------------------------------------------------
-- 店铺分类（首页金刚区顺序 = sort_no）
-- ------------------------------------------------------------
DELETE FROM `takeout_shop_category`;

INSERT INTO `takeout_shop_category`
(`category_id`, `category_name`, `icon`, `sort_no`, `status`, `create_by`, `create_time`)
VALUES
(1, '快餐便当', 'ic_cat_fastfood', 10, '0', 'admin', sysdate()),
(2, '面食',     'ic_cat_noodle',   20, '0', 'admin', sysdate()),
(3, '川湘菜',   'ic_cat_sichuan',  30, '0', 'admin', sysdate()),
(4, '轻食沙拉', 'ic_cat_salad',    40, '0', 'admin', sysdate()),
(5, '饮品甜品', 'ic_cat_drink',    50, '0', 'admin', sysdate()),
(6, '烧烤炸串', 'ic_cat_bbq',      60, '0', 'admin', sysdate());

-- ------------------------------------------------------------
-- 回填店铺分类（category 文本保留，作为展示冗余）
-- ------------------------------------------------------------
UPDATE `takeout_shop` SET `category_id` = 2, `month_sales` = 1286, `open_time` = '09:00-21:30'
WHERE `shop_name` = '老王牛肉面';

UPDATE `takeout_shop` SET `category_id` = 3, `month_sales` = 934,  `open_time` = '10:00-22:00'
WHERE `shop_name` = '川味小厨';

UPDATE `takeout_shop` SET `category_id` = 4, `month_sales` = 642,  `open_time` = '08:30-20:00'
WHERE `shop_name` = '元气轻食';

-- ------------------------------------------------------------
-- 菜品排序：同分类内手工指定，分类之间的先后也按最小值决定
-- ------------------------------------------------------------
UPDATE `takeout_dish` SET `sort_no` = 10 WHERE `dish_name` = '红烧牛肉面';
UPDATE `takeout_dish` SET `sort_no` = 20 WHERE `dish_name` = '清汤牛肉面';
UPDATE `takeout_dish` SET `sort_no` = 10 WHERE `dish_name` = '凉拌黄瓜';
UPDATE `takeout_dish` SET `sort_no` = 20 WHERE `dish_name` = '卤蛋';
UPDATE `takeout_dish` SET `sort_no` = 10 WHERE `dish_name` = '麻婆豆腐';
UPDATE `takeout_dish` SET `sort_no` = 20 WHERE `dish_name` = '水煮牛肉';
UPDATE `takeout_dish` SET `sort_no` = 30 WHERE `dish_name` = '鱼香肉丝';
UPDATE `takeout_dish` SET `sort_no` = 40 WHERE `dish_name` = '米饭';
UPDATE `takeout_dish` SET `sort_no` = 10 WHERE `dish_name` = '鸡胸肉沙拉';
UPDATE `takeout_dish` SET `sort_no` = 20 WHERE `dish_name` = '牛油果三明治';
UPDATE `takeout_dish` SET `sort_no` = 10 WHERE `dish_name` = '无糖酸奶';

-- ------------------------------------------------------------
-- 规格：红烧牛肉面（份量 + 辣度）
-- ------------------------------------------------------------
SET @dish = (SELECT `dish_id` FROM `takeout_dish` WHERE `dish_name` = '红烧牛肉面' ORDER BY `dish_id` LIMIT 1);
UPDATE `takeout_dish` SET `has_spec` = '1' WHERE `dish_id` = @dish;

INSERT INTO `takeout_dish_spec` (`dish_id`, `spec_name`, `required`, `multi`, `sort_no`, `create_time`)
VALUES (@dish, '份量', '0', '0', 10, sysdate());
SET @spec = LAST_INSERT_ID();
INSERT INTO `takeout_dish_spec_option` (`spec_id`, `option_name`, `price_delta`, `is_default`, `sort_no`, `status`)
VALUES
(@spec, '标准份', 0.00, '1', 10, '0'),
(@spec, '大份',   4.00, '0', 20, '0');

INSERT INTO `takeout_dish_spec` (`dish_id`, `spec_name`, `required`, `multi`, `sort_no`, `create_time`)
VALUES (@dish, '辣度', '0', '0', 20, sysdate());
SET @spec = LAST_INSERT_ID();
INSERT INTO `takeout_dish_spec_option` (`spec_id`, `option_name`, `price_delta`, `is_default`, `sort_no`, `status`)
VALUES
(@spec, '不辣',   0.00, '0', 10, '0'),
(@spec, '微辣',   0.00, '1', 20, '0'),
(@spec, '中辣',   0.00, '0', 30, '0'),
(@spec, '特辣',   0.00, '0', 40, '0');

-- ------------------------------------------------------------
-- 规格：水煮牛肉（辣度）
-- ------------------------------------------------------------
SET @dish = (SELECT `dish_id` FROM `takeout_dish` WHERE `dish_name` = '水煮牛肉' ORDER BY `dish_id` LIMIT 1);
UPDATE `takeout_dish` SET `has_spec` = '1' WHERE `dish_id` = @dish;

INSERT INTO `takeout_dish_spec` (`dish_id`, `spec_name`, `required`, `multi`, `sort_no`, `create_time`)
VALUES (@dish, '辣度', '0', '0', 10, sysdate());
SET @spec = LAST_INSERT_ID();
INSERT INTO `takeout_dish_spec_option` (`spec_id`, `option_name`, `price_delta`, `is_default`, `sort_no`, `status`)
VALUES
(@spec, '微辣', 0.00, '0', 10, '0'),
(@spec, '中辣', 0.00, '1', 20, '0'),
(@spec, '特辣', 0.00, '0', 30, '0');

-- ------------------------------------------------------------
-- 规格：麻婆豆腐（份量）
-- ------------------------------------------------------------
SET @dish = (SELECT `dish_id` FROM `takeout_dish` WHERE `dish_name` = '麻婆豆腐' ORDER BY `dish_id` LIMIT 1);
UPDATE `takeout_dish` SET `has_spec` = '1' WHERE `dish_id` = @dish;

INSERT INTO `takeout_dish_spec` (`dish_id`, `spec_name`, `required`, `multi`, `sort_no`, `create_time`)
VALUES (@dish, '份量', '0', '0', 10, sysdate());
SET @spec = LAST_INSERT_ID();
INSERT INTO `takeout_dish_spec_option` (`spec_id`, `option_name`, `price_delta`, `is_default`, `sort_no`, `status`)
VALUES
(@spec, '标准份', 0.00, '1', 10, '0'),
(@spec, '大份',   6.00, '0', 20, '0');

-- ------------------------------------------------------------
-- 规格：鱼香肉丝（加料，可选多选）
-- ------------------------------------------------------------
SET @dish = (SELECT `dish_id` FROM `takeout_dish` WHERE `dish_name` = '鱼香肉丝' ORDER BY `dish_id` LIMIT 1);
UPDATE `takeout_dish` SET `has_spec` = '1' WHERE `dish_id` = @dish;

INSERT INTO `takeout_dish_spec` (`dish_id`, `spec_name`, `required`, `multi`, `sort_no`, `create_time`)
VALUES (@dish, '加料（可多选）', '1', '1', 10, sysdate());
SET @spec = LAST_INSERT_ID();
INSERT INTO `takeout_dish_spec_option` (`spec_id`, `option_name`, `price_delta`, `is_default`, `sort_no`, `status`)
VALUES
(@spec, '加肉丝', 8.00, '0', 10, '0'),
(@spec, '加木耳', 3.00, '0', 20, '0'),
(@spec, '加笋丝', 3.00, '0', 30, '0');

-- ------------------------------------------------------------
-- 规格：鸡胸肉沙拉（酱料）
-- ------------------------------------------------------------
SET @dish = (SELECT `dish_id` FROM `takeout_dish` WHERE `dish_name` = '鸡胸肉沙拉' ORDER BY `dish_id` LIMIT 1);
UPDATE `takeout_dish` SET `has_spec` = '1' WHERE `dish_id` = @dish;

INSERT INTO `takeout_dish_spec` (`dish_id`, `spec_name`, `required`, `multi`, `sort_no`, `create_time`)
VALUES (@dish, '酱料', '0', '0', 10, sysdate());
SET @spec = LAST_INSERT_ID();
INSERT INTO `takeout_dish_spec_option` (`spec_id`, `option_name`, `price_delta`, `is_default`, `sort_no`, `status`)
VALUES
(@spec, '油醋汁',   0.00, '1', 10, '0'),
(@spec, '和风芝麻', 1.00, '0', 20, '0'),
(@spec, '凯撒酱',   2.00, '0', 30, '0');

-- ------------------------------------------------------------
-- 规格：无糖酸奶（杯型）
-- ------------------------------------------------------------
SET @dish = (SELECT `dish_id` FROM `takeout_dish` WHERE `dish_name` = '无糖酸奶' ORDER BY `dish_id` LIMIT 1);
UPDATE `takeout_dish` SET `has_spec` = '1' WHERE `dish_id` = @dish;

INSERT INTO `takeout_dish_spec` (`dish_id`, `spec_name`, `required`, `multi`, `sort_no`, `create_time`)
VALUES (@dish, '规格', '0', '0', 10, sysdate());
SET @spec = LAST_INSERT_ID();
INSERT INTO `takeout_dish_spec_option` (`spec_id`, `option_name`, `price_delta`, `is_default`, `sort_no`, `status`)
VALUES
(@spec, '单杯',   0.00,  '1', 10, '0'),
(@spec, '三杯装', 20.00, '0', 20, '0');

-- ------------------------------------------------------------
-- 热搜词
-- ------------------------------------------------------------
DELETE FROM `takeout_hot_keyword`;

INSERT INTO `takeout_hot_keyword` (`keyword`, `sort_no`, `status`, `create_time`)
VALUES
('牛肉面',     10, '0', sysdate()),
('麻辣烫',     20, '0', sysdate()),
('沙拉',       30, '0', sysdate()),
('水煮牛肉',   40, '0', sysdate()),
('卤蛋',       50, '0', sysdate()),
('轻食',       60, '0', sysdate()),
('炸鸡',       70, '0', sysdate()),
('奶茶',       80, '0', sysdate());
