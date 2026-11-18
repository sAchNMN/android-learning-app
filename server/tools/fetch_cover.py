#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""云端曲库封面抓取工具。

按「标题 + 艺人」到 QQ 音乐搜同名词条，取 albummid 拼出官方专辑封面，
下载到 $ruoyi.profile/music/cover/{id}.jpg，并打印对应的 UPDATE 语句。

为什么用 QQ 音乐而不是项目里已接入的网易云：
- 网易云 `/api/search/get/web` 连续请求十几次就返回 {"code":405,"操作频繁"}，
  实测锁十几分钟还不恢复，不适合批量补数据
- QQ 音乐 `c.y.qq.com/soso/fcgi-bin/client_search_cp` 是公开老接口，返回 albummid，
  封面拼 `https://y.gtimg.cn/music/photo_new/T002R500x500M000{albummid}.jpg`
- 华语/日文/欧美的专辑覆盖率比网易云高（周杰伦、宋冬野在网易云无版权）

用法（需要后端在跑，用来读曲目时长做匹配校验；powershell 里 curl 可能被别名劫持，
所以脚本内部直接调 curl.exe）：

    python fetch_cover.py             # 只预览匹配结果，不下载
    python fetch_cover.py --apply     # 下载 + 生成 SQL 片段

匹配打分：标题完全相等 100、艺人完全相等 100、时长差 <2s 加 40。
低于 100 分的一律跳过 —— 宁可没有封面走渐变兜底，也不要在答辩现场
把《晴天》配上别人的专辑图。
"""
import json
import os
import re
import subprocess
import sys
import time
import urllib.parse
import urllib.request

CURL = r"C:/Windows/System32/curl.exe"
SEARCH = "https://c.y.qq.com/soso/fcgi-bin/client_search_cp"
PROFILE = os.environ.get("RUOYI_PROFILE", r"C:/ruoyi/uploadPath")
COVER_DIR = os.path.join(PROFILE, "music", "cover")
API = "http://127.0.0.1:8080/app/music/tracks"
UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")


def norm(s):
    """归一化：去空格、小写、去掉 (live) [Remix] （原唱 xxx） 这类尾巴。"""
    if not s:
        return ""
    s = s.lower().replace(" ", "").replace("\u3000", "")
    s = re.sub(r"[\(\（\[【].*?[\)\）\]】]", "", s)
    return s.strip()


def http_get(url, referer="https://y.qq.com/", timeout=20):
    """用 curl.exe 而不是 urllib：同一出口 IP 下 urllib 的默认头容易被判爬虫。"""
    proc = subprocess.run(
        [CURL, "-s", "--noproxy", "*", "-m", str(timeout),
         "-H", "Referer: " + referer, "-H", "User-Agent: " + UA, url],
        capture_output=True,
    )
    return proc.stdout


def qq_search(keyword, limit=10):
    qs = urllib.parse.urlencode({"w": keyword, "n": limit, "p": 1, "format": "json", "t": 0})
    songs = []
    for attempt in range(3):
        raw = http_get(SEARCH + "?" + qs)
        try:
            body = json.loads(raw.decode("utf-8", "ignore"))
        except Exception:
            body = {}
        songs = ((body.get("data") or {}).get("song") or {}).get("list") or []
        if songs:
            break
        time.sleep(2.0 * (attempt + 1))
    out = []
    for s in songs:
        mid = s.get("albummid") or ""
        out.append({
            "title": s.get("songname") or "",
            "artist": " / ".join(x.get("name") or "" for x in (s.get("singer") or [])),
            "album": s.get("albumname") or "",
            "durationMs": int(s.get("interval") or 0) * 1000,
            "cover": f"https://y.gtimg.cn/music/photo_new/T002R500x500M000{mid}.jpg" if mid else "",
        })
    return out


def score(cand, title, artist, duration_ms):
    if not cand["cover"]:
        return -999
    t, a = norm(cand["title"]), norm(cand["artist"])
    nt, na = norm(title), norm(artist)
    s = 0
    if t == nt:
        s += 100
    elif nt and nt in t:
        s += 60
    elif t and t in nt:
        s += 35
    else:
        return -999
    if a == na:
        s += 100
    elif na and na in a:
        s += 55
    elif a and a in na:
        s += 40
    else:
        s -= 10
    gap = abs(cand["durationMs"] - duration_ms) / 1000.0
    if gap < 2:
        s += 40
    elif gap < 6:
        s += 15
    elif gap > 40:
        s -= 40
    return s


def load_tracks():
    req = urllib.request.Request(API)
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    with opener.open(req, timeout=10) as r:
        return json.loads(r.read().decode("utf-8"))["data"]


def main():
    apply = "--apply" in sys.argv
    tracks = load_tracks()
    if not tracks:
        print("接口没有返回曲目，先确认后端在 8080 上跑着")
        return

    print(f"{'id':>3} {'分':>4} {'本地曲目':<18} {'命中专辑':<38} {'时长差':>6}")
    print("-" * 84)
    results = []
    for t in tracks:
        tid, title, artist = int(t["id"]), t["title"], t.get("artist") or ""
        dur = int(t.get("durationMs") or 0)
        cands = qq_search(f"{title} {artist}")
        if not cands:
            print(f"{tid:>3} {'X':>4} {title:<18} 未找到")
            results.append((tid, title, None, -999))
            time.sleep(1.2)
            continue
        sc, c = max(((score(x, title, artist, dur), x) for x in cands), key=lambda x: x[0])
        gap = (c["durationMs"] - dur) / 1000.0
        label = f"{c['title'][:14]} / {c['artist'][:10]} / {c['album'][:9]}"
        print(f"{tid:>3} {sc:>4} {title:<18} {label:<38} {gap:>5.0f}s")
        results.append((tid, title, c, sc))
        time.sleep(1.2)

    if not apply:
        print("\n[preview] 加 --apply 才会下载并输出 SQL")
        return

    os.makedirs(COVER_DIR, exist_ok=True)
    updates = []
    for tid, title, cand, sc in results:
        if not cand or sc < 100:
            print(f"  - 跳过 {tid} {title}（分 {sc}，不可靠）")
            continue
        dst = os.path.join(COVER_DIR, f"{tid:02d}.jpg")
        try:
            data = http_get(cand["cover"])
            if not data.startswith(b"\xff\xd8") or len(data) < 3000:
                raise ValueError(f"不是有效 jpeg（{len(data)} 字节）")
            with open(dst, "wb") as f:
                f.write(data)
            updates.append((tid, f"/profile/music/cover/{tid:02d}.jpg"))
            print(f"  ✓ {tid:02d}.jpg {len(data) // 1024}KB ← {cand['title']} / {cand['artist']}")
        except Exception as e:  # noqa: BLE001
            print(f"  ✗ {tid} 下载失败：{e}")
        time.sleep(0.5)

    print("\n-- 把下面几行贴进 MySQL，或合并进 sql/07_music_cover.sql")
    for tid, rel in updates:
        print(f"UPDATE biz_music_track SET cover = '{rel}' WHERE id = {tid};")
    print(f"\n图片目录：{COVER_DIR}")


if __name__ == "__main__":
    main()
