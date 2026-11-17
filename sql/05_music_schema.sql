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

-- 演示数据：与 sql/README.md 里说明的 8 个示例文件对应
INSERT INTO biz_music_track (title, artist, album, duration_ms, file_path, sort_no) VALUES
('夜航西飞', '林深',     '夜航',   222000, '/profile/music/01.mp3', 1),
('玻璃海',   '陈屿',     '潮汐',   245000, '/profile/music/02.mp3', 2),
('逆光行走', '苏木',     '回声',   198000, '/profile/music/03.mp3', 3),
('城市候鸟', '白鹭乐队', '候鸟',   271000, '/profile/music/04.mp3', 4),
('缓慢坠落', '沈青',     '失重',   236000, '/profile/music/05.mp3', 5),
('极光以北', 'Nova',     'Aurora', 302000, '/profile/music/06.mp3', 6),
('雨落三号线', '顾南',   '地铁',   207000, '/profile/music/07.mp3', 7),
('无边',     '海盐汽水', '夏末',   254000, '/profile/music/08.mp3', 8);
