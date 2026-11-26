#!/usr/bin/env bash
# 部署一个或几个服务：deploy.sh <服务[,服务...]> <提交哈希> --handover|--cold [--now]
#   服务是 feed / sim / agent。几个一起发时不管写的先后，都按 feed → sim → agent 来
# 对齐网关：deploy.sh doctor（systemd timer 每分钟跑一次）
# 等旧实例清空再停掉：deploy.sh drain <服务> <槽位> <容器 ID>（交接切完后部署自己用 systemd-run 起，不拿部署锁）
# 由 ci-entry.sh 用 systemd-run 起，在 releases/<哈希>/deploy/ 下跑，脚本和要发的代码是同一个版本。
# 交接（文档 4.5）：拿部署锁 → 每个服务读在任槽位、把网关对齐到它，新槽位上还有上一次的旧实例在收尾就等它清空、停掉
#   → 跑迁移 → 打镜像 → 逐个起新槽位、等它待命、比交接协议版本（起之前看可用内存）→ 等切换时刻（x4:20、x9:20，--now 不等）
#   → 按顺序逐个切：确认 Redis 写得进 → 服务自己的交接（deploy/services/<服务>.sh）→ 确认新实例在任 → 记账 → 后台等旧实例清空再停。
#   几个服务在同一个切换时刻里接着切；轮到某个服务时已经过了 x4:45 / x9:45，它等下一个切换时刻。
#   服务还没有交接、或者没有在跑的在任实例，这个服务改走冷切。
# 冷切：轮到这个服务时，停掉它的两个槽位（关停走第 1 期修好的那套）→ Redis 记成另一个槽位
#   → 起那个槽位（启动时读到键是自己，冷启动上任）→ 等就绪 → 网关指过去 → 记账。
# 输出写 releases/<哈希>/<名字>.log，结果写 <名字>.status：running / ok / failed。名字是服务列表里的逗号换成 -
set -euo pipefail
export LC_ALL=C

# 按真实路径算：对齐任务是经 deploy/scripts 这个软链调进来的
REL=$(cd "$(dirname "$(readlink -f "$0")")/.." && pwd)
ROOT=$(cd "$REL/../.." && pwd)
source "$REL/deploy/lib/common.sh"
source "$REL/deploy/lib/migrate.sh"
source "$REL/deploy/lib/redis.sh"
source "$REL/deploy/lib/gateway.sh"
source "$REL/deploy/lib/handoff.sh"
source "$REL/deploy/lib/slot.sh"

# 服务器上的个别设置（PG 容器名、演练栈的库名和 Redis 容器、起新实例要的可用内存），没有就用默认
[[ -f $ROOT/deploy/deploy.conf ]] && source "$ROOT/deploy/deploy.conf"
PG_CONTAINER=${PG_CONTAINER:-postgres}
PG_DB=${PG_DB:-$(env_value PG_DB)}
PG_DB=${PG_DB:-wiib}
PG_USER=$(env_value PG_USER)
PG_PASSWORD=$(env_value PG_PASSWORD)
REDIS_SENTINEL=${REDIS_SENTINEL:-redis-sentinel-1}
REDIS_SENTINEL_PORT=${REDIS_SENTINEL_PORT:-26379}
REDIS_MASTER_NAME=${REDIS_MASTER_NAME:-mymaster}
# 起一个新实例之前至少要有多少可用内存（M）
MIN_AVAIL_MB=${MIN_AVAIL_MB:-4096}

mkdir -p "$ROOT/deploy"
exec 9>"$ROOT/deploy/deploy.lock"

# 对齐网关：没有部署在跑时，每个服务的网关指的槽位要和 Redis 里的在任槽位一样，不一样按 Redis 改网关。
# Redis 记的那个槽位自己说在任才改；它不是在任（或调不通）就只报出来，不动网关
if [[ ${1:-} == doctor ]]; then
  flock -n 9 || exit 0
  for s in feed sim agent; do
    active=$(active_slot_get "$s")
    [[ -n $active && $(gateway_slot "$s") != "$active" ]] || continue
    if [[ $(json_field role "$(handoff_status "$s" "$active" || true)") != ACTIVE ]]; then
      log "网关的 $s 指着 $(gateway_slot "$s")，Redis 里在任的是 $active，但 wiib-$s-$active 不是在任（或调不通），不改网关，手工看一下"
      continue
    fi
    log "网关的 $s 指着 $(gateway_slot "$s")，Redis 里在任的是 $active，改过来"
    gateway_point "$s" "$active"
  done
  exit 0
fi

# 后台等交接下来的旧实例清空再停掉（文档 6.10）。只认交接那一刻的容器 ID：这个槽位后来换了容器（下一次部署起的）也不碰
if [[ ${1:-} == drain ]]; then
  svc=$2
  slot=$3
  cid=$4
  exec >>"$REL/$svc.log" 2>&1
  source "$REL/deploy/services/$svc.sh"
  wait_drained "$svc" "$cid" "$DRAIN_TIMEOUT"
  log "停旧实例 wiib-$svc-$slot（容器 ${cid:0:12}）"
  container_stop "$cid"
  handoff_record "$svc 旧实例 $slot 清空、停掉，用时 ${SECONDS}s"
  exit 0
fi

list=${1:-}
hash=${2:-}
mode=${3:-}
now=${4:-}
[[ $list =~ ^(feed|sim|agent)(,(feed|sim|agent))*$ ]] || { echo "服务只能是 feed / sim / agent，几个用逗号隔开" >&2; exit 2; }
svcs=()
for s in feed sim agent; do
  if [[ ",$list," == *",$s,"* ]]; then
    svcs+=("$s")
  fi
done
name=${list//,/-}

STATUS=$REL/$name.status
# 写结果：先写临时文件再改名，查结果的一方读不到写了一半的
status_set() {
  echo "$1" >"$STATUS.tmp"
  mv "$STATUS.tmp" "$STATUS"
}
exec >>"$REL/$name.log" 2>&1
status_set running
# 交接切过去之前失败，这些槽位上新起的容器要删掉：服务 → 槽位
declare -A PENDING=()
on_exit() {
  local s
  rm -rf "${ctx:-}"
  [[ $(cat "$STATUS") == ok ]] || status_set failed
  for s in "${!PENDING[@]}"; do
    log "删掉没用上的 wiib-$s-${PENDING[$s]}"
    slot_stop "$s" "${PENDING[$s]}"
  done
}
trap on_exit EXIT

[[ $hash =~ ^[0-9a-f]{7,40}$ ]] || die "哈希不对：$hash"
[[ $mode =~ ^--(handover|cold)$ ]] || die "模式要是 --handover 或 --cold"
[[ -z $now || $now == --now ]] || die "第四个参数只能是 --now"

# 新槽位起来后最多等多久就绪
READY_TIMEOUT=180

# 同一时刻只跑一个部署；部署锁最多等 10 分钟
log "部署 ${svcs[*]} $hash（${mode#--}），等部署锁"
flock -w 600 9 || die "等部署锁超过 10 分钟"

[[ $(docker inspect -f '{{.State.Running}}' "$GATEWAY" 2>/dev/null) == true ]] \
  || die "网关没在跑：还没迁到槽位的话先跑一次 migrate-slots.sh"

# 每个服务：在任的槽位、这次起到哪个槽位、走交接还是冷切
declare -A ACTIVE=() TARGET=() MODE=()
for s in "${svcs[@]}"; do
  ACTIVE[$s]=$(active_slot_get "$s")
  TARGET[$s]=$(other_slot "${ACTIVE[$s]:-b}")
  MODE[$s]=$mode
  log "$s 在任槽位 ${ACTIVE[$s]:-（没记过）}，这次起到 ${TARGET[$s]}"
  [[ -z ${ACTIVE[$s]} ]] || [[ $(gateway_slot "$s") == "${ACTIVE[$s]}" ]] || gateway_point "$s" "${ACTIVE[$s]}"
  if [[ $mode == --handover ]]; then
    if [[ ! -f $REL/deploy/services/$s.sh ]]; then
      log "$s 还没有交接，改走冷切"
      MODE[$s]=--cold
    elif [[ -z ${ACTIVE[$s]} ]] || ! slot_running "$s" "${ACTIVE[$s]}"; then
      log "$s 没有在跑的在任实例，改走冷切"
      MODE[$s]=--cold
    fi
  fi
  if [[ ${MODE[$s]} == --handover ]] && slot_exists "$s" "${TARGET[$s]}"; then
    source "$REL/deploy/services/$s.sh"
    [[ $(json_field role "$(handoff_status "$s" "${TARGET[$s]}" || true)") != ACTIVE ]] \
      || die "wiib-$s-${TARGET[$s]} 自称在任，和 Redis 记的 ${ACTIVE[$s]} 对不上，手工看一下"
    log "$s 槽位 ${TARGET[$s]} 上还有上一次的实例，等它清空（最多 ${DRAIN_TIMEOUT}s）"
    wait_drained "$s" "wiib-$s-${TARGET[$s]}" "$DRAIN_TIMEOUT"
    slot_stop "$s" "${TARGET[$s]}"
  fi
done

log "跑没跑过的数据库迁移"
migrate "$REL/sql/migrations"

for s in "${svcs[@]}"; do
  log "打镜像 wiib-$s:$hash"
  ctx=$(mktemp -d)
  cp "$REL/wiib-$s/Dockerfile" "$ctx/Dockerfile"
  mkdir "$ctx/target"
  cp "$REL/$s.jar" "$ctx/target/$s.jar"
  docker build -t "wiib-$s:$hash" "$ctx"
  rm -rf "$ctx"
done

# compose 文件和网关配置用这个版本自带的
cp "$REL/docker-compose.yml" "$ROOT/docker-compose.yml"
gateway_install "$REL"

# 走交接的服务先都起好待命，一个起好再起下一个。到这里线上没有任何变化
for s in "${svcs[@]}"; do
  [[ ${MODE[$s]} == --handover ]] || continue
  avail=$(awk '/^MemAvailable:/ {print int($2 / 1024)}' /proc/meminfo)
  ((avail >= MIN_AVAIL_MB)) || die "可用内存 ${avail}M，不到 ${MIN_AVAIL_MB}M，不起新实例"
  PENDING[$s]=${TARGET[$s]}
  slot_start "$s" "${TARGET[$s]}" "$hash"
  slot_wait_role "$s" "${TARGET[$s]}" "$READY_TIMEOUT" STANDBY || die "wiib-$s-${TARGET[$s]} ${READY_TIMEOUT}s 内没起好待命"
  old_status=$(handoff_status "$s" "${ACTIVE[$s]}") || die "调不通 wiib-$s-${ACTIVE[$s]} 的 /status"
  new_status=$(handoff_status "$s" "${TARGET[$s]}") || die "调不通 wiib-$s-${TARGET[$s]} 的 /status"
  old_protocol=$(json_field protocol "$old_status")
  new_protocol=$(json_field protocol "$new_status")
  [[ $old_protocol == "$new_protocol" ]] \
    || die "$s 的交接协议版本不一样（在任的 $old_protocol，新的 $new_protocol），这次只能冷切：手动触发选 cold，或者提交信息带 [cold]"
done

# 按顺序逐个切。头一个走交接的等切换时刻，后面的接着切；轮到时已经出了切换窗口的等下一个时刻
waited=
for s in "${svcs[@]}"; do
  active=${ACTIVE[$s]}
  target=${TARGET[$s]}
  if [[ ${MODE[$s]} == --cold ]]; then
    log "$s 冷切：停掉这个服务的两个槽位，起 $target"
    slot_stop "$s" a
    slot_stop "$s" b
    active_slot_set "$s" "$target"
    slot_start "$s" "$target" "$hash"
    slot_wait_role "$s" "$target" "$READY_TIMEOUT" ACTIVE \
      || die "wiib-$s-$target ${READY_TIMEOUT}s 内没就绪。要回滚就把上一个哈希 $(state_get "$s" current) 再发一次"
    gateway_point "$s" "$target"
  else
    if [[ -z $now ]] && { [[ -z $waited ]] || ! in_switch_window; }; then
      wait_switch_time
    fi
    waited=1
    redis_writable || die "Redis 写不进去（可能在主从切换），这次不切"
    # 旧实例的容器 ID，交接完后台清它时用
    old_cid=$(docker container inspect -f '{{.Id}}' "wiib-$s-$active")
    source "$REL/deploy/services/$s.sh"
    HANDOFF_STEPS=
    service_handover "$active" "$target"
    [[ $(json_field role "$(handoff_status "$s" "$target" || true)") == ACTIVE ]] || die "交接后 wiib-$s-$target 不是在任"
    [[ $(active_slot_get "$s") == "$target" ]] || die "交接后 Redis 记的 $s 在任槽位不是 $target"
    handoff_record "$s $active→$target $hash ${HANDOFF_STEPS}交接单 $HANDOFF_NOTE"
  fi

  # 记账：当前和上一个哈希。重发同一个哈希时上一个不变
  current=$(state_get "$s" current)
  previous=$(state_get "$s" previous)
  [[ $current == "$hash" ]] || previous=$current
  mkdir -p "$STATE"
  printf 'current=%s\nprevious=%s\n' "$hash" "$previous" >"$STATE/$s.tmp"
  mv "$STATE/$s.tmp" "$STATE/$s"
  # 对齐网关的定时任务用最近一次部署的脚本
  ln -sfn "$REL/deploy" "$ROOT/deploy/scripts"

  # 交接下来的旧实例：后台等它清空再停，不占部署锁
  if [[ ${MODE[$s]} == --handover ]]; then
    systemd-run --unit="wiib-drain-$s-$active" --collect --quiet --setenv=HOME="${HOME:-/root}" \
      /bin/bash "$REL/deploy/deploy.sh" drain "$s" "$active" "$old_cid" \
      || log "起后台清旧实例的任务失败，wiib-$s-$active 留给下一次部署第一步去等、去停"
  fi
  log "完成：$s 现在是 $hash，在槽位 $target"
done

# 留最近 5 个版本的目录和镜像；各服务当前和上一个用到的哈希不删
keep=$({ ls -1t "$RELEASES" | head -n 5; sed -n 's/^\(current\|previous\)=//p' "$STATE"/* 2>/dev/null || true; } | sort -u)
for d in "$RELEASES"/*/; do
  h=$(basename "$d")
  [[ $h =~ ^[0-9a-f]{7,40}$ ]] || continue
  grep -qxF "$h" <<<"$keep" || { log "删旧版本目录 $h"; rm -rf -- "${RELEASES:?}/$h"; }
done
for s in feed sim agent; do
  for tag in $(docker image ls "wiib-$s" --format '{{.Tag}}'); do
    [[ $tag =~ ^[0-9a-f]{7,40}$ ]] || continue
    grep -qxF "$tag" <<<"$keep" || docker rmi "wiib-$s:$tag" >/dev/null 2>&1 || true
  done
done

status_set ok
