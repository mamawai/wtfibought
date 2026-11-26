# agent 的交接（部署第 7 步，文档 6.2）：旧实例让位 → 新实例接手（失败就先删新容器、再让旧实例恢复在任）
# → 网关指向新实例 → 旧实例把不持有的 trader 的实时页连接完成掉（前端 2 秒后重连，落到新实例）

# 旧实例收尾最多等多久：单笔复盘 30 分钟加余量
DRAIN_TIMEOUT=2100

# service_handover <旧槽位> <新槽位>
service_handover() {
  local old=$1 new=$2 note
  handoff_mark
  log "wiib-agent-$old 让位"
  if ! note=$(handoff_post agent "$old" yield 30 </dev/null); then
    handoff_resume agent "$old"
    die "wiib-agent-$old 让位失败"
  fi
  handoff_step 让位
  HANDOFF_NOTE=$note
  log "wiib-agent-$new 接手"
  # 超时比旧实例让位后的 15 秒保险短；调用失败再看一眼新实例，已经在任就是接手成了、只是回包没收到。
  # 没成就先删新容器、再让旧实例恢复：新实例那边晚到的接手改不到 Redis，也不会两边同时在任
  if ! handoff_post agent "$new" takeover 10 <<<"$note" >/dev/null \
    && [[ $(json_field role "$(handoff_status agent "$new" || true)") != ACTIVE ]]; then
    log "删掉 wiib-agent-$new"
    slot_stop agent "$new"
    PENDING_SLOT=
    handoff_resume agent "$old"
    die "wiib-agent-$new 接手失败，已删新容器、旧实例恢复在任"
  fi
  handoff_step 接手
  # 新实例在任了，之后再失败也不删它
  PENDING_SLOT=
  gateway_point agent "$new"
  handoff_step 网关
  handoff_post agent "$old" release-streams 10 </dev/null >/dev/null \
    || log "wiib-agent-$old 完成实时页连接没调通，那些连接等它退出时断"
  handoff_step 放实时页
}
