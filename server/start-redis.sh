#!/usr/bin/env bash
# 启动 Redis（Windows 便携版，免安装）
# 数据不落盘（未配 save 规则），关掉即清空——课设够用，别当生产缓存。
set -u
cd "$(dirname "$0")/redis"
exec ./redis-server.exe redis.windows.conf
