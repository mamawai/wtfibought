# 交接接口 /internal/handoff/*：在槽位容器里用 busybox wget 打它自己的 localhost，token 走环境变量不上命令行

# GET /status，输出 JSON；调不通返回非 0
handoff_status() {
  local port
  port=$(service_port "$1")
  T=$(env_value INTERNAL_API_TOKEN) docker exec -e T "wiib-$1-$2" \
    sh -c "wget -qO- -T 10 --header \"X-Internal-Token: \$T\" http://127.0.0.1:$port/internal/handoff/status" 2>/dev/null
}

# POST /<动作>：handoff_post <服务> <槽位> <动作> <超时秒>，请求体从标准输入读，输出回包；不是 2xx 返回非 0
handoff_post() {
  local port
  port=$(service_port "$1")
  T=$(env_value INTERNAL_API_TOKEN) docker exec -i -e T "wiib-$1-$2" \
    sh -c "wget -qO- -T $4 --header \"X-Internal-Token: \$T\" --header 'Content-Type: application/json' --post-file=/dev/stdin http://127.0.0.1:$port/internal/handoff/$3"
}

# JSON 里第一个叫这个名字的字段的值，字符串去掉引号；没有输出空
json_field() {
  { grep -o "\"$1\":\"\{0,1\}[^\",}]*" <<<"$2" || true; } | head -n 1 | sed "s/^\"$1\":\"\{0,1\}//"
}

# 让位后接手失败：Redis 记回旧实例，再让它恢复在任。调不通只记一笔，它让位后没等到对端接手会自己恢复
handoff_resume() {
  active_slot_set "$1" "$2"
  if handoff_post "$1" "$2" resume 10 </dev/null >/dev/null; then
    log "wiib-$1-$2 恢复在任"
  else
    log "让 wiib-$1-$2 恢复在任没调通，等它自己的保险恢复"
  fi
}

# 交接每一步的耗时，攒在 HANDOFF_STEPS 里，最后进汇总
handoff_mark() {
  HANDOFF_MARK=$(date +%s%3N)
}
handoff_step() {
  local now
  now=$(date +%s%3N)
  HANDOFF_STEPS+="$1 $((now - HANDOFF_MARK))ms；"
  HANDOFF_MARK=$now
}

# 交接记录：打一行日志，同样一行进 Redis 列表 wiib:handover:log，留最近 50 条
handoff_record() {
  log "交接记录：$1"
  redis_cli LPUSH wiib:handover:log "$(date '+%F %T') $1" >/dev/null && redis_cli LTRIM wiib:handover:log 0 49 >/dev/null \
    || log "交接记录没写进 Redis"
}

# Redis 写得进去（主从切换中会写不进）
redis_writable() {
  [[ $(redis_cli SET wiib:deploy:probe "$(date +%s)" EX 60 2>/dev/null) == OK ]]
}

# 等到下一个 x4:20 或 x9:20（5 分钟边界前 40 秒），系统时钟是 UTC，按分钟数除以 5 的余数算
wait_switch_time() {
  local wait=$(((260 - $(date +%s) % 300 + 300) % 300))
  log "等切换时刻，${wait}s 后交接"
  sleep "$wait"
}
