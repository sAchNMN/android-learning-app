#!/usr/bin/env bash
# 一键启动后端：Redis + RuoYi-Vue（8080）
#
# 用法（仓库任意位置）：
#   ./server/start-all.sh
#
# 相比原来的 start-redis.sh + start-backend.sh 两个终端：
#   1) 幂等：Redis / 后端已在跑就跳过，不会起第二份
#   2) 预检：jar、端口、MySQL、依赖命令，出错直接说人话
#   3) 显式 --server.port=8080，压过本机把端口顶到 8066 的外部覆盖
#   4) 轮询 /captchaImage 直到 200，失败时打印日志尾部
#
# 服务用 nohup 脱离终端，脚本返回后继续运行。
# 日志：server/redis-run.log、server/backend-run.log
set -u

ROOT="$(cd "$(dirname "$0")" && pwd)"
REDIS_DIR="$ROOT/redis"
REDIS_CLI="$REDIS_DIR/redis-cli.exe"
REDIS_LOG="$ROOT/redis-run.log"
JAR="$ROOT/RuoYi-Vue/ruoyi-admin/target/ruoyi-admin.jar"
BACKEND_LOG="$ROOT/backend-run.log"
PORT=8080
HEALTH="http://localhost:${PORT}/captchaImage"

log() { printf '[start-all] %s\n' "$*"; }
die() { printf '[start-all] 错误：%s\n' "$*" >&2; exit 1; }

# 本机代理会拦 localhost，curl 访问本地服务必须 --noproxy '*'，
# 否则看到 502 会误判成服务没起来。
http_code() {
  local c
  c="$(curl --noproxy '*' -s -m 2 -o /dev/null -w '%{http_code}' "$HEALTH" 2>/dev/null)"
  case "$c" in
    [0-9][0-9][0-9]) printf '%s' "$c" ;;
    *) printf '000' ;;
  esac
}

listener_pid() {
  netstat -ano 2>/dev/null \
    | grep -E ":${1}[[:space:]]" \
    | grep -i LISTENING \
    | awk '{print $NF}' \
    | head -1
}

# Android Studio 点 Run 不会触发 installDebug 依赖的 adbReverse 任务，
# App 里的 127.0.0.1:8080 就指向设备自己，UI 显示「离线」。
ensure_adb_tunnel() {
  command -v adb >/dev/null 2>&1 || return 0
  if adb devices 2>/dev/null | grep -qE '[[:space:]]device$'; then
    if adb reverse "tcp:$PORT" "tcp:$PORT" >/dev/null 2>&1; then
      log "adb 隧道已建：tcp:$PORT → tcp:$PORT（App 可访问宿主机后端）"
    else
      log "警告：adb reverse 失败，App 可能仍显示离线。"
    fi
  else
    log "未检测到 adb 设备，跳过隧道。"
  fi
}

# 不要只用 redis-cli ping 判断存活：Git Bash 下 redis-cli 偶发
# "Can't create socket: Unknown error"，Redis 明明在听也会误判成没起，
# 结果重复拉起第二份、端口冲突后直接 die。以监听端口为准。
redis_alive() {
  [ -n "$(listener_pid 6379 || true)" ]
}

# ---------- 预检 ----------
for cmd in java curl netstat; do
  command -v "$cmd" >/dev/null 2>&1 || die "找不到命令 $cmd，先配好 PATH。"
done

[ -f "$JAR" ] || die "找不到 $JAR。业务代码在 server/business/，改完要先 cp 到 RuoYi-Vue/ 再 mvn package（见 server/business/README.md）。"
[ -x "$REDIS_CLI" ] || die "找不到 $REDIS_CLI，Redis 便携版不完整。"

# 后端已经在跑：直接返回，不重复启动
CODE="$(http_code)"
if [ "$CODE" = "200" ]; then
  log "后端已在运行（$HEALTH 返回 200），无需启动。"
  ensure_adb_tunnel
  exit 0
fi

# 端口被占但不是若依：绝不擅自杀进程
PID="$(listener_pid "$PORT" || true)"
if [ -n "$PID" ]; then
  NAME="$(tasklist //FI "PID eq $PID" //FO CSV 2>/dev/null | tail -1 | cut -d, -f1 | tr -d '"')"
  case "$NAME" in
    *java*)
      log "端口 $PORT 上有 java 进程（PID=$PID），可能正在启动，继续等待。"
      ;;
    *)
      die "端口 $PORT 被 $NAME (PID=$PID) 占用，但 $HEALTH 返回 $CODE。先停掉它或改端口；我不会自动杀进程。"
      ;;
  esac
fi

# MySQL 没起时后端可能仍能启动，但登录必挂，提前说清楚
if [ -z "$(listener_pid 3306 || true)" ]; then
  log "警告：3306 没监听，MySQL 未启动。后端能起来，但登录会失败。"
fi

# ---------- Redis ----------
if redis_alive; then
  log "Redis 已在运行（6379 PONG），跳过。"
else
  log "启动 Redis（6379）…"
  # 方式一：跟原 start-redis.sh 一致，走配置文件
  (
    cd "$REDIS_DIR" || exit 1
    nohup ./redis-server.exe redis.windows.conf >> "$REDIS_LOG" 2>&1 &
  )
  for _ in $(seq 1 5); do
    redis_alive && break
    sleep 1
  done
  # 方式二：若 .conf 打不开（如事件日志权限问题），降级为纯命令行参数，
  # 等价关闭 RDB / AOF，避免 MISCONF 禁写
  if ! redis_alive; then
    log "配置文件方式未就绪，降级为命令行参数启动…"
    (
      cd "$REDIS_DIR" || exit 1
      nohup ./redis-server.exe --port 6379 --save '' --appendonly no --stop-writes-on-bgsave-error no >> "$REDIS_LOG" 2>&1 &
    )
    for _ in $(seq 1 10); do
      redis_alive && break
      sleep 1
    done
  fi
  redis_alive || die "Redis 未就绪，看 $REDIS_LOG"
  log "Redis 就绪，PID=$(listener_pid 6379 || echo '?')"
fi

# ---------- 后端 ----------
log "启动后端（:$PORT）…"
printf '\n===== %s =====\n' "$(date '+%F %T')" >> "$BACKEND_LOG"
cd "$ROOT/RuoYi-Vue" || die "进入 RuoYi-Vue 目录失败"
# 注意：Git Bash 的 $! 是 MSYS 包装进程 PID，不等于 Windows 真实 PID，
# 不能拿它去 taskkill。就绪后用 netstat 查真实 PID。
nohup java -jar ruoyi-admin/target/ruoyi-admin.jar --server.port="$PORT" >> "$BACKEND_LOG" 2>&1 &
log "后端已拉起，等待就绪…（日志：$BACKEND_LOG）"

for _ in $(seq 1 60); do
  [ "$(http_code)" = "200" ] && break
  sleep 1
done

if [ "$(http_code)" != "200" ]; then
  printf -- '--- 日志尾部（%s）---\n' "$BACKEND_LOG" >&2
  tail -30 "$BACKEND_LOG" >&2
  die "后端 60 秒内未就绪。常见原因：8080 被占、MySQL/Redis 不通、jar 不是最新编译。"
fi

log "后端就绪：$HEALTH 返回 200，PID=$(listener_pid "$PORT" || echo '?')"
ensure_adb_tunnel

log "停止服务：netstat -ano | grep -E ':(8080|6379)[[:space:]]' 查 PID，再 taskkill //PID <PID> //F（Git Bash 写法）。"
