-- ============================================================
-- 外卖点餐 P2 - 二级/三级页面配套表结构
-- 前置：已执行 01_takeout_schema.sql
--
-- 本脚本分两段，执行顺序不能颠倒：
--   第一段：CREATE TABLE（可重复执行，先 DROP）
--   第二段：ALTER TABLE（一次性，重复执行会报 Duplicate column）
-- ============================================================

SET NAMES utf8mb4;

-- ============================================================
-- 第一段：新建表
-- ============================================================

DROP TABLE IF EXISTS `takeout_hot_keyword`;
DROP TABLE IF EXISTS `takeout_shop_favorite`;
DROP TABLE IF EXISTS `takeout_review`;
DROP TABLE IF EXISTS `takeout_order_status_log`;
DROP TABLE IF EXISTS `takeout_address`;
DROP TABLE IF EXISTS `takeout_dish_spec_option`;
DROP TABLE IF EXISTS `takeout_dish_spec`;
DROP TABLE IF EXISTS `takeout_shop_category`;

-- ------------------------------------------------------------
-- 店铺分类（首页金刚区 / 店铺列表筛选）
-- 注意：takeout_shop.category 是文本冗余，筛选走 category_id，
--       展示走文本。改名不影响历史数据。
-- ------------------------------------------------------------
CREATE TABLE `takeout_shop_category` (
  `category_id`   bigint(20)   NOT NULL AUTO_INCREMENT COMMENT '分类ID',
  `category_name` varchar(32)  NOT NULL                COMMENT '分类名称',
  `icon`          varchar(128) DEFAULT NULL            COMMENT '图标（drawable 名或图片URL）',
  `sort_no`       int(11)      DEFAULT 0               COMMENT '排序，越小越前',
  `status`        char(1)      DEFAULT '0'             COMMENT '状态（0显示 1隐藏）',
  `del_flag`      char(1)      DEFAULT '0'             COMMENT '删除标志（0存在 2删除）',
  `create_by`     varchar(64)  DEFAULT ''              COMMENT '创建者',
  `create_time`   datetime     DEFAULT NULL            COMMENT '创建时间',
  `update_by`     varchar(64)  DEFAULT ''              COMMENT '更新者',
  `update_time`   datetime     DEFAULT NULL            COMMENT '更新时间',
  `remark`        varchar(500) DEFAULT NULL            COMMENT '备注',
  PRIMARY KEY (`category_id`),
  UNIQUE KEY `uk_category_name` (`category_name`),
  KEY `idx_category_show` (`status`, `del_flag`, `sort_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='店铺分类';

-- ------------------------------------------------------------
-- 菜品规格组（如「份量」「辣度」）
-- required=0 表示该组必须选满一个才算合法组合，服务端下单要校验
-- ------------------------------------------------------------
CREATE TABLE `takeout_dish_spec` (
  `spec_id`     bigint(20)   NOT NULL AUTO_INCREMENT COMMENT '规格组ID',
  `dish_id`     bigint(20)   NOT NULL                COMMENT '菜品ID',
  `spec_name`   varchar(32)  NOT NULL                COMMENT '规格组名称',
  `required`    char(1)      DEFAULT '0'             COMMENT '是否必选（0必选 1可选）',
  `multi`       char(1)      DEFAULT '1'             COMMENT '是否多选（0单选 1多选）',
  `sort_no`     int(11)      DEFAULT 0               COMMENT '组排序',
  `del_flag`    char(1)      DEFAULT '0'             COMMENT '删除标志（0存在 2删除）',
  `create_time` datetime     DEFAULT NULL            COMMENT '创建时间',
  `update_time` datetime     DEFAULT NULL            COMMENT '更新时间',
  PRIMARY KEY (`spec_id`),
  KEY `idx_spec_dish` (`dish_id`, `del_flag`, `sort_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='菜品规格组';

-- ------------------------------------------------------------
-- 菜品规格选项（如「大份 +3.00」）
-- price_delta 是加价，不是最终价。最终单价 = dish.price + Σ price_delta
-- ------------------------------------------------------------
CREATE TABLE `takeout_dish_spec_option` (
  `option_id`   bigint(20)    NOT NULL AUTO_INCREMENT COMMENT '选项ID',
  `spec_id`     bigint(20)    NOT NULL                COMMENT '规格组ID',
  `option_name` varchar(32)   NOT NULL                COMMENT '选项名称',
  `price_delta` decimal(10,2) DEFAULT 0.00            COMMENT '加价（可为0）',
  `is_default`  char(1)       DEFAULT '0'             COMMENT '是否默认选中（0否 1是）',
  `sort_no`     int(11)       DEFAULT 0               COMMENT '选项排序',
  `status`      char(1)       DEFAULT '0'             COMMENT '状态（0可用 1停用）',
  `del_flag`    char(1)       DEFAULT '0'             COMMENT '删除标志（0存在 2删除）',
  PRIMARY KEY (`option_id`),
  KEY `idx_option_spec` (`spec_id`, `del_flag`, `sort_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='菜品规格选项';

-- ------------------------------------------------------------
-- 收货地址簿
-- 上限 20 条由服务端控制；默认地址必须唯一（设默认时先清同用户其它行）
-- ------------------------------------------------------------
CREATE TABLE `takeout_address` (
  `address_id`  bigint(20)   NOT NULL AUTO_INCREMENT COMMENT '地址ID',
  `user_id`     bigint(20)   NOT NULL                COMMENT '所属用户（sys_user.user_id）',
  `receiver`    varchar(32)  NOT NULL                COMMENT '收货人',
  `phone`       varchar(20)  NOT NULL                COMMENT '手机号',
  `province`    varchar(32)  DEFAULT NULL            COMMENT '省',
  `city`        varchar(32)  DEFAULT NULL            COMMENT '市',
  `district`    varchar(32)  DEFAULT NULL            COMMENT '区/县',
  `detail`      varchar(128) NOT NULL                COMMENT '详细地址（门牌号）',
  `tag`         varchar(8)   DEFAULT NULL            COMMENT '标签（家/公司/学校）',
  `is_default`  char(1)      DEFAULT '0'             COMMENT '是否默认（0否 1是）',
  `del_flag`    char(1)      DEFAULT '0'             COMMENT '删除标志（0存在 2删除）',
  `create_time` datetime     DEFAULT NULL            COMMENT '创建时间',
  `update_time` datetime     DEFAULT NULL            COMMENT '更新时间',
  PRIMARY KEY (`address_id`),
  KEY `idx_addr_user` (`user_id`, `del_flag`, `is_default`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='收货地址';

-- ------------------------------------------------------------
-- 订单状态流转日志（审计用，不参与页面渲染）
-- 时间轴直接取 takeout_order 上的关键时间戳，快且不会重复
-- ------------------------------------------------------------
CREATE TABLE `takeout_order_status_log` (
  `log_id`        bigint(20)   NOT NULL AUTO_INCREMENT COMMENT '日志ID',
  `order_id`      bigint(20)   NOT NULL                COMMENT '订单ID',
  `from_status`   char(1)      DEFAULT NULL            COMMENT '原状态（下单时为 NULL）',
  `to_status`     char(1)      NOT NULL                COMMENT '新状态',
  `operator_type` char(1)      DEFAULT '0'             COMMENT '操作方（0用户 1商家 2系统）',
  `operator_id`   bigint(20)   DEFAULT NULL            COMMENT '操作人ID（系统为 NULL）',
  `remark`        varchar(128) DEFAULT NULL            COMMENT '备注（取消原因等）',
  `create_time`   datetime     DEFAULT NULL            COMMENT '发生时间',
  PRIMARY KEY (`log_id`),
  KEY `idx_log_order` (`order_id`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单状态流转日志';

-- ------------------------------------------------------------
-- 订单评价（一单一评，靠 uk_order 兜底）
-- ------------------------------------------------------------
CREATE TABLE `takeout_review` (
  `review_id`       bigint(20)   NOT NULL AUTO_INCREMENT COMMENT '评价ID',
  `order_id`        bigint(20)   NOT NULL                COMMENT '订单ID',
  `shop_id`         bigint(20)   NOT NULL                COMMENT '店铺ID',
  `user_id`         bigint(20)   NOT NULL                COMMENT '评价人',
  `rating`          tinyint(1)   NOT NULL                COMMENT '总评 1-5',
  `taste_rating`    tinyint(1)   DEFAULT NULL            COMMENT '口味 1-5',
  `pack_rating`     tinyint(1)   DEFAULT NULL            COMMENT '包装 1-5',
  `delivery_rating` tinyint(1)   DEFAULT NULL            COMMENT '配送 1-5',
  `tags`            varchar(128) DEFAULT NULL            COMMENT '标签，逗号分隔',
  `content`         varchar(500) DEFAULT NULL            COMMENT '文字评价',
  `is_anonymous`    char(1)      DEFAULT '0'             COMMENT '是否匿名（0否 1是）',
  `reply`           varchar(255) DEFAULT NULL            COMMENT '商家回复',
  `reply_time`      datetime     DEFAULT NULL            COMMENT '回复时间',
  `del_flag`        char(1)      DEFAULT '0'             COMMENT '删除标志（0存在 2删除）',
  `create_time`     datetime     DEFAULT NULL            COMMENT '评价时间',
  PRIMARY KEY (`review_id`),
  UNIQUE KEY `uk_review_order` (`order_id`),
  KEY `idx_review_shop` (`shop_id`, `del_flag`, `create_time`),
  KEY `idx_review_user` (`user_id`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单评价';

-- ------------------------------------------------------------
-- 店铺收藏
-- ------------------------------------------------------------
CREATE TABLE `takeout_shop_favorite` (
  `fav_id`      bigint(20) NOT NULL AUTO_INCREMENT COMMENT '收藏ID',
  `user_id`     bigint(20) NOT NULL                COMMENT '用户ID',
  `shop_id`     bigint(20) NOT NULL                COMMENT '店铺ID',
  `create_time` datetime   DEFAULT NULL            COMMENT '收藏时间',
  PRIMARY KEY (`fav_id`),
  UNIQUE KEY `uk_fav_user_shop` (`user_id`, `shop_id`),
  KEY `idx_fav_user` (`user_id`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='店铺收藏';

-- ------------------------------------------------------------
-- 热搜词（搜索页与首页搜索框共用）
-- ------------------------------------------------------------
CREATE TABLE `takeout_hot_keyword` (
  `keyword_id`  bigint(20)   NOT NULL AUTO_INCREMENT COMMENT '主键',
  `keyword`     varchar(32)  NOT NULL                COMMENT '关键词',
  `sort_no`     int(11)      DEFAULT 0               COMMENT '排序',
  `status`      char(1)      DEFAULT '0'             COMMENT '状态（0启用 1停用）',
  `create_time` datetime     DEFAULT NULL            COMMENT '创建时间',
  PRIMARY KEY (`keyword_id`),
  UNIQUE KEY `uk_keyword` (`keyword`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='热搜关键词';

-- ============================================================
-- 第二段：既有表扩字段（只跑一次）
-- ============================================================

-- 店铺：分类外键、月售、营业时间、头图占位
ALTER TABLE `takeout_shop`
  ADD COLUMN `category_id`  bigint(20)   DEFAULT NULL COMMENT '分类ID' AFTER `category`,
  ADD COLUMN `month_sales`  int(11)      DEFAULT 0    COMMENT '月售' AFTER `rating`,
  ADD COLUMN `open_time`    varchar(11)  DEFAULT NULL COMMENT '营业时段，如 09:00-21:00' AFTER `delivery_time`,
  ADD COLUMN `image`        varchar(128) DEFAULT NULL COMMENT '头图（可为空，空时前端用色块）' AFTER `description`;

ALTER TABLE `takeout_shop`
  ADD KEY `idx_shop_category` (`category_id`, `status`, `del_flag`);

-- 菜品：是否有规格、组内排序
ALTER TABLE `takeout_dish`
  ADD COLUMN `has_spec` char(1) DEFAULT '0' COMMENT '是否有规格（0否 1是）' AFTER `category`,
  ADD COLUMN `sort_no`  int(11) DEFAULT 0   COMMENT '排序（同时决定分类分组顺序）' AFTER `sales`;

-- 订单：地址外键、件数冗余、关键时间戳
-- 拆成两条：第二条用 AFTER 引用第一条新增的 receive_time，
-- 同一条 ALTER 里引用本语句新增的列 MySQL 不保证，别赌。
ALTER TABLE `takeout_order`
  ADD COLUMN `address_id`    bigint(20)   DEFAULT NULL COMMENT '收货地址ID（地址簿来源，可为空）' AFTER `shop_name`,
  ADD COLUMN `item_count`    int(11)      DEFAULT 0    COMMENT '商品总件数（列表页直接展示，免 count）' AFTER `pay_amount`,
  ADD COLUMN `expect_time`   datetime     DEFAULT NULL COMMENT '期望送达时间' AFTER `address`,
  ADD COLUMN `deliver_time`  datetime     DEFAULT NULL COMMENT '商家发货时间' AFTER `pay_time`,
  ADD COLUMN `receive_time`  datetime     DEFAULT NULL COMMENT '确认收货时间' AFTER `finish_time`;

ALTER TABLE `takeout_order`
  ADD COLUMN `cancel_reason` varchar(128) DEFAULT NULL COMMENT '取消原因' AFTER `receive_time`,
  ADD COLUMN `cancel_time`   datetime     DEFAULT NULL COMMENT '取消时间' AFTER `cancel_reason`,
  ADD COLUMN `review_status` char(1)      DEFAULT '0'  COMMENT '评价状态（0未评价 1已评价）' AFTER `cancel_time`;

ALTER TABLE `takeout_order`
  ADD KEY `idx_order_user_status` (`user_id`, `status`, `create_time`);

-- 订单明细：规格快照，保证菜品改规格不影响历史订单
ALTER TABLE `takeout_order_item`
  ADD COLUMN `spec_text` varchar(128) DEFAULT NULL COMMENT '规格快照，如「大份/微辣」' AFTER `dish_name`;

ALTER TABLE `takeout_order_item`
  ADD COLUMN `option_ids` varchar(128) DEFAULT NULL COMMENT '选项ID逗号串，用于「再来一单」还原规格' AFTER `spec_text`;
