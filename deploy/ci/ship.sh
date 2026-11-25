#!/usr/bin/env bash
# CI 的部署步骤，在仓库根目录跑：问服务器各服务部署的哈希 → 算这次发哪些 → 传 bundle 和 jar →
# 按 feed → sim → agent 逐个部署，每个等出结果再发下一个，有一个失败就停。
# 环境变量：DEPLOY_HOST、DEPLOY_USER、GITHUB_SHA；SERVICES 是手动触发时点名的服务，可空。
# 产物在 release/（bundle.tgz、feed.jar、sim.jar、agent.jar），私钥在 ~/.ssh/deploy_key
set -euo pipefail

sha=$GITHUB_SHA
# 一个服务从起部署到出结果最多等多久
DEPLOY_TIMEOUT=1200

# 调一次服务器上的 ci-entry：连不上（退出码 255）最多重试 4 次，远端命令自己失败不重试；
# 输入文件每次重新打开，重试时从头传
ssh_retry() {
  local input=$1 attempt rc
  shift
  for attempt in 1 2 3 4; do
    if ssh -i ~/.ssh/deploy_key -o BatchMode=yes -o ConnectTimeout=20 "$DEPLOY_USER@$DEPLOY_HOST" "$*" <"$input"; then
      return 0
    else
      rc=$?
    fi
    ((rc == 255)) || return "$rc"
    echo "ssh 没连上（第 $attempt 次），稍后重试" >&2
    sleep $((attempt * 5))
  done
  return 255
}
remote() { ssh_retry /dev/null "$@"; }
upload() {
  local file=$1
  shift
  ssh_retry "$file" "$@"
}

declare -A deployed
for s in feed sim agent; do
  deployed[$s]=$(remote deployed "$s")
done
echo "服务器上现在是：feed=${deployed[feed]:-没部署过} sim=${deployed[sim]:-没部署过} agent=${deployed[agent]:-没部署过}"

plan=$(bash deploy/ci/plan.sh "$sha" "${SERVICES:-}" "${deployed[feed]}" "${deployed[sim]}" "${deployed[agent]}")
if [[ -z $plan ]]; then
  echo "这次没有要发的服务"
  exit 0
fi
mapfile -t todo <<<"$plan"
echo "这次发：${todo[*]}"

upload release/bundle.tgz upload-bundle "$sha"
for s in "${todo[@]}"; do
  upload "release/$s.jar" upload-jar "$s" "$sha"
done

for s in "${todo[@]}"; do
  echo "== 部署 $s"
  remote deploy "$s" "$sha" cold
  deadline=$((SECONDS + DEPLOY_TIMEOUT))
  while :; do
    sleep 10
    out=$(remote deploy-status "$s" "$sha")
    case ${out%%$'\n'*} in
      ok)
        echo "$out"
        break
        ;;
      failed | none)
        echo "$out"
        echo "$s 部署失败" >&2
        exit 1
        ;;
    esac
    if ((SECONDS > deadline)); then
      echo "$out"
      echo "等 $s 部署超过 ${DEPLOY_TIMEOUT}s" >&2
      exit 1
    fi
  done
done
