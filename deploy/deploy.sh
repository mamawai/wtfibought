#!/usr/bin/env bash
# 部署一个服务：deploy.sh <feed|sim|agent> <提交哈希> --handover|--cold [--now]
# 对齐网关：deploy.sh doctor（systemd timer 每分钟跑一次）
# 等旧实例清空再停掉：deploy.sh drain <服务> <槽位>（交接切完后部署自己用 systemd-run 起，不拿部署锁）
# 由 ci-entry.sh 用 systemd-run 起，在 releases/<哈希>/deploy/ 下跑，脚本和要发的代码是同一个版本。
# 交接（文档 4.5）：拿部署锁 → 读在任槽位、把网关对齐到它 → 新槽位上还有上一次的旧实例在收尾就等它清空、停掉 → 跑迁移
#   → 打镜像 → 看可用内存 → 起新槽位、等它待命 → 比交接协议版本 → 等切换时刻（x4:20、x9:20，--now 不等）→ 确认 Redis 写得进
#   → 服务自己的交接（deploy/services/<服务>.sh）→ 确认新实例在任 → 记账 → 后台等旧实例清空再停。
#   服务还没有交接、或者没有在跑的在任实例，改走冷切。
# 冷切：拿部署锁 → 对齐网关 → 跑迁移 → 打镜像 → 停掉这个服务的两个槽位（关停走第 1 期修好的那套）
#   → Redis 记成另一个槽位 → 起那个槽位（启动时读到键是自己，冷启动上任）→ 等就绪 → 网关指过去 → 记账。
# 输出写 releases/<哈希>/<服务>.log，结果写 <服务>.status：running / ok / failed
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

# 服务器上的个别设置（PG 容器名、演练栈的库名和 Redis 容器），没有就用默认
[[ -f $ROOT/deploy/deploy.conf ]] && source "$ROOT/deploy/deploy.conf"
PG_CONTAINER=${PG_CONTAINER:-postgres}
PG_DB=${PG_DB:-$(env_value PG_DB)}
PG_DB=${PG_DB:-wiib}
PG_USER=$(env_value PG_USER)
PG_PASSWORD=$(env_value PG_PASSWORD)
REDIS_SENTINEL=${REDIS_SENTINEL:-redis-sentinel-1}
REDIS_SENTINEL_PORT=${REDIS_SENTINEL_PORT:-26379}
REDIS_MASTER_NAME=${REDIS_MASTER_NAME:-mymaster}

mkdir -p "$ROOT/deploy"
exec 9>"$ROOT/deploy/deploy.lock"

# 对齐网关：没有部署在跑时，每个服务的网关指的槽位要和 Redis 里的在任槽位一样，不一样按 Redis 改网关
if [[ ${1:-} == doctor ]]; then
  flock -n 9 || exit 0
  for s in feed sim agent; do
    active=$(active_slot_get "$s")
    [[ -n $active && $(gateway_slot "$s") != "$active" ]] || continue
    log "网关的 $s 指着 $(gateway_slot "$s")，Redis 里在任的是 $active，改过来"
    gateway_point "$s" "$active"
  done
  exit 0
fi

# 后台等交接下来的旧实例清空再停掉（文档 6.10）
if [[ ${1:-} == drain ]]; then
  svc=$2
  slot=$3
  exec >>"$REL/$svc.log" 2>&1
  source "$REL/deploy/services/$svc.sh"
  slot_wait_drained "$svc" "$slot" "$DRAIN_TIMEOUT"
  slot_stop "$svc" "$slot"
  handoff_record "$svc 旧实例 $slot 清空、停掉，用时 ${SECONDS}s"
  exit 0
fi

svc=${1:-}
hash=${2:-}
mode=${3:-}
now=${4:-}
[[ $svc =~ ^(feed|sim|agent)$ ]] || { echo "服务名只能是 feed / sim / agent" >&2; exit 2; }

STATUS=$REL/$svc.status
exec >>"$REL/$svc.log" 2>&1
echo running >"$STATUS"
# 交接切过去之前失败，这个槽位上新起的容器要删掉
PENDING_SLOT=
on_exit() {
  rm -rf "${ctx:-}"
  [[ $(cat "$STATUS") == ok ]] || echo failed >"$STATUS"
  if [[ -n $PENDING_SLOT ]]; then
    log "删掉没用上的 wiib-$svc-$PENDING_SLOT"
    slot_stop "$svc" "$PENDING_SLOT"
  fi
}
trap on_exit EXIT

[[ $hash =~ ^[0-9a-f]{7,40}$ ]] || die "哈希不对：$hash"
[[ $mode =~ ^--(handover|cold)$ ]] || die "模式要是 --handover 或 --cold"
[[ -z $now || $now == --now ]] || die "第四个参数只能是 --now"

# 新槽位起来后最多等多久就绪
READY_TIMEOUT=180

# 同一时刻只跑一个部署；部署锁最多等 10 分钟
log "部署 $svc $hash（${mode#--}），等部署锁"
flock -w 600 9 || die "等部署锁超过 10 分钟"

[[ $(docker inspect -f '{{.State.Running}}' "$GATEWAY" 2>/dev/null) == true ]] \
  || die "网关没在跑：还没迁到槽位的话先跑一次 migrate-slots.sh"
active=$(active_slot_get "$svc")
target=$(other_slot "${active:-b}")
log "在任槽位 ${active:-（没记过）}，这次起到 $target"
[[ -z $active ]] || [[ $(gateway_slot "$svc") == "$active" ]] || gateway_point "$svc" "$active"

if [[ $mode == --handover ]]; then
  if [[ ! -f $REL/deploy/services/$svc.sh ]]; then
    log "$svc 还没有交接，改走冷切"
    mode=--cold
  elif [[ -z $active ]] || ! slot_running "$svc" "$active"; then
    log "没有在跑的在任实例，改走冷切"
    mode=--cold
  fi
fi

if [[ $mode == --handover ]]; then
  source "$REL/deploy/services/$svc.sh"
  if slot_exists "$svc" "$target"; then
    [[ $(json_field role "$(handoff_status "$svc" "$target" || true)") != ACTIVE ]] \
      || die "wiib-$svc-$target 自称在任，和 Redis 记的 $active 对不上，手工看一下"
    log "槽位 $target 上还有上一次的实例，等它清空（最多 ${DRAIN_TIMEOUT}s）"
    slot_wait_drained "$svc" "$target" "$DRAIN_TIMEOUT"
    slot_stop "$svc" "$target"
  fi
fi

log "跑没跑过的数据库迁移"
migrate "$REL/sql/migrations"

log "打镜像 wiib-$svc:$hash"
ctx=$(mktemp -d)
cp "$REL/wiib-$svc/Dockerfile" "$ctx/Dockerfile"
mkdir "$ctx/target"
cp "$REL/$svc.jar" "$ctx/target/$svc.jar"
docker build -t "wiib-$svc:$hash" "$ctx"
rm -rf "$ctx"

# compose 文件和网关配置用这个版本自带的
cp "$REL/docker-compose.yml" "$ROOT/docker-compose.yml"
gateway_install "$REL"

if [[ $mode == --cold ]]; then
  log "冷切：停掉这个服务的两个槽位，起 $target"
  slot_stop "$svc" a
  slot_stop "$svc" b
  active_slot_set "$svc" "$target"
  slot_start "$svc" "$target" "$hash"
  slot_wait_role "$svc" "$target" "$READY_TIMEOUT" ACTIVE \
    || die "wiib-$svc-$target ${READY_TIMEOUT}s 内没就绪。要回滚就把上一个哈希 $(state_get "$svc" current) 再发一次"
  gateway_point "$svc" "$target"
else
  avail=$(awk '/^MemAvailable:/ {print int($2 / 1024)}' /proc/meminfo)
  ((avail >= 4096)) || die "可用内存 ${avail}M，不到 4G，不起新实例"
  PENDING_SLOT=$target
  slot_start "$svc" "$target" "$hash"
  slot_wait_role "$svc" "$target" "$READY_TIMEOUT" STANDBY || die "wiib-$svc-$target ${READY_TIMEOUT}s 内没起好待命"
  old_status=$(handoff_status "$svc" "$active") || die "调不通 wiib-$svc-$active 的 /status"
  new_status=$(handoff_status "$svc" "$target") || die "调不通 wiib-$svc-$target 的 /status"
  old_protocol=$(json_field protocol "$old_status")
  new_protocol=$(json_field protocol "$new_status")
  [[ $old_protocol == "$new_protocol" ]] \
    || die "交接协议版本不一样（在任的 $old_protocol，新的 $new_protocol），这次只能冷切：手动触发选 cold，或者提交信息带 [cold]"
  [[ -n $now ]] || wait_switch_time
  redis_writable || die "Redis 写不进去（可能在主从切换），这次不切"
  HANDOFF_STEPS=
  service_handover "$active" "$target"
  [[ $(json_field role "$(handoff_status "$svc" "$target" || true)") == ACTIVE ]] || die "交接后 wiib-$svc-$target 不是在任"
  [[ $(active_slot_get "$svc") == "$target" ]] || die "交接后 Redis 记的在任槽位不是 $target"
  handoff_record "$svc $active→$target $hash ${HANDOFF_STEPS}交接单 $HANDOFF_NOTE"
fi

# 记账：当前和上一个哈希。重发同一个哈希时上一个不变
current=$(state_get "$svc" current)
previous=$(state_get "$svc" previous)
[[ $current == "$hash" ]] || previous=$current
mkdir -p "$STATE"
printf 'current=%s\nprevious=%s\n' "$hash" "$previous" >"$STATE/$svc.tmp"
mv "$STATE/$svc.tmp" "$STATE/$svc"
# 对齐网关的定时任务用最近一次部署的脚本
ln -sfn "$REL/deploy" "$ROOT/deploy/scripts"

# 交接下来的旧实例：后台等它清空再停，不占部署锁，CI 接着发下一个服务
if [[ $mode == --handover ]]; then
  systemd-run --unit="wiib-drain-$svc-$active" --collect --quiet --setenv=HOME="${HOME:-/root}" \
    /bin/bash "$REL/deploy/deploy.sh" drain "$svc" "$active" \
    || log "起后台清旧实例的任务失败，wiib-$svc-$active 留给下一次部署第一步去等、去停"
fi

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

echo ok >"$STATUS"
log "完成：$svc 现在是 $hash，在槽位 $target"
