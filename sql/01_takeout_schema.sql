-- ============================================================
-- 外卖点餐 - 表结构
-- 前置：已导入若依基础表（ry_*.sql）
-- 字段风格与若依一致：create_by / create_time / update_by /
-- update_time / remark / del_flag
-- ============================================================

SET NAMES utf8mb4;

DROP TABLE IF EXISTS `takeout_order_item`;
DROP TABLE IF EXISTS `takeout_order`;
DROP TABLE IF EXISTS `takeout_dish`;
DROP TABLE IF EXISTS `takeout_shop`;

-- ------------------------------------------------------------
-- 店铺
-- ------------------------------------------------------------
CREATE TABLE `takeout_shop` (
  `shop_id`      bigint(20)    NOT NULL AUTO_INCREMENT COMMENT '店铺ID',
  `shop_name`    varchar(64)   NOT NULL                COMMENT '店铺名称',
  `description`  varchar(255)  DEFAULT NULL            COMMENT '简介',
  `category`     varchar(32)   DEFAULT NULL            COMMENT '分类（快餐/面食/轻食…）',
  `address`      varchar(128)  DEFAULT NULL            COMMENT '地址',
  `phone`        varchar(20)   DEFAULT NULL            COMMENT '联系电话',
  `delivery_fee` decimal(10,2) DEFAULT 0.00            COMMENT '配送费',
  `min_amount`   decimal(10,2) DEFAULT 0.00            COMMENT '起送金额',
  `delivery_time` int(11)      DEFAULT 30              COMMENT '预计送达分钟',
  `rating`       decimal(2,1)  DEFAULT 5.0             COMMENT '评分',
  `status`       char(1)       DEFAULT '0'             COMMENT '状态（0营业 1休息）',
  `del_flag`     char(1)       DEFAULT '0'             COMMENT '删除标志（0存在 2删除）',
  `create_by`    varchar(64)   DEFAULT ''              COMMENT '创建者',
  `create_time`  datetime      DEFAULT NULL            COMMENT '创建时间',
  `update_by`    varchar(64)   DEFAULT ''              COMMENT '更新者',
  `update_time`  datetime      DEFAULT NULL            COMMENT '更新时间',
  `remark`       varchar(500)  DEFAULT NULL            COMMENT '备注',
  PRIMARY KEY (`shop_id`),
  KEY `idx_shop_status` (`status`, `del_flag`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='外卖店铺';

-- ------------------------------------------------------------
-- 菜品
-- ------------------------------------------------------------
CREATE TABLE `takeout_dish` (
  `dish_id`      bigint(20)    NOT NULL AUTO_INCREMENT COMMENT '菜品ID',
  `shop_id`      bigint(20)    NOT NULL                COMMENT '所属店铺',
  `dish_name`    varchar(64)   NOT NULL                COMMENT '菜品名称',
  `description`  varchar(255)  DEFAULT NULL            COMMENT '描述',
  `price`        decimal(10,2) NOT NULL                COMMENT '单价',
  `category`     varchar(32)   DEFAULT NULL            COMMENT '菜品分类',
  `sales`        int(11)       DEFAULT 0               COMMENT '销量',
  `status`       char(1)       DEFAULT '0'             COMMENT '状态（0上架 1下架）',
  `del_flag`     char(1)       DEFAULT '0'             COMMENT '删除标志（0存在 2删除）',
  `create_by`    varchar(64)   DEFAULT ''              COMMENT '创建者',
  `create_time`  datetime      DEFAULT NULL            COMMENT '创建时间',
  `update_by`    varchar(64)   DEFAULT ''              COMMENT '更新者',
  `update_time`  datetime      DEFAULT NULL            COMMENT '更新时间',
  `remark`       varchar(500)  DEFAULT NULL            COMMENT '备注',
  PRIMARY KEY (`dish_id`),
  KEY `idx_dish_shop` (`shop_id`, `status`, `del_flag`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='菜品';

-- ------------------------------------------------------------
-- 订单
-- status: 0待支付 1已支付 2配送中 3已完成 4已取消
-- ------------------------------------------------------------
CREATE TABLE `takeout_order` (
  `order_id`     bigint(20)    NOT NULL AUTO_INCREMENT COMMENT '订单ID',
  `order_no`     varchar(32)   NOT NULL                COMMENT '订单号',
  `user_id`      bigint(20)    NOT NULL                COMMENT '下单用户（sys_user.user_id）',
  `shop_id`      bigint(20)    NOT NULL                COMMENT '店铺ID',
  `shop_name`    varchar(64)   DEFAULT NULL            COMMENT '店铺名称快照',
  `total_amount` decimal(10,2) NOT NULL                COMMENT '商品总额',
  `delivery_fee` decimal(10,2) DEFAULT 0.00            COMMENT '配送费',
  `pay_amount`   decimal(10,2) NOT NULL                COMMENT '实付金额',
  `status`       char(1)       DEFAULT '0'             COMMENT '状态（0待支付 1已支付 2配送中 3已完成 4已取消）',
  `receiver`     varchar(32)   DEFAULT NULL            COMMENT '收货人',
  `phone`        varchar(20)   DEFAULT NULL            COMMENT '联系电话',
  `address`      varchar(128)  DEFAULT NULL            COMMENT '收货地址',
  `remark`       varchar(255)  DEFAULT NULL            COMMENT '备注',
  `pay_time`     datetime      DEFAULT NULL            COMMENT '支付时间',
  `finish_time`  datetime      DEFAULT NULL            COMMENT '完成时间',
  `create_time`  datetime      DEFAULT NULL            COMMENT '下单时间',
  `update_time`  datetime      DEFAULT NULL            COMMENT '更新时间',
  PRIMARY KEY (`order_id`),
  UNIQUE KEY `uk_order_no` (`order_no`),
  KEY `idx_order_user` (`user_id`, `create_time`),
  KEY `idx_order_status` (`status`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='外卖订单';

-- ------------------------------------------------------------
-- 订单明细（下单时把菜名和单价快照下来，避免菜品改价影响历史订单）
-- ------------------------------------------------------------
CREATE TABLE `takeout_order_item` (
  `item_id`      bigint(20)    NOT NULL AUTO_INCREMENT COMMENT '明细ID',
  `order_id`     bigint(20)    NOT NULL                COMMENT '订单ID',
  `dish_id`      bigint(20)    NOT NULL                COMMENT '菜品ID',
  `dish_name`    varchar(64)   NOT NULL                COMMENT '菜品名称快照',
  `price`        decimal(10,2) NOT NULL                COMMENT '单价快照',
  `quantity`     int(11)       NOT NULL                COMMENT '数量',
  `subtotal`     decimal(10,2) NOT NULL                COMMENT '小计',
  PRIMARY KEY (`item_id`),
  KEY `idx_item_order` (`order_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单明细';
