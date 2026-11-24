#!/usr/bin/env bash
# 启动若依服务端
#
# 必须显式传 --server.port=8080：
# 本机环境变量里存在把端口顶到 8066 的因素（8066 被 WorkBuddy 服务占用），
# 只靠 application.yml 的 server.port 会启动失败。
# 命令行参数优先级最高，能稳定压过它。
set -u
cd "$(dirname "$0")/RuoYi-Vue"
exec java -jar ruoyi-admin/target/ruoyi-admin.jar --server.port=8080 "$@"
