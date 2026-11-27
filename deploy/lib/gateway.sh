# 网关：deploy/gateway/active/<服务>.conf 一行，写着这个服务的流量转给哪个槽位。
# 改完先 nginx -t，过了再 reload；没过就把原来那行放回去

GATEWAY_DIR=$ROOT/deploy/gateway
GATEWAY=wiib-gateway

# 网关现在指的槽位
gateway_slot() {
  sed -n "s/.*wiib-$1-\([ab]\):.*/\1/p" "$GATEWAY_DIR/active/$1.conf" 2>/dev/null || true
}

gateway_reload() {
  docker exec "$GATEWAY" nginx -t -q -c /etc/nginx/wiib/nginx.conf && docker exec "$GATEWAY" nginx -s reload -c /etc/nginx/wiib/nginx.conf
}

# 把这个服务的流量指到某个槽位
gateway_point() {
  local svc=$1 slot=$2 file=$GATEWAY_DIR/active/$1.conf old
  old=$(cat "$file" 2>/dev/null || true)
  printf 'set $%s_upstream wiib-%s-%s:%s;\n' "$svc" "$svc" "$slot" "$(service_port "$svc")" >"$file.tmp"
  mv "$file.tmp" "$file"
  if ! gateway_reload; then
    printf '%s\n' "$old" >"$file"
    die "网关改到 $svc-$slot 没通过 nginx -t，已放回原来那行"
  fi
  log "网关：$svc → 槽位 $slot"
}

# 经网关问这个服务的 /internal/handoff/status，直到回包里的槽位是 $2（reload 生效了），最多等 $3 秒
gateway_wait_slot() {
  local svc=$1 slot=$2 deadline=$((SECONDS + $3)) port status
  port=$(service_port "$svc")
  until status=$(T=$(env_value INTERNAL_API_TOKEN) docker exec -e T "$GATEWAY" \
    sh -c "wget -qO- -T 2 --header \"X-Internal-Token: \$T\" http://127.0.0.1:$port/internal/handoff/status" 2>/dev/null) \
    && [[ $(json_field slot "$status") == "$slot" ]]; do
    ((SECONDS < deadline)) || return 1
    sleep 0.2
  done
}

# 网关的配置用这个版本的（active/ 下没有的才补默认的）；网关没在跑就起起来，在跑而配置变了就 reload，
# 新配置没通过 nginx -t 就放回原来的
gateway_install() {
  local rel=$1 changed=0
  mkdir -p "$GATEWAY_DIR/active"
  if ! cmp -s "$rel/deploy/gateway/nginx.conf" "$GATEWAY_DIR/nginx.conf"; then
    [[ -f $GATEWAY_DIR/nginx.conf ]] && cp "$GATEWAY_DIR/nginx.conf" "$GATEWAY_DIR/nginx.conf.bak"
    cp "$rel/deploy/gateway/nginx.conf" "$GATEWAY_DIR/nginx.conf"
    changed=1
  fi
  for f in "$rel"/deploy/gateway/active/*.conf; do
    [[ -f $GATEWAY_DIR/active/$(basename "$f") ]] || cp "$f" "$GATEWAY_DIR/active/"
  done
  if [[ $(docker inspect -f '{{.State.Running}}' "$GATEWAY" 2>/dev/null) != true ]]; then
    log "起网关"
    (cd "$ROOT" && docker compose up -d --no-deps "$GATEWAY")
    sleep 2
    [[ $(docker inspect -f '{{.State.Running}}' "$GATEWAY" 2>/dev/null) == true ]] \
      && docker exec "$GATEWAY" nginx -t -q -c /etc/nginx/wiib/nginx.conf \
      || die "网关没起来，看 docker logs $GATEWAY"
  elif ((changed)); then
    if ! gateway_reload; then
      mv "$GATEWAY_DIR/nginx.conf.bak" "$GATEWAY_DIR/nginx.conf"
      die "这个版本的网关配置没通过 nginx -t，已放回原来的"
    fi
    log "网关配置换成这个版本的"
  fi
}
