# 槽位容器：wiib-<服务>-<槽位>，槽位是 a / b。起停都在项目目录里用 compose，镜像标签走 <服务>_<槽位>_TAG

other_slot() {
  [[ $1 == a ]] && echo b || echo a
}

# 槽位容器在不在（不管是不是在跑）
slot_exists() {
  docker inspect "wiib-$1-$2" >/dev/null 2>&1
}

slot_running() {
  [[ $(docker inspect -f '{{.State.Running}}' "wiib-$1-$2" 2>/dev/null) == true ]]
}

# 停掉并删掉一个容器（名字或 ID，没有就算了）；docker stop 按容器自己的 stop_grace_period 等它关停。
# 后台清旧实例和下一次部署可能同时停同一个，所以停完只看它还在不在
container_stop() {
  docker container inspect "$1" >/dev/null 2>&1 || return 0
  docker stop "$1" >/dev/null 2>&1 || true
  docker rm "$1" >/dev/null 2>&1 || true
  ! docker container inspect "$1" >/dev/null 2>&1 || die "容器 $1 停不掉"
}

# 停掉并删掉一个槽位容器
slot_stop() {
  slot_exists "$1" "$2" || return 0
  log "停 wiib-$1-$2"
  container_stop "wiib-$1-$2"
}

# 用某个标签的镜像起一个槽位容器
slot_start() {
  local svc=$1 slot=$2 tag=$3
  log "起 wiib-$svc-$slot（镜像 wiib-$svc:$tag）"
  (cd "$ROOT" && env "${svc^^}_${slot^^}_TAG=$tag" docker compose up -d --no-deps --no-build --force-recreate "wiib-$svc-$slot")
}

slot_healthy() {
  [[ $(docker exec "wiib-$1-$2" wget -qO- "http://127.0.0.1:$(service_port "$1")/actuator/health" 2>/dev/null) == *'"status":"UP"'* ]]
}

# 等槽位就绪：/actuator/health 是 UP，有交接接口的服务还要 /status 的 role 是 $4。最多等 $3 秒
slot_wait_role() {
  local svc=$1 slot=$2 timeout=$3 role=$4 deadline
  deadline=$((SECONDS + timeout))
  log "等 wiib-$svc-$slot 就绪（最多 ${timeout}s）"
  until slot_healthy "$svc" "$slot" \
    && { ! has_handoff "$svc" || [[ $(json_field role "$(handoff_status "$svc" "$slot")") == "$role" ]]; }; do
    ((SECONDS < deadline)) || return 1
    sleep 3
  done
}

# 等一个容器里收尾中的实例清空（/status 的 drained）：wait_drained <服务> <容器名或 ID> <最多等几秒>。
# 容器没在跑、或者回的身份不是收尾就不等，一时调不通（卡顿）接着等
wait_drained() {
  local svc=$1 c=$2 timeout=$3 deadline status
  deadline=$((SECONDS + timeout))
  while [[ $(docker container inspect -f '{{.State.Running}}' "$c" 2>/dev/null) == true ]]; do
    if status=$(handoff_status_in "$svc" "$c"); then
      [[ $(json_field role "$status") == DRAINING ]] || return 0
      if [[ $(json_field drained "$status") == true ]]; then
        log "$svc 收尾中的实例清空了"
        return 0
      fi
    fi
    if ((SECONDS >= deadline)); then
      log "等 $svc 收尾中的实例清空到了 ${timeout}s 上限，直接停，没完的活走关停收尾"
      return 0
    fi
    sleep 5
  done
}

# 哪些服务有 /internal/handoff 接口：三个都有
has_handoff() {
  [[ $1 == feed || $1 == sim || $1 == agent ]]
}
