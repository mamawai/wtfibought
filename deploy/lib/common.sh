# deploy.sh 的公共函数。用之前要先有 ROOT（服务器上的项目目录）

RELEASES=$ROOT/releases
STATE=$ROOT/deploy/state

# 写标准错误，在 $(...) 里调到也进日志
log() { printf '%s %s\n' "$(date '+%F %T')" "$*" >&2; }
die() { log "失败：$*"; exit 1; }

# .env 里某个键的值，没有这个键输出空。认 export 前缀；带引号的取引号里面，不带的去掉行尾 \r、" #" 起的注释和尾部空白
env_value() {
  local v
  v=$(sed -n "s/^\(export[[:space:]]\{1,\}\)\{0,1\}$1=//p" "$ROOT/.env" | tail -n 1)
  v=${v%$'\r'}
  if [[ $v == \"* ]]; then
    v=${v#\"}
    v=${v%%\"*}
  elif [[ $v == \'* ]]; then
    v=${v#\'}
    v=${v%%\'*}
  else
    v=${v%%[[:space:]]#*}
    v=${v%"${v##*[![:space:]]}"}
  fi
  printf '%s' "$v"
}

# 服务在容器里的端口
service_port() {
  case $1 in
    feed) echo 8081 ;;
    sim) echo 8080 ;;
    agent) echo 8082 ;;
  esac
}

# 这个服务现在部署的哈希和上一个，记在 deploy/state/<服务>，没部署过输出空
state_get() {
  sed -n "s/^$2=//p" "$STATE/$1" 2>/dev/null || true
}
