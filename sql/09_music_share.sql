-- ============================================================
-- 歌单分享码
-- 依赖：无，可单独导入；表没建时 App 的分享入口会明确报「后端未记录」，不影响其它功能
-- 说明：
--   code 是 6 位短码，字符集去掉了 0/O/1/I/L，避免口头/手抄时认错。
--   payload_json 原样存客户端提交的曲目数组（MusicTrack 的 JSON），
--   后端**不解析**每个字段 —— 跨音源曲目带 hash / albumId / albumAudioId 这些
--   平台私有参数，后端一旦理解这套结构，客户端每加一个字段就要改两边。
--   后端只负责「短码唯一」「条数上限」「过期清理」这些客户端做不到的事。
-- ============================================================

CREATE TABLE IF NOT EXISTS biz_music_share (
  id           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  code         VARCHAR(8)   NOT NULL COMMENT '分享码（6 位，已排除易混字符）',
  owner_device VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '生成者 deviceId（App 无登录体系）',
  title        VARCHAR(200) NOT NULL DEFAULT '' COMMENT '歌单名',
  track_count  INT          NOT NULL DEFAULT 0 COMMENT '实际存下的曲目数',
  payload_json MEDIUMTEXT   NOT NULL COMMENT '曲目数组 JSON，原样透传',
  view_count   INT          NOT NULL DEFAULT 0 COMMENT '被导入次数',
  create_time  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  expire_time  DATETIME     DEFAULT NULL COMMENT '过期时间，NULL 表示不过期',
  PRIMARY KEY (id),
  UNIQUE KEY uk_code (code),
  KEY idx_owner (owner_device, create_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '歌单分享码';
