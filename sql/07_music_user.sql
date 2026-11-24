-- ============================================================
-- 我喜欢（跨音源）+ 推荐口味来源
-- 依赖：无，可单独导入；表没建时后端照样能跑（喜欢只在本机生效）
-- 说明：
--   App 没有登录体系，deviceId 就是用户身份（客户端首次启动生成的 UUID）。
--   source = netease / kugou / cloud / local，与客户端 MusicTrack 的 source 对齐。
--   取消喜欢不删行，只把 liked 置 0：取消动作本身也是口味信号。
-- ============================================================

CREATE TABLE IF NOT EXISTS biz_music_user_like (
  id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  device_id   VARCHAR(64)  NOT NULL COMMENT '设备号（客户端生成的 UUID）',
  source      VARCHAR(32)  NOT NULL COMMENT '音源：netease/kugou/cloud/local',
  track_id    VARCHAR(128) NOT NULL COMMENT '平台内歌曲 id',
  title       VARCHAR(200) DEFAULT '' COMMENT '歌名',
  artist      VARCHAR(200) DEFAULT '' COMMENT '歌手',
  album       VARCHAR(200) DEFAULT '' COMMENT '专辑',
  duration_ms BIGINT       DEFAULT 0  COMMENT '时长（毫秒）',
  cover       VARCHAR(500) DEFAULT NULL COMMENT '封面地址',
  hash        VARCHAR(128) DEFAULT NULL COMMENT '酷狗文件 hash，取址必需',
  album_id    VARCHAR(64)  DEFAULT NULL COMMENT '酷狗专辑 id',
  album_audio_id VARCHAR(64) DEFAULT NULL COMMENT '酷狗 mixSongId',
  liked       TINYINT      NOT NULL DEFAULT 1 COMMENT '1 喜欢 0 取消',
  update_time DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  create_time DATETIME     DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_device_track (device_id, source, track_id),
  KEY idx_device_liked (device_id, liked, update_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '我喜欢（跨音源同步）';
