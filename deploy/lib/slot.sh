# 槽位容器：wiib-<服务>-<槽位>，槽位是 a / b。起停都在项目目录里用 compose，镜像标签走 <服务>_<槽位>_TAG

other_slot() {
  [[ $1 == a ]] && echo b || echo a
}

# 停掉并删掉一个槽位容器（没有就算了）；docker stop 按容器自己的 stop_grace_period 等它关停
slot_stop() {
  local c=wiib-$1-$2
  docker inspect "$c" >/dev/null 2>&1 || return 0
  log "停 $c"
  docker stop "$c" >/dev/null
  docker rm "$c" >/dev/null
}

# 用某个标签的镜像起一个槽位容器
slot_start() {
  local svc=$1 slot=$2 tag=$3
  log "起 wiib-$svc-$slot（镜像 wiib-$svc:$tag）"
  (cd "$ROOT" && env "${svc^^}_${slot^^}_TAG=$tag" docker compose up -d --no-deps --no-build --force-recreate "wiib-$svc-$slot")
}

# 这个槽位的 /internal/handoff/status，token 走环境变量不上命令行
slot_status() {
  local c=wiib-$1-$2 port
  port=$(service_port "$1")
  T=$(env_value INTERNAL_API_TOKEN) docker exec -e T "$c" \
    sh -c "wget -qO- --header \"X-Internal-Token: \$T\" http://127.0.0.1:$port/internal/handoff/status" 2>/dev/null
}

# 等槽位就绪：/actuator/health 是 UP；有交接接口的服务还要 /status 的 role 是 ACTIVE。最多等 $3 秒
slot_wait_active() {
  local svc=$1 slot=$2 timeout=$3 c=wiib-$1-$2 port deadline
  port=$(service_port "$svc")
  deadline=$((SECONDS + timeout))
  log "等 $c 就绪（最多 ${timeout}s）"
  until docker exec "$c" wget -qO- "http://127.0.0.1:$port/actuator/health" 2>/dev/null | grep -q '"status":"UP"' \
    && { ! has_handoff "$svc" || slot_status "$svc" "$slot" | grep -q '"role":"ACTIVE"'; }; do
    ((SECONDS < deadline)) || return 1
    sleep 3
  done
}

# 哪些服务有 /internal/handoff 接口
has_handoff() {
  [[ $1 == sim || $1 == agent ]]
}
