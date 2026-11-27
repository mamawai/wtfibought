# Redis：线上是哨兵那套，主库会变，每次先问哨兵主库在哪，再在哨兵容器里用 redis-cli 连主库。
# deploy.conf 设了 REDIS_CONTAINER（演练栈那种单机 Redis）就直接在那个容器里连。
# 库号读 .env 的 REDIS_DB，密码读 REDIS_PASSWORD，用 REDISCLI_AUTH 环境变量传进去，不上命令行

redis_cli() {
  local pass db addr host port
  local auth=()
  pass=$(env_value REDIS_PASSWORD)
  db=$(env_value REDIS_DB)
  [[ -n $pass ]] && auth=(-e REDISCLI_AUTH)
  if [[ -n ${REDIS_CONTAINER:-} ]]; then
    REDISCLI_AUTH=$pass docker exec "${auth[@]}" "$REDIS_CONTAINER" redis-cli --raw -n "${db:-0}" "$@"
    return
  fi
  addr=$(docker exec "$REDIS_SENTINEL" redis-cli -p "$REDIS_SENTINEL_PORT" --raw sentinel get-master-addr-by-name "$REDIS_MASTER_NAME") \
    || die "问哨兵要主库地址失败"
  host=$(sed -n 1p <<<"$addr")
  port=$(sed -n 2p <<<"$addr")
  [[ -n $host && -n $port ]] || die "哨兵没给出主库地址"
  REDISCLI_AUTH=$pass docker exec "${auth[@]}" "$REDIS_SENTINEL" redis-cli --raw -h "$host" -p "$port" -n "${db:-0}" "$@"
}

# 这个服务现在在任的槽位，没记过输出空
active_slot_get() {
  local v
  v=$(redis_cli GET "wiib:active:$1") || die "读 wiib:active:$1 失败"
  [[ -z $v || $v =~ ^[ab]$ ]] || die "wiib:active:$1 不是 a / b：$v"
  printf '%s' "$v"
}

active_slot_set() {
  local out
  out=$(redis_cli SET "wiib:active:$1" "$2") || die "写 wiib:active:$1 失败"
  [[ $out == OK ]] || die "写 wiib:active:$1 失败：$out"
}
