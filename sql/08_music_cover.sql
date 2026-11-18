-- ============================================================
-- 云端曲库封面
-- 依赖：05_music_schema.sql（biz_music_track 表）
-- 说明：
--   cover 存站内相对路径，客户端 MusicRepository.absolute() 会补成 baseUrl + 该路径。
--   图片实体放在 $ruoyi.profile/music/cover/{id}.jpg，
--   由 Spring 静态资源映射 /profile/** 直接对外提供，不需要额外接口。
--
--   这批封面是用 server/tools/fetch_cover.py 按「标题+艺人」从 QQ 音乐取的
--   官方专辑封面（500x500），逐张核对过专辑名与时长，不是随便搜的图。
--   重建库后重新执行本脚本即可恢复；也可以重新跑那个脚本抓最新封面。
--
--   不直接存外链（y.gtimg.cn / p1.music.126.net）的原因：
--   演示/答辩现场如果没网，外链封面会全部退化成兜底渐变，本地文件不会。
-- ============================================================

UPDATE biz_music_track SET cover = '/profile/music/cover/01.jpg' WHERE id = 1;   -- 晴天 / 周杰伦 / 叶惠美
UPDATE biz_music_track SET cover = '/profile/music/cover/02.jpg' WHERE id = 2;   -- 遇见 / 孙燕姿 / The Moment
UPDATE biz_music_track SET cover = '/profile/music/cover/03.jpg' WHERE id = 3;   -- 淘汰 / 陈奕迅 / 认了吧
UPDATE biz_music_track SET cover = '/profile/music/cover/04.jpg' WHERE id = 4;   -- 多远都要在一起 / G.E.M. 邓紫棋 / 新的心跳
UPDATE biz_music_track SET cover = '/profile/music/cover/05.jpg' WHERE id = 5;   -- 失语者 / 蔡健雅 / 失语者
UPDATE biz_music_track SET cover = '/profile/music/cover/06.jpg' WHERE id = 6;   -- 小さな恋のうた / 高橋李依 / からかい上手の高木さん
UPDATE biz_music_track SET cover = '/profile/music/cover/07.jpg' WHERE id = 7;   -- Love In The Dark / Adele / 25
UPDATE biz_music_track SET cover = '/profile/music/cover/08.jpg' WHERE id = 8;   -- In The End / Linkin Park / Hybrid Theory
UPDATE biz_music_track SET cover = '/profile/music/cover/09.jpg' WHERE id = 9;   -- Die For You / The Weeknd / Starboy
UPDATE biz_music_track SET cover = '/profile/music/cover/10.jpg' WHERE id = 10;  -- 安和桥 / 宋冬野 / 安和桥北
