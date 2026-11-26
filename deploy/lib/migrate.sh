# 数据库迁移：sql/migrations 下按文件名排序，记录表 schema_migration 里没有的逐个跑。
# 一个文件一个事务，版本号在同一个事务里记上；开头注释里有 "-- no-transaction" 的不包事务（CREATE INDEX CONCURRENTLY 用）。
# 任何一个失败就停，后面的不跑。库在 PG 容器里，账号读 .env，密码走环境变量不上命令行。
#
# 迁移跑完到旧实例退出之间，旧代码一直在用库（agent 的旧实例收尾最长 35 分钟）。所以旧代码会受影响的改动
# （删表、删列、改名、改列类型、加非空、加约束、唯一索引、清表）要分两次发：先发不再用它的代码，旧实例都退了之后，
# 下一次再发这条迁移。脚本这样拦：
#   - 文件里有这类语句，开头注释里就要有一行 "-- old-code-safe: <为什么>"，表示确认过现在在跑的版本都不受影响；没有就不发
#   - 有这一行的，先等三个服务上一次交接留下的旧实例都清空，再跑

psql_in_pg() {
  PGPASSWORD=$PG_PASSWORD docker exec -i -e PGPASSWORD "$PG_CONTAINER" \
    psql -X -q -v ON_ERROR_STOP=1 -U "$PG_USER" -d "$PG_DB" "$@"
}

# 文件开头的注释行（第一条语句之前的）
migration_header() {
  awk '/^[[:space:]]*$/ { next } /^[[:space:]]*--/ { print; next } { exit }' "$1"
}

# 文件里旧代码会受影响的语句，一条一行；没有就什么都不输出。注释里的不算，
# 放宽限制的 DROP（DROP INDEX / DROP CONSTRAINT / DROP NOT NULL）不算，带 DEFAULT 的加非空列不算
migration_risky() {
  local stmt rest
  sed 's/--.*$//' "$1" | tr '\r\n' '  ' | tr ';' '\n' | while IFS= read -r stmt || [[ -n $stmt ]]; do
    rest=$(sed -E 's/DROP[[:space:]]+(INDEX|CONSTRAINT|NOT[[:space:]]+NULL)//Ig' <<<"$stmt")
    if grep -qiE '\b(DROP|RENAME|TRUNCATE)\b|\bSET[[:space:]]+NOT[[:space:]]+NULL\b|\bALTER[[:space:]]+(COLUMN[[:space:]]+)?[A-Za-z0-9_"]+[[:space:]]+(SET[[:space:]]+DATA[[:space:]]+)?TYPE\b|\bADD[[:space:]]+(CONSTRAINT|UNIQUE|PRIMARY|FOREIGN|CHECK)\b|\bCREATE[[:space:]]+UNIQUE[[:space:]]+INDEX\b' <<<"$rest" \
      || { grep -qiE '\bADD\b.*\bNOT[[:space:]]+NULL\b' <<<"$rest" && ! grep -qiE '\bDEFAULT\b' <<<"$rest"; }; then
      sed -E 's/^[[:space:]]+//; s/[[:space:]]+/ /g' <<<"$stmt"
    fi
  done
}

# 这个迁移文件能不能发：有旧代码会受影响的语句、开头又没有 "-- old-code-safe: <为什么>"，就说明原因、返回非 0
migration_guard() {
  local risky
  risky=$(migration_risky "$1")
  [[ -n $risky ]] || return 0
  if migration_header "$1" | grep -qE '^[[:space:]]*--[[:space:]]*old-code-safe:[[:space:]]*[^[:space:]]'; then
    return 0
  fi
  {
    echo "迁移 $(basename "$1") 里有旧代码会受影响的改动："
    sed 's/^/    /' <<<"$risky"
    echo "迁移跑完后旧实例还要干活（agent 收尾最长 35 分钟），这类改动要分两次发："
    echo "先发不再用它的代码，等旧实例都退了，下一次再发这条迁移。"
    echo "确认过现在在跑的版本都不受影响，就在文件开头加一行："
    echo "    -- old-code-safe: <为什么不受影响>"
  } >&2
  return 1
}

migrate() {
  local dir=$1 exists applied f v risky=0
  local pending=()
  exists=$(psql_in_pg -At -c "SELECT to_regclass('public.schema_migration') IS NOT NULL") || die "连不上数据库"
  applied=
  if [[ $exists == t ]]; then
    applied=$(psql_in_pg -At -c "SELECT version FROM schema_migration") || die "读迁移记录失败"
  fi
  # 先把没跑过的都看一遍，有不能发的一个都不跑
  for f in "$dir"/*.sql; do
    [[ -e $f ]] || continue
    v=$(basename "$f" .sql)
    [[ $v =~ ^[0-9]{4}_[A-Za-z0-9_]+$ ]] || die "迁移文件名要是 NNNN_简述.sql：$v"
    grep -qxF "$v" <<<"$applied" && continue
    migration_guard "$f" || die "迁移 $v 不能发，原因见上面"
    [[ -z $(migration_risky "$f") ]] || risky=1
    pending+=("$f")
  done
  if ((risky)); then
    log "这次有旧代码会受影响的迁移，先等各服务上一次交接留下的旧实例清空"
    wait_old_instances_drained || die "还有旧实例手上的活没做完，这次不跑迁移，等它退了再发"
  fi
  for f in "${pending[@]}"; do
    v=$(basename "$f" .sql)
    log "迁移 $v"
    if migration_header "$f" | grep -qE '^[[:space:]]*--[[:space:]]*no-transaction'; then
      psql_in_pg <"$f" || die "迁移 $v 出错"
      psql_in_pg -c "INSERT INTO schema_migration (version) VALUES ('$v')" || die "记迁移 $v 出错"
    else
      { cat "$f"; printf "\nINSERT INTO schema_migration (version) VALUES ('%s');\n" "$v"; } \
        | psql_in_pg --single-transaction || die "迁移 $v 出错，已回滚"
    fi
  done
}
