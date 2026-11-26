#!/usr/bin/env bash
# 一次性迁到槽位：先把网关的镜像拉好，再停掉并删掉旧的 wiib-feed / wiib-sim / wiib-agent 三个容器，
# 起网关（三个服务都指槽位 a），用旧容器正在跑的镜像起三个服务的 a 槽位，Redis 记成 a。期间约一分钟不可用。
# 中途失败可以再跑一次：旧容器已经删了的，用上次记下的 legacy 镜像。之后 CI 的部署就从 a 切到 b。
# 用法：这个版本的 bundle 解到 releases/<哈希>/ 后，bash <项目目录>/releases/<哈希>/deploy/migrate-slots.sh
set -euo pipefail
export LC_ALL=C

REL=$(cd "$(dirname "$(readlink -f "$0")")/.." && pwd)
ROOT=$(cd "$REL/../.." && pwd)
source "$REL/deploy/lib/common.sh"
source "$REL/deploy/lib/redis.sh"
source "$REL/deploy/lib/gateway.sh"
source "$REL/deploy/lib/slot.sh"

[[ -f $ROOT/deploy/deploy.conf ]] && source "$ROOT/deploy/deploy.conf"
REDIS_SENTINEL=${REDIS_SENTINEL:-redis-sentinel-1}
REDIS_SENTINEL_PORT=${REDIS_SENTINEL_PORT:-26379}
REDIS_MASTER_NAME=${REDIS_MASTER_NAME:-mymaster}

mkdir -p "$ROOT/deploy"
exec 9>"$ROOT/deploy/deploy.lock"
flock -w 600 9 || die "等部署锁超过 10 分钟"

# 旧容器的镜像记成 wiib-<服务>:legacy，a 槽位先跑它
for s in feed sim agent; do
  if image=$(docker inspect -f '{{.Config.Image}}' "wiib-$s" 2>/dev/null); then
    docker tag "$image" "wiib-$s:legacy"
    log "旧容器 wiib-$s 的镜像 $image 记成 wiib-$s:legacy"
  else
    docker image inspect "wiib-$s:legacy" >/dev/null 2>&1 || die "没有旧容器 wiib-$s，也没有上次记下的 wiib-$s:legacy"
    log "旧容器 wiib-$s 已经删了，用上次记下的 wiib-$s:legacy"
  fi
done

cp "$REL/docker-compose.yml" "$ROOT/docker-compose.yml"
# 网关的镜像本机没有就先拉好，旧容器这时还在跑
gateway_image=$(cd "$ROOT" && docker compose config --images "$GATEWAY")
docker image inspect "$gateway_image" >/dev/null 2>&1 || docker pull "$gateway_image" \
  || die "网关镜像 $gateway_image 拉不下来，旧容器没动"
mkdir -p "$GATEWAY_DIR/active"
cp "$REL/deploy/gateway/nginx.conf" "$GATEWAY_DIR/nginx.conf"
for s in feed sim agent; do
  printf 'set $%s_upstream wiib-%s-a:%s;\n' "$s" "$s" "$(service_port "$s")" >"$GATEWAY_DIR/active/$s.conf"
done

# 旧容器和网关的别名重名，先停掉删掉；先停调用方
for s in agent sim feed; do
  docker inspect "wiib-$s" >/dev/null 2>&1 || continue
  log "停旧容器 wiib-$s"
  docker stop "wiib-$s" >/dev/null
  docker rm "wiib-$s" >/dev/null
done

gateway_install "$REL"

for s in feed sim agent; do
  active_slot_set "$s" a
  slot_start "$s" a legacy
done
for s in feed sim agent; do
  # 旧版本还没有交接接口，只看健康检查
  port=$(service_port "$s")
  deadline=$((SECONDS + 180))
  until docker exec "wiib-$s-a" wget -qO- "http://127.0.0.1:$port/actuator/health" 2>/dev/null | grep -q '"status":"UP"'; do
    ((SECONDS < deadline)) || die "wiib-$s-a 180s 内没就绪"
    sleep 3
  done
  log "wiib-$s-a 就绪"
done

ln -sfn "$REL/deploy" "$ROOT/deploy/scripts"
log "迁到槽位完成：三个服务都在 a，网关已起"
