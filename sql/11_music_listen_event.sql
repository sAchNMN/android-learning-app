-- ============================================================
-- 播放事件流水 + 榜单聚合数据源
-- 依赖：无，可单独导入；表没建时接口返回空榜并给出原因
-- 说明：
--   这是「先记流水、再算榜单」里的流水。榜单本身不落表 ——
--   日榜是一次 GROUP BY 的结果，落表就要处理补算、回滚、时区，
--   而流水永远不会写错：它只记录"某设备在某时刻对某首歌做了什么"。
--   song_key 由服务端归一化（歌名|主歌手），与评论区共用同一套规则，
--   所以《晴天》在网易云和酷狗被听到，算同一首歌的热度。
-- ============================================================

CREATE TABLE IF NOT EXISTS biz_music_listen_event (
  id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  device_id   VARCHAR(64)  NOT NULL COMMENT '设备号，防刷维度的主体',
  song_key    VARCHAR(200) NOT NULL COMMENT '归一化键：歌名|主歌手，小写去标点',
  source      VARCHAR(32)  DEFAULT '' COMMENT '音源：netease/kugou/qq/cloud',
  track_id    VARCHAR(128) DEFAULT '' COMMENT '平台内歌曲 id，榜单点播要用',
  title       VARCHAR(200) DEFAULT '' COMMENT '原始歌名（展示用）',
  artist      VARCHAR(200) DEFAULT '' COMMENT '原始歌手（展示用）',
  cover       VARCHAR(500) DEFAULT NULL COMMENT '封面地址',
  duration_ms BIGINT       DEFAULT 0  COMMENT '时长，客户端算"听完"阈值用',
  event       VARCHAR(16)  NOT NULL COMMENT 'play/finish/skip/like',
  position_ms BIGINT       DEFAULT 0  COMMENT '上报时播放到哪，排查误判用',
  weight      INT          NOT NULL DEFAULT 0 COMMENT '本条对热度的贡献，可为负',
  create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_created (create_time),
  KEY idx_song_created (song_key, create_time),
  KEY idx_device_song (device_id, song_key, create_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '播放事件流水（榜单数据源）';
