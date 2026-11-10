-- ============================================================
-- 学习助手 - 业务表结构
-- 前置：已导入若依基础表（ry_*.sql）
-- 字符集：utf8mb4 / utf8mb4_general_ci
-- 字段风格与若依保持一致：create_by / create_time / update_by /
-- update_time / remark / del_flag
-- ============================================================

SET NAMES utf8mb4;

-- ------------------------------------------------------------
-- 课程
-- ------------------------------------------------------------
DROP TABLE IF EXISTS `biz_course`;
CREATE TABLE `biz_course` (
  `course_id`     bigint(20)   NOT NULL AUTO_INCREMENT COMMENT '课程ID',
  `course_name`   varchar(120) NOT NULL                COMMENT '课程名称',
  `cover_url`     varchar(255) DEFAULT NULL            COMMENT '封面地址',
  `description`   varchar(500) DEFAULT NULL            COMMENT '课程简介',
  `teacher`       varchar(64)  DEFAULT NULL            COMMENT '讲师',
  `total_chapter` int(11)      DEFAULT 0               COMMENT '章节数',
  `status`        char(1)      DEFAULT '0'             COMMENT '状态（0正常 1停用）',
  `order_num`     int(4)       DEFAULT 0               COMMENT '显示顺序',
  `del_flag`      char(1)      DEFAULT '0'             COMMENT '删除标志（0存在 2删除）',
  `create_by`     varchar(64)  DEFAULT ''              COMMENT '创建者',
  `create_time`   datetime     DEFAULT NULL            COMMENT '创建时间',
  `update_by`     varchar(64)  DEFAULT ''              COMMENT '更新者',
  `update_time`   datetime     DEFAULT NULL            COMMENT '更新时间',
  `remark`        varchar(500) DEFAULT NULL            COMMENT '备注',
  PRIMARY KEY (`course_id`),
  KEY `idx_course_status` (`status`, `del_flag`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='课程表';

-- ------------------------------------------------------------
-- 章节
-- ------------------------------------------------------------
DROP TABLE IF EXISTS `biz_chapter`;
CREATE TABLE `biz_chapter` (
  `chapter_id`   bigint(20)    NOT NULL AUTO_INCREMENT COMMENT '章节ID',
  `course_id`    bigint(20)    NOT NULL                COMMENT '所属课程ID',
  `chapter_name` varchar(120)  NOT NULL                COMMENT '章节名称',
  `content`      text                                  COMMENT '正文（Markdown）',
  `duration`     int(11)       DEFAULT 0               COMMENT '预计学时（分钟）',
  `order_num`    int(4)        DEFAULT 0               COMMENT '章节序号',
  `status`       char(1)       DEFAULT '0'             COMMENT '状态（0正常 1停用）',
  `del_flag`     char(1)       DEFAULT '0'             COMMENT '删除标志（0存在 2删除）',
  `create_by`    varchar(64)   DEFAULT ''              COMMENT '创建者',
  `create_time`  datetime      DEFAULT NULL            COMMENT '创建时间',
  `update_by`    varchar(64)   DEFAULT ''              COMMENT '更新者',
  `update_time`  datetime      DEFAULT NULL            COMMENT '更新时间',
  `remark`       varchar(500)  DEFAULT NULL            COMMENT '备注',
  PRIMARY KEY (`chapter_id`),
  KEY `idx_chapter_course` (`course_id`, `order_num`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='章节表';

-- ------------------------------------------------------------
-- 题目
-- ------------------------------------------------------------
DROP TABLE IF EXISTS `biz_question`;
CREATE TABLE `biz_question` (
  `question_id`  bigint(20)    NOT NULL AUTO_INCREMENT COMMENT '题目ID',
  `chapter_id`   bigint(20)    NOT NULL                COMMENT '所属章节ID',
  `question_type` char(1)      DEFAULT '1'             COMMENT '题型（1单选 2多选 3判断 4填空）',
  `content`      varchar(1000) NOT NULL                COMMENT '题干',
  `options`      varchar(2000) DEFAULT NULL            COMMENT '选项JSON，如 [{"k":"A","v":"..."}]',
  `answer`       varchar(255)  NOT NULL                COMMENT '正确答案',
  `analysis`     varchar(1000) DEFAULT NULL            COMMENT '解析',
  `score`        int(11)       DEFAULT 5               COMMENT '分值',
  `del_flag`     char(1)       DEFAULT '0'             COMMENT '删除标志（0存在 2删除）',
  `create_by`    varchar(64)   DEFAULT ''              COMMENT '创建者',
  `create_time`  datetime      DEFAULT NULL            COMMENT '创建时间',
  `update_by`    varchar(64)   DEFAULT ''              COMMENT '更新者',
  `update_time`  datetime      DEFAULT NULL            COMMENT '更新时间',
  `remark`       varchar(500)  DEFAULT NULL            COMMENT '备注',
  PRIMARY KEY (`question_id`),
  KEY `idx_question_chapter` (`chapter_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='题目表';

-- ------------------------------------------------------------
-- 学习记录：一个用户对一个章节一条，重复学习更新时长
-- ------------------------------------------------------------
DROP TABLE IF EXISTS `biz_study_record`;
CREATE TABLE `biz_study_record` (
  `record_id`    bigint(20)   NOT NULL AUTO_INCREMENT COMMENT '记录ID',
  `user_id`      bigint(20)   NOT NULL                COMMENT '用户ID',
  `course_id`    bigint(20)   NOT NULL                COMMENT '课程ID',
  `chapter_id`   bigint(20)   NOT NULL                COMMENT '章节ID',
  `progress`     int(11)      DEFAULT 0               COMMENT '进度百分比 0-100',
  `study_second` int(11)      DEFAULT 0               COMMENT '累计学习秒数',
  `finished`     char(1)      DEFAULT '0'             COMMENT '是否完成（0否 1是）',
  `last_time`    datetime     DEFAULT NULL            COMMENT '最近学习时间',
  `create_time`  datetime     DEFAULT NULL            COMMENT '创建时间',
  `update_time`  datetime     DEFAULT NULL            COMMENT '更新时间',
  PRIMARY KEY (`record_id`),
  UNIQUE KEY `uk_user_chapter` (`user_id`, `chapter_id`),
  KEY `idx_record_user` (`user_id`, `last_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='学习记录表';

-- ------------------------------------------------------------
-- App 会员账号（方案 B 才需要）
-- 若采用「直接复用 sys_user」的方案 A，本表可以不建。
-- 密码使用 BCrypt，与若依 SecurityUtils.encryptPassword 一致。
-- ------------------------------------------------------------
DROP TABLE IF EXISTS `biz_app_user`;
CREATE TABLE `biz_app_user` (
  `app_user_id`  bigint(20)   NOT NULL AUTO_INCREMENT COMMENT '会员ID',
  `username`     varchar(30)  NOT NULL                COMMENT '登录名',
  `nickname`     varchar(30)  DEFAULT ''              COMMENT '昵称',
  `password`     varchar(100) DEFAULT ''              COMMENT 'BCrypt 密码',
  `mobile`       varchar(11)  DEFAULT ''              COMMENT '手机号',
  `avatar`       varchar(255) DEFAULT ''              COMMENT '头像地址',
  `school`       varchar(64)  DEFAULT ''              COMMENT '学校',
  `status`       char(1)      DEFAULT '0'             COMMENT '状态（0正常 1停用）',
  `del_flag`     char(1)      DEFAULT '0'             COMMENT '删除标志（0存在 2删除）',
  `login_ip`     varchar(128) DEFAULT ''              COMMENT '最后登录IP',
  `login_date`   datetime     DEFAULT NULL            COMMENT '最后登录时间',
  `create_time`  datetime     DEFAULT NULL            COMMENT '创建时间',
  `update_time`  datetime     DEFAULT NULL            COMMENT '更新时间',
  PRIMARY KEY (`app_user_id`),
  UNIQUE KEY `uk_app_user_name` (`username`),
  KEY `idx_app_user_mobile` (`mobile`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='App 会员表';
