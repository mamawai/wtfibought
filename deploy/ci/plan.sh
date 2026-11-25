#!/usr/bin/env bash
# CI 用：算这次要发哪些服务，按 feed → sim → agent 的顺序一行一个输出。在仓库根目录跑，要完整历史
#   plan.sh <目标哈希> <点名的服务> <feed 已部署哈希> <sim 已部署哈希> <agent 已部署哈希>
# 点名的服务逗号分隔，有就只发它们；空就按改动算，拿服务器上实际部署的哈希和目标比：
#   wiib-feed/**                                            → feed
#   wiib-sim/**、wiib-common/**、根 pom.xml                  → sim
#   wiib-agent/**、wiib-quant/**、wiib-common/**、根 pom.xml → agent
#   各服务的 Dockerfile 改动不单独触发；common 改了不带 feed，范围里有提交写了 [deploy feed] 才带
#   目标提交写了 [skip deploy] 就都不发；没部署过、或部署的哈希不在历史里，当作全变了
set -euo pipefail

target=$1
named=$2
declare -A deployed=([feed]=$3 [sim]=$4 [agent]=$5)
order=(feed sim agent)

if [[ -n $named ]]; then
  IFS=',' read -r -a picks <<<"${named//[[:space:]]/}"
  for p in "${picks[@]}"; do
    [[ $p =~ ^(feed|sim|agent)$ ]] || { echo "点名的服务只能是 feed / sim / agent：$p" >&2; exit 2; }
  done
  for s in "${order[@]}"; do
    for p in "${picks[@]}"; do
      if [[ $p == "$s" ]]; then
        echo "$s"
        break
      fi
    done
  done
  exit 0
fi

if grep -qF '[skip deploy]' <<<"$(git log -1 --format=%B "$target")"; then
  exit 0
fi

for s in "${order[@]}"; do
  from=${deployed[$s]}
  [[ $from == "$target" ]] && continue
  if [[ -z $from ]] || ! git cat-file -e "$from^{commit}" 2>/dev/null; then
    echo "$s"
    continue
  fi
  changed=$(git diff --name-only "$from" "$target" | grep -vE '^wiib-[a-z]+/Dockerfile$' || true)
  case $s in
    feed) pattern='^wiib-feed/' ;;
    sim) pattern='^(wiib-sim/|wiib-common/|pom\.xml$)' ;;
    agent) pattern='^(wiib-agent/|wiib-quant/|wiib-common/|pom\.xml$)' ;;
  esac
  if grep -qE "$pattern" <<<"$changed"; then
    echo "$s"
  elif [[ $s == feed ]] && grep -qF '[deploy feed]' <<<"$(git log --format=%B "$from..$target")"; then
    echo "$s"
  fi
done
