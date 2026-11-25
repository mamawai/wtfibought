# 数据库迁移：sql/migrations 下按文件名排序，记录表 schema_migration 里没有的逐个跑。
# 一个文件一个事务，版本号在同一个事务里记上；第一行是 "-- no-transaction" 的不包事务（CREATE INDEX CONCURRENTLY 用）。
# 任何一个失败就停，后面的不跑。库在 PG 容器里，账号读 .env，密码走环境变量不上命令行

psql_in_pg() {
  PGPASSWORD=$PG_PASSWORD docker exec -i -e PGPASSWORD "$PG_CONTAINER" \
    psql -X -q -v ON_ERROR_STOP=1 -U "$PG_USER" -d "$PG_DB" "$@"
}

migrate() {
  local dir=$1 exists applied f v
  exists=$(psql_in_pg -At -c "SELECT to_regclass('public.schema_migration') IS NOT NULL") || die "连不上数据库"
  applied=
  if [[ $exists == t ]]; then
    applied=$(psql_in_pg -At -c "SELECT version FROM schema_migration") || die "读迁移记录失败"
  fi
  for f in "$dir"/*.sql; do
    [[ -e $f ]] || continue
    v=$(basename "$f" .sql)
    [[ $v =~ ^[0-9]{4}_[A-Za-z0-9_]+$ ]] || die "迁移文件名要是 NNNN_简述.sql：$v"
    grep -qxF "$v" <<<"$applied" && continue
    log "迁移 $v"
    if [[ $(head -n 1 "$f") == '-- no-transaction'* ]]; then
      psql_in_pg <"$f" || die "迁移 $v 出错"
      psql_in_pg -c "INSERT INTO schema_migration (version) VALUES ('$v')" || die "记迁移 $v 出错"
    else
      { cat "$f"; printf "\nINSERT INTO schema_migration (version) VALUES ('%s');\n" "$v"; } \
        | psql_in_pg --single-transaction || die "迁移 $v 出错，已回滚"
    fi
  done
}
