#!/usr/bin/env bash
# 停掉后端三件套：RuoYi(8080) → Redis(6379) → MySQL(3306)
#
# 顺序不能反：后端还在跑就先停库，手上的请求会一路报连接异常。
#
# MySQL 走 mysqladmin shutdown，不要 taskkill //F —— 强杀会留 InnoDB 断言
# （日志里出现 trx_sys_close assertion failure），下次启动要走 crash recovery。
# 本脚本宁可报错退出，也不强杀 MySQL。
#
# 用法（仓库任意位置）：
#   ./server/stop-all.sh
#   MYSQL_PWD=xxxx ./server/stop-all.sh      # root 密码不是默认值时
#   MYSQL_HOME=D:/mysql ./server/stop-all.sh  # MySQL 装在别处时
#
# 幂等：没在跑的服务直接跳过，不报错。
set -u

ROOT="$(cd "$(dirname "$0")" && pwd)"
REDIS_CLI="$ROOT/redis/redis-cli.exe"
MYSQL_HOME="${MYSQL_HOME:-C:/tools/mysql}"
MYSQLADMIN="$MYSQL_HOME/bin/mysqladmin.exe"
MYSQL_PWD="${MYSQL_PWD:-password}"
BACKEND_PORT=8080
REDIS_PORT=6379
MYSQL_PORT=3306

log() { printf '[stop-all] %s\n' "$*"; }
warn() { printf '[stop-all] 警告：%s\n' "$*" >&2; }
die() { printf '[stop-all] 错误：%s\n' "$*" >&2; exit 1; }

listener_pid() {
  netstat -ano 2>/dev/null \
    | grep -E ":${1}[[:space:]]" \
    | grep -i LISTENING \
    | awk '{print $NF}' \
    | head -1
}

# 等端口释放，最多 $2 秒。返回 0 表示已释放。
wait_gone() {
  local port=$1 timeout=$2 i=0
  while [ "$i" -lt "$timeout" ]; do
    [ -z "$(listener_pid "$port" || true)" ] && return 0
    sleep 1
    i=$((i + 1))
  done
  return 1
}

# 只在优雅停无效时兜底。用于后端和 Redis，不用于 MySQL。
force_kill() {
  local port=$1 pid
  pid="$(listener_pid "$port" || true)"
  [ -z "$pid" ] && return 0
  warn "端口 $port（PID=$pid）仍在监听，强杀。"
  taskkill //PID "$pid" //F >/dev/null 2>&1 || true
  wait_gone "$port" 10 || warn "端口 $port 10 秒内未释放，可能有残留进程。"
}

# ---------- 后端 ----------
# 强杀在这里是合理的：nohup 起的 java 没有控制台窗口，taskkill 不带 /F
# 发的是 WM_CLOSE，对无窗口进程多半无效。若依也没有需要落盘的运行时状态。
PID="$(listener_pid "$BACKEND_PORT" || true)"
if [ -z "$PID" ]; then
  log "后端（$BACKEND_PORT）没在跑，跳过。"
else
  log "停止后端（$BACKEND_PORT，PID=$PID）…"
  taskkill //PID "$PID" //F >/dev/null 2>&1 || true
  if wait_gone "$BACKEND_PORT" 15; then
    log "后端已停。"
  else
    warn "后端端口 15 秒内未释放，手动确认：netstat -ano | grep :$BACKEND_PORT"
  fi
fi

# ---------- Redis ----------
PID="$(listener_pid "$REDIS_PORT" || true)"
if [ -z "$PID" ]; then
  log "Redis（$REDIS_PORT）没在跑，跳过。"
else
  log "停止 Redis（$REDIS_PORT，PID=$PID）…"
  # shutdown nosave：本机 Redis 只放 token，不值得落 RDB
  [ -x "$REDIS_CLI" ] && "$REDIS_CLI" -p "$REDIS_PORT" shutdown nosave >/dev/null 2>&1 || true
  if wait_gone "$REDIS_PORT" 5; then
    log "Redis 已停。"
  else
    # Git Bash 下 redis-cli 偶发 socket 错误，命令失败不代表没下发
    force_kill "$REDIS_PORT"
    [ -z "$(listener_pid "$REDIS_PORT" || true)" ] && log "Redis 已停。"
  fi
fi

# ---------- MySQL ----------
PID="$(listener_pid "$MYSQL_PORT" || true)"
if [ -z "$PID" ]; then
  log "MySQL（$MYSQL_PORT）没在跑，跳过。"
else
  log "停止 MySQL（$MYSQL_PORT，PID=$PID）…"
  [ -x "$MYSQLADMIN" ] || die "找不到 $MYSQLADMIN，无法优雅停库。用 MYSQL_HOME 指定 MySQL 目录。"
  # 密码走环境变量：命令行明文会进 process list，也会被记进 shell 历史
  if MYSQL_PWD="$MYSQL_PWD" "$MYSQLADMIN" -uroot --connect-timeout=5 shutdown 2>/dev/null; then
    log "shutdown 已下发，等 InnoDB 收尾…"
  else
    die "mysqladmin shutdown 失败（密码不对，或 mysqld 无响应）。密码不是 password 时用 MYSQL_PWD=xxxx 重跑。不强杀 —— 强杀会留 InnoDB 断言。"
  fi
  if wait_gone "$MYSQL_PORT" 40; then
    log "MySQL 已停。"
  else
    die "MySQL 40 秒内未退出，可能有长事务或正在刷盘。手动查 :$MYSQL_PORT 的 PID 再决定，别直接 taskkill。"
  fi
fi

log "处理完毕。"
