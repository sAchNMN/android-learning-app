-- ============================================================
-- 外卖点餐 - 初始化数据
-- 前置：已执行 01_takeout_schema.sql
-- ============================================================

SET NAMES utf8mb4;

DELETE FROM `takeout_dish`;
DELETE FROM `takeout_shop`;

INSERT INTO `takeout_shop`
(`shop_id`, `shop_name`, `description`, `category`, `address`, `phone`, `delivery_fee`, `min_amount`, `delivery_time`, `rating`, `status`, `create_by`, `create_time`)
VALUES
(1, '老王牛肉面', '手工拉面，牛骨慢炖六小时', '面食', '郑州市金水区文化路 12 号', '0371-88880001', 3.00, 15.00, 25, 4.8, '0', 'admin', sysdate()),
(2, '川味小厨', '地道川菜，麻辣鲜香', '川菜', '郑州市金水区花园路 88 号', '0371-88880002', 4.00, 20.00, 35, 4.6, '0', 'admin', sysdate()),
(3, '元气轻食', '低卡轻食，每日现做', '轻食', '郑州市郑东新区商务外环 5 号', '0371-88880003', 2.00, 18.00, 30, 4.9, '0', 'admin', sysdate());

INSERT INTO `takeout_dish`
(`shop_id`, `dish_name`, `description`, `price`, `category`, `sales`, `status`, `create_by`, `create_time`)
VALUES
(1, '红烧牛肉面', '大块牛腩，汤浓面劲', 22.00, '招牌面', 328, '0', 'admin', sysdate()),
(1, '清汤牛肉面', '原汤清亮，牛肉片足量', 20.00, '招牌面', 215, '0', 'admin', sysdate()),
(1, '凉拌黄瓜', '爽口解腻', 8.00, '小菜', 96, '0', 'admin', sysdate()),
(1, '卤蛋', '秘制卤汁浸泡', 3.00, '小菜', 412, '0', 'admin', sysdate()),

(2, '麻婆豆腐', '嫩豆腐配牛肉末', 26.00, '热菜', 187, '0', 'admin', sysdate()),
(2, '水煮牛肉', '麻辣过瘾，牛肉滑嫩', 48.00, '热菜', 143, '0', 'admin', sysdate()),
(2, '鱼香肉丝', '酸甜微辣，下饭首选', 32.00, '热菜', 205, '0', 'admin', sysdate()),
(2, '米饭', '东北大米', 2.00, '主食', 890, '0', 'admin', sysdate()),

(3, '鸡胸肉沙拉', '低温慢煮鸡胸，配油醋汁', 28.00, '沙拉', 156, '0', 'admin', sysdate()),
(3, '牛油果三明治', '全麦面包，牛油果鸡蛋', 24.00, '轻食', 132, '0', 'admin', sysdate()),
(3, '无糖酸奶', '自制发酵，零添加', 12.00, '饮品', 241, '0', 'admin', sysdate());
