-- ============================================================
-- 歌曲评论 + 评论点赞
-- 依赖：无，可单独导入；表没建时接口会明确报「评论功能未就绪」，不影响播放与收藏
-- 说明：
--   评论按 **song_key**（归一化「歌名|主歌手」）归属，不是按平台 track_id。
--   理由：用户在网易云听到《晴天》和在酷我听到《晴天》，评论应该是同一批 ——
--   评论是对「这首歌」的评价，而不是对「某个平台的某条曲目记录」的评价。
--   代价是不同歌手的同名翻唱会混在一起（比如《晴天》原版和翻唱），
--   但这种重合远少于「跨平台割裂」带来的困惑。
--
--   归一化只在后端实现一处（MusicCommentService.songKey），客户端传原始
--   title/artist。两端各写一套正则，迟早会因为一个全角括号对不上。
-- ============================================================

CREATE TABLE IF NOT EXISTS biz_music_comment (
  id           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  song_key     VARCHAR(200) NOT NULL COMMENT '归一化「歌名|主歌手」，跨平台同一首歌共用',
  track_key    VARCHAR(200) NOT NULL DEFAULT '' COMMENT '首次评论时的平台曲目键，仅用于排查',
  track_title  VARCHAR(200) NOT NULL DEFAULT '' COMMENT '歌名原文',
  track_artist VARCHAR(200) NOT NULL DEFAULT '' COMMENT '歌手原文',
  source_key   VARCHAR(32)  NOT NULL DEFAULT '' COMMENT 'netease/kugou/kuwo/qq/cloud/local',
  device_id    VARCHAR(64)  NOT NULL COMMENT '评论者设备号（App 无登录体系）',
  device_name  VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '昵称，空则前端显示「匿名听众」',
  content      VARCHAR(500) NOT NULL COMMENT '正文',
  like_count   INT          NOT NULL DEFAULT 0 COMMENT '冗余计数，避免列表页每次 count(*)',
  status       TINYINT      NOT NULL DEFAULT 1 COMMENT '1 正常 0 隐藏（保留给后台处理违规）',
  create_time  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '发表时间',
  PRIMARY KEY (id),
  KEY idx_song (song_key, status, create_time),
  KEY idx_device (device_id, create_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '歌曲评论';

-- 点赞单独建表而不是在评论表存个数组：
--   1. 唯一键 (comment_id, device_id) 天然防重复点赞，不需要读改写
--   2. 能回答「我赞过哪些评论」，以后做「我的点赞」不用改表
CREATE TABLE IF NOT EXISTS biz_music_comment_like (
  id         BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
  comment_id BIGINT      NOT NULL COMMENT '评论 id',
  device_id  VARCHAR(64) NOT NULL COMMENT '点赞者设备号',
  create_time DATETIME   NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_comment_device (comment_id, device_id),
  KEY idx_device (device_id, create_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '评论点赞';
