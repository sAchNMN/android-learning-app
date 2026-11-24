-- ============================================================
-- 云端曲库
-- 依赖：若依 profile 目录（application.yml 的 ruoyi.profile）
-- 音频文件放到 $ruoyi.profile/music/ 下，库里只存相对路径 /profile/music/xx.mp3
-- 客户端拿 baseUrl + 该路径直接流式播放，Range 由 Spring 静态资源处理器负责
-- ============================================================

DROP TABLE IF EXISTS biz_music_track;
CREATE TABLE biz_music_track (
  id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  title       VARCHAR(200) NOT NULL COMMENT '标题',
  artist      VARCHAR(100) DEFAULT '' COMMENT '艺人',
  album       VARCHAR(100) DEFAULT '' COMMENT '专辑',
  duration_ms BIGINT       DEFAULT 0  COMMENT '时长（毫秒）',
  file_path   VARCHAR(500) NOT NULL COMMENT '相对路径，如 /profile/music/01.mp3',
  cover       VARCHAR(500) DEFAULT NULL COMMENT '封面相对路径，可空',
  sort_no     INT          DEFAULT 0  COMMENT '排序，小的在前',
  status      CHAR(1)      DEFAULT '0' COMMENT '0 正常 1 下架',
  del_flag    CHAR(1)      DEFAULT '0' COMMENT '0 存在 2 删除',
  create_time DATETIME     DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_music_sort (status, del_flag, sort_no)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '云端曲库';

-- 演示数据：对应 $ruoyi.profile/music/ 下的 01..10.mp3
-- 本机 $ruoyi.profile 已从 D:/ruoyi/uploadPath 改为 C:/ruoyi/uploadPath（D: 是可移动盘，常被抽走）
INSERT INTO biz_music_track (title, artist, album, duration_ms, file_path, sort_no) VALUES
('晴天',             '周杰伦',        '', 269747, '/profile/music/01.mp3', 1),
('遇见',             '孙燕姿',        '', 210575, '/profile/music/02.mp3', 2),
('淘汰',             '陈奕迅',        '', 284867, '/profile/music/03.mp3', 3),
('多远都要在一起',   'G.E.M. 邓紫棋', '', 217721, '/profile/music/04.mp3', 4),
('失语者',           '蔡健雅',        '', 221907, '/profile/music/05.mp3', 5),
('小さな恋のうた',   '高橋李依',      '', 247253, '/profile/music/06.mp3', 6),
('Love In The Dark', 'Adele',         '', 285935, '/profile/music/07.mp3', 7),
('In The End',       'Linkin Park',   '', 216293, '/profile/music/08.mp3', 8),
('Die For You',      'The Weeknd',    '', 260256, '/profile/music/09.mp3', 9),
('安和桥',           '宋冬野',        '', 251000, '/profile/music/10.mp3', 10);

