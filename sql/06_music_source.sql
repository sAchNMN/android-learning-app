-- ============================================================
-- 在线音源配置（Mineradio 式多音源接入）
-- 依赖：无，可单独导入
-- 说明：
--   cookie 存的是「用户自己在平台官网登录后的 Cookie」，
--   用于播放会员曲目；不填也能搜索、播放免费曲目。
--   接口永不回传 cookie，只回传 hasCookie。
-- ============================================================

CREATE TABLE IF NOT EXISTS biz_music_source (
  source_key  VARCHAR(32)  NOT NULL COMMENT '平台标识：netease / kugou',
  label       VARCHAR(64)  NOT NULL COMMENT '展示名',
  enabled     TINYINT      NOT NULL DEFAULT 1 COMMENT '1 启用 0 停用',
  priority    INT          NOT NULL DEFAULT 10 COMMENT '升序，小的优先',
  cookie      TEXT         NULL COMMENT '平台登录态，可空',
  update_time DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (source_key)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '在线音源配置';

-- 默认两个平台都开；重复执行只更新展示名，不覆盖用户已改的开关/Cookie
INSERT INTO biz_music_source (source_key, label, enabled, priority) VALUES
('netease', '网易云音乐', 1, 10),
('kugou',   '酷狗音乐',   1, 20)
ON DUPLICATE KEY UPDATE label = VALUES(label);