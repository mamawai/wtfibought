# sim 的交接（部署第 7 步，文档 7.2）：网关先指向新实例（它还在待命，请求闸把请求压住）→ 旧实例让位（关口、等清零最多 3 秒）
# → 新实例接手、放行压住的请求。失败都把网关指回旧实例；接手失败时先删新容器、再让旧实例恢复，不会两个实例同时干活

# 旧实例让位时已经等过清零，收尾只剩几秒
DRAIN_TIMEOUT=60

# service_handover <旧槽位> <新槽位>
service_handover() {
  local old=$1 new=$2 note
  handoff_mark
  gateway_point sim "$new"
  if ! gateway_wait_slot sim "$new" 30; then
    gateway_point sim "$old"
    die "网关改到 wiib-sim-$new 后 30 秒内经网关看到的还不是它"
  fi
  handoff_step 网关
  log "wiib-sim-$old 让位"
  if ! note=$(handoff_post sim "$old" yield 4 </dev/null); then
    handoff_resume sim "$old"
    gateway_point sim "$old"
    die "wiib-sim-$old 让位失败（3 秒内手上的活没清零，或者超时），已恢复"
  fi
  handoff_step 让位
  HANDOFF_NOTE=$note
  log "wiib-sim-$new 接手"
  if ! handoff_post sim "$new" takeover 4 <<<"$note" >/dev/null; then
    log "删掉 wiib-sim-$new"
    slot_stop sim "$new"
    PENDING_SLOT=
    handoff_resume sim "$old"
    gateway_point sim "$old"
    die "wiib-sim-$new 接手失败，已删新容器、旧实例恢复在任"
  fi
  handoff_step 接手
  PENDING_SLOT=
}
