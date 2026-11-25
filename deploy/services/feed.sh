# feed 的交接（部署第 7 步，文档 8.2）：新实例先接手（连币安各流和 Polymarket、开始写出）→ 每 200 毫秒看它 /status 的 ready，
# 最多 30 秒 → 旧实例让位（断掉全部连接）→ 网关指向新实例。两个实例同时写出约 1–3 秒。
# 没等到 ready：删新容器、Redis 记回旧槽位，旧实例一直没停，线上没影响

# 旧实例让位时断掉连接就完事，没有手上的活
DRAIN_TIMEOUT=30

# service_handover <旧槽位> <新槽位>
service_handover() {
  local old=$1 new=$2 protocol deadline
  handoff_mark
  # feed 是新实例先接手，交接单没有内容，自己拼一张
  protocol=$(json_field protocol "$(handoff_status feed "$new" || true)")
  HANDOFF_NOTE="{\"protocol\":${protocol:-0},\"at\":$(date +%s%3N),\"carried\":{}}"
  log "wiib-feed-$new 接手"
  handoff_post feed "$new" takeover 10 <<<"$HANDOFF_NOTE" >/dev/null || feed_back "$old" "$new" "wiib-feed-$new 接手失败"
  handoff_step 接手
  deadline=$((SECONDS + 30))
  until [[ $(json_field ready "$(handoff_status feed "$new" || true)") == true ]]; do
    ((SECONDS < deadline)) || feed_back "$old" "$new" "wiib-feed-$new 30 秒内没连好出数"
    sleep 0.2
  done
  handoff_step 连好出数
  PENDING_SLOT=
  log "wiib-feed-$old 让位"
  handoff_post feed "$old" yield 10 </dev/null >/dev/null || log "wiib-feed-$old 让位没调通，后台清旧实例时直接停"
  handoff_step 让位
  gateway_point feed "$new"
  handoff_step 网关
}

# 接手没成：删新容器，Redis 记回旧槽位，失败退出
feed_back() {
  log "删掉 wiib-feed-$2"
  slot_stop feed "$2"
  PENDING_SLOT=
  active_slot_set feed "$1"
  die "$3，已删新容器，旧实例一直在任"
}
