#!/usr/bin/env bash
# CI 的部署步骤，在仓库根目录跑：问服务器各服务部署的哈希 → 算这次发哪些、各用什么模式 → 传 bundle 和 jar →
# 按 feed → sim → agent 排好，相邻、模式一样的服务合成一批，一批调一次部署（新实例先都起好，同一个切换时刻里依次切）；
# 一批等到切换完成再发下一批（不等旧实例清空），有一批失败就停。
# 环境变量：DEPLOY_HOST、DEPLOY_USER、GITHUB_SHA、GITHUB_RUN_ID、GITHUB_RUN_ATTEMPT；
# SERVICES 是手动触发时点名的服务，可空；MODE 是手动触发时选的模式，push 来的是空，按 handover。
# AUTO_DEPLOY 只在自动部署的 job 里给：这次要发的服务有不在它里面的，什么都不发，在 GITHUB_OUTPUT 里写 manual=true 交给要审批的 job。
# 产物在 release/（bundle.tgz、feed.jar、sim.jar、agent.jar），私钥在 ~/.ssh/deploy_key
set -euo pipefail

sha=$GITHUB_SHA
# 一批从起部署到出结果最多等多久：上一次交接下来的旧实例还在收尾要先等它（agent 最多 35 分钟），
# 再逐个起新实例（每个最多 3 分钟），再加等切换时刻的 5 分钟
DEPLOY_TIMEOUT=3600

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

plan=$(bash deploy/ci/plan.sh "$sha" "${SERVICES:-}" "${MODE:-handover}" \
  "${deployed[feed]}" "${deployed[sim]}" "${deployed[agent]}")
if [[ -z $plan ]]; then
  echo "这次没有要发的服务"
  exit 0
fi
mapfile -t todo <<<"$plan"
echo "这次发：$(paste -sd ',' <<<"$plan")"

if [[ -n ${AUTO_DEPLOY:-} ]]; then
  for line in "${todo[@]}"; do
    s=${line% *}
    if [[ ",${AUTO_DEPLOY//[[:space:]]/}," != *",$s,"* ]]; then
      echo "$s 不在自动部署名单（$AUTO_DEPLOY）里，这一批交给要审批的部署"
      echo "manual=true" >>"$GITHUB_OUTPUT"
      exit 0
    fi
  done
fi

upload release/bundle.tgz upload-bundle "$sha"
for line in "${todo[@]}"; do
  upload "release/${line% *}.jar" upload-jar "${line% *}" "$sha"
done

# 相邻、模式一样的服务合成一批，每一项是"<服务,服务...> <模式>"
batches=()
for line in "${todo[@]}"; do
  s=${line% *}
  mode=${line#* }
  last=$((${#batches[@]} - 1))
  if ((last >= 0)) && [[ ${batches[last]#* } == "$mode" ]]; then
    batches[last]="${batches[last]% *},$s $mode"
  else
    batches+=("$s $mode")
  fi
done

for batch in "${batches[@]}"; do
  list=${batch% *}
  mode=${batch#* }
  echo "== 部署 $list（$mode）"
  remote deploy "$list" "$sha" "$mode" "$GITHUB_RUN_ID-$GITHUB_RUN_ATTEMPT"
  deadline=$((SECONDS + DEPLOY_TIMEOUT))
  while :; do
    sleep 10
    out=$(remote deploy-status "$list" "$sha")
    case ${out%%$'\n'*} in
      ok)
        echo "$out"
        break
        ;;
      failed | none)
        echo "$out"
        echo "$list 部署失败" >&2
        exit 1
        ;;
    esac
    if ((SECONDS > deadline)); then
      echo "$out"
      echo "等 $list 部署超过 ${DEPLOY_TIMEOUT}s" >&2
      exit 1
    fi
  done
done
