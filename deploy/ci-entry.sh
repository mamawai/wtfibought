#!/usr/bin/env bash
# CI 部署密钥的强制命令：authorized_keys 里这把公钥前面写 command="<项目目录>/deploy/ci-entry.sh"，
# 用这把密钥登上来只能跑这里。读 SSH_ORIGINAL_COMMAND，只认下面五个子命令，参数逐个校验：
#   deployed <服务>                    这个服务现在部署的哈希，没部署过输出空
#   upload-bundle <哈希>               标准输入收 bundle，解到 releases/<哈希>/
#   upload-jar <服务> <哈希>           标准输入收 jar，存成 releases/<哈希>/<服务>.jar
#   deploy <服务列表> <哈希> <模式> <请求号>  用 systemd-run 起部署任务（脱离 ssh 会话），立刻返回；
#                                    服务列表是一个或几个服务，逗号隔开（feed,sim,agent），几个的在一次部署里一起发；
#                                    请求号是 CI 的 <run_id>-<run_attempt>，同一个请求号重复调（ssh 重试）不重起
#   deploy-status <服务列表> <哈希>    第一行 running / ok / failed / none，后面是日志末尾
# 手工装一次（放在 <项目目录>/deploy/ 下，chmod +x），尽量不动；其余脚本随 bundle 来
set -euo pipefail

ROOT=$(cd "$(dirname "$(readlink -f "$0")")/.." && pwd)
RELEASES=$ROOT/releases
STATE=$ROOT/deploy/state

die() { echo "ci-entry: $*" >&2; exit 2; }
check_service() { [[ ${1:-} =~ ^(feed|sim|agent)$ ]] || die "服务名只能是 feed / sim / agent"; }
check_services() { [[ ${1:-} =~ ^(feed|sim|agent)(,(feed|sim|agent))*$ ]] || die "服务只能是 feed / sim / agent，几个用逗号隔开"; }
check_hash() { [[ ${1:-} =~ ^[0-9a-f]{7,40}$ ]] || die "哈希不对"; }
check_mode() { [[ ${1:-} =~ ^(handover|cold)$ ]] || die "模式只能是 handover / cold"; }
check_request() { [[ ${1:-} =~ ^[0-9]{1,20}-[0-9]{1,5}$ ]] || die "请求号不对"; }
check_argc() { [[ $1 -eq $2 ]] || die "参数个数不对"; }

# 部署任务的结果：<名字>.status 里写的（名字是服务列表里的逗号换成 -）；写着 running 但任务已经没了，是脚本被杀没来得及写，算 failed
deploy_state() {
  local dir=$1 name=$2 hash=$3 s
  s=$(cat "$dir/$name.status" 2>/dev/null || echo none)
  if [[ $s == running ]] && ! systemctl is-active --quiet "wiib-deploy-$name-${hash:0:12}"; then
    # 任务可能正好在上面两步之间跑完，再读一次；还是 running 才是被杀了
    s=$(cat "$dir/$name.status" 2>/dev/null || echo none)
    [[ $s != running ]] || s=failed
  fi
  echo "$s"
}

read -r -a argv <<<"${SSH_ORIGINAL_COMMAND:-}"
cmd=${argv[0]:-}
svc=${argv[1]:-}

case $cmd in
  deployed)
    check_argc ${#argv[@]} 2
    check_service "$svc"
    sed -n 's/^current=//p' "$STATE/$svc" 2>/dev/null || true
    ;;

  upload-bundle)
    check_argc ${#argv[@]} 2
    hash=${argv[1]}
    check_hash "$hash"
    mkdir -p "$RELEASES/$hash"
    tar -xzf - -C "$RELEASES/$hash"
    ;;

  upload-jar)
    check_argc ${#argv[@]} 3
    hash=${argv[2]}
    check_service "$svc"
    check_hash "$hash"
    mkdir -p "$RELEASES/$hash"
    cat >"$RELEASES/$hash/$svc.jar.part"
    mv "$RELEASES/$hash/$svc.jar.part" "$RELEASES/$hash/$svc.jar"
    ;;

  deploy)
    check_argc ${#argv[@]} 5
    hash=${argv[2]}
    mode=${argv[3]}
    request=${argv[4]}
    check_services "$svc"
    check_hash "$hash"
    check_mode "$mode"
    check_request "$request"
    dir=$RELEASES/$hash
    name=${svc//,/-}
    [[ -f $dir/deploy/deploy.sh ]] || die "这个哈希的 bundle 还没传"
    for s in ${svc//,/ }; do
      [[ -f $dir/$s.jar ]] || die "这个哈希的 $s.jar 还没传"
    done
    status=$(deploy_state "$dir" "$name" "$hash")
    # 同一个请求号已经起过（ssh 重试）：不再起，回它的状态
    if [[ $(cat "$dir/$name.request" 2>/dev/null || true) == "$request" ]]; then
      echo "$status"
      exit 0
    fi
    [[ $status != running ]] || die "这几个服务和哈希的另一次部署还在跑"
    echo "$request" >"$dir/$name.request"
    echo running >"$dir/$name.status"
    systemd-run --unit="wiib-deploy-$name-${hash:0:12}" --collect --quiet --setenv=HOME="${HOME:-/root}" \
      /bin/bash "$dir/deploy/deploy.sh" "$svc" "$hash" "--$mode" || { echo failed >"$dir/$name.status"; die "起部署任务失败"; }
    echo started
    ;;

  deploy-status)
    check_argc ${#argv[@]} 3
    hash=${argv[2]}
    check_services "$svc"
    check_hash "$hash"
    dir=$RELEASES/$hash
    name=${svc//,/-}
    deploy_state "$dir" "$name" "$hash"
    tail -n 40 "$dir/$name.log" 2>/dev/null || true
    ;;

  *)
    die "不认识的命令"
    ;;
esac
