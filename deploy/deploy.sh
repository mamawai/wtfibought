#!/usr/bin/env bash
# 部署一个服务：deploy.sh <feed|sim|agent> <提交哈希> --cold
# 由 ci-entry.sh 用 systemd-run 起，在 releases/<哈希>/deploy/ 下跑，脚本和要发的代码是同一个版本。
# 冷切：拿部署锁 → 跑迁移 → 打镜像 → 停旧容器起新容器（关停走第 1 期修好的那套）→ 等就绪 → 记账 → 清旧版本。
# 输出写 releases/<哈希>/<服务>.log，结果写 <服务>.status：running / ok / failed
set -euo pipefail
export LC_ALL=C

svc=${1:-}
hash=${2:-}
mode=${3:-}
[[ $svc =~ ^(feed|sim|agent)$ ]] || { echo "服务名只能是 feed / sim / agent" >&2; exit 2; }

REL=$(cd "$(dirname "$0")/.." && pwd)
ROOT=$(cd "$REL/../.." && pwd)
source "$REL/deploy/lib/common.sh"
source "$REL/deploy/lib/migrate.sh"

STATUS=$REL/$svc.status
exec >>"$REL/$svc.log" 2>&1
echo running >"$STATUS"
trap 'rm -rf "${ctx:-}"; [[ $(cat "$STATUS") == ok ]] || echo failed >"$STATUS"' EXIT

[[ $hash =~ ^[0-9a-f]{7,40}$ ]] || die "哈希不对：$hash"
[[ $mode == --cold ]] || die "现在只有冷切，要带 --cold"

# 服务器上的个别设置（PG 容器名、演练栈的库名），没有就用默认
[[ -f $ROOT/deploy/deploy.conf ]] && source "$ROOT/deploy/deploy.conf"
PG_CONTAINER=${PG_CONTAINER:-postgres}
PG_DB=${PG_DB:-$(env_value PG_DB)}
PG_DB=${PG_DB:-wiib}
PG_USER=$(env_value PG_USER)
PG_PASSWORD=$(env_value PG_PASSWORD)

image=wiib-$svc
container=wiib-$svc
# 新容器起来后最多等多久就绪
READY_TIMEOUT=180

# 同一时刻只跑一个部署；部署锁最多等 10 分钟（短于 CI 等结果的 20 分钟）
mkdir -p "$ROOT/deploy"
exec 9>"$ROOT/deploy/deploy.lock"
log "部署 $svc $hash，等部署锁"
flock -w 600 9 || die "等部署锁超过 10 分钟"

log "跑没跑过的数据库迁移"
migrate "$REL/sql/migrations"

log "打镜像 $image:$hash"
ctx=$(mktemp -d)
cp "$REL/wiib-$svc/Dockerfile" "$ctx/Dockerfile"
mkdir "$ctx/target"
cp "$REL/$svc.jar" "$ctx/target/$svc.jar"
docker build -t "$image:$hash" "$ctx"
rm -rf "$ctx"

# compose 里的镜像是 <服务>:current，指到这次的哈希；compose 文件用这个版本自带的
docker tag "$image:$hash" "$image:current"
cp "$REL/docker-compose.yml" "$ROOT/docker-compose.yml"

log "停旧的、起新的"
(cd "$ROOT" && docker compose up -d --no-deps --no-build --force-recreate "$container")

log "等就绪（/actuator/health 是 UP，最多 ${READY_TIMEOUT}s）"
port=$(service_port "$svc")
deadline=$((SECONDS + READY_TIMEOUT))
until docker exec "$container" wget -qO- "http://127.0.0.1:$port/actuator/health" 2>/dev/null | grep -q '"status":"UP"'; do
  ((SECONDS < deadline)) || die "${READY_TIMEOUT}s 内没就绪。要回滚就把上一个哈希 $(state_get "$svc" current) 再发一次"
  sleep 3
done

# 记账：当前和上一个哈希。重发同一个哈希时上一个不变
current=$(state_get "$svc" current)
previous=$(state_get "$svc" previous)
[[ $current == "$hash" ]] || previous=$current
mkdir -p "$STATE"
printf 'current=%s\nprevious=%s\n' "$hash" "$previous" >"$STATE/$svc.tmp"
mv "$STATE/$svc.tmp" "$STATE/$svc"

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
log "完成：$svc 现在是 $hash"
