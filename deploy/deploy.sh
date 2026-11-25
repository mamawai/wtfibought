#!/usr/bin/env bash
# 部署一个服务：deploy.sh <feed|sim|agent> <提交哈希> --cold
# 对齐网关：deploy.sh doctor（systemd timer 每分钟跑一次）
# 由 ci-entry.sh 用 systemd-run 起，在 releases/<哈希>/deploy/ 下跑，脚本和要发的代码是同一个版本。
# 冷切：拿部署锁 → 读在任槽位、把网关对齐到它 → 跑迁移 → 打镜像 → 停掉这个服务的两个槽位（关停走第 1 期修好的那套）→
# Redis 记成另一个槽位 → 起那个槽位（启动时读到键是自己，冷启动上任）→ 等就绪 → 网关指过去 → 记账 → 清旧版本。
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

svc=${1:-}
hash=${2:-}
mode=${3:-}
[[ $svc =~ ^(feed|sim|agent)$ ]] || { echo "服务名只能是 feed / sim / agent" >&2; exit 2; }

STATUS=$REL/$svc.status
exec >>"$REL/$svc.log" 2>&1
echo running >"$STATUS"
trap 'rm -rf "${ctx:-}"; [[ $(cat "$STATUS") == ok ]] || echo failed >"$STATUS"' EXIT

[[ $hash =~ ^[0-9a-f]{7,40}$ ]] || die "哈希不对：$hash"
[[ $mode == --cold ]] || die "现在只有冷切，要带 --cold"

# 新槽位起来后最多等多久就绪
READY_TIMEOUT=180

# 同一时刻只跑一个部署；部署锁最多等 10 分钟（短于 CI 等结果的 20 分钟）
log "部署 $svc $hash，等部署锁"
flock -w 600 9 || die "等部署锁超过 10 分钟"

[[ $(docker inspect -f '{{.State.Running}}' "$GATEWAY" 2>/dev/null) == true ]] || die "网关没在跑：还没迁到槽位的话先跑一次 migrate-slots.sh"
active=$(active_slot_get "$svc")
target=$(other_slot "${active:-b}")
log "在任槽位 ${active:-（没记过）}，这次起到 $target"
[[ -z $active ]] || [[ $(gateway_slot "$svc") == "$active" ]] || gateway_point "$svc" "$active"

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

log "冷切：停掉这个服务的两个槽位，起 $target"
slot_stop "$svc" a
slot_stop "$svc" b
active_slot_set "$svc" "$target"
slot_start "$svc" "$target" "$hash"
slot_wait_active "$svc" "$target" "$READY_TIMEOUT" \
  || die "wiib-$svc-$target ${READY_TIMEOUT}s 内没就绪。要回滚就把上一个哈希 $(state_get "$svc" current) 再发一次"
gateway_point "$svc" "$target"

# 记账：当前和上一个哈希。重发同一个哈希时上一个不变
current=$(state_get "$svc" current)
previous=$(state_get "$svc" previous)
[[ $current == "$hash" ]] || previous=$current
mkdir -p "$STATE"
printf 'current=%s\nprevious=%s\n' "$hash" "$previous" >"$STATE/$svc.tmp"
mv "$STATE/$svc.tmp" "$STATE/$svc"
# 对齐网关的定时任务用最近一次部署的脚本
ln -sfn "$REL/deploy" "$ROOT/deploy/scripts"

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
