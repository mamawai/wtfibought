-- 迁移记录表：部署脚本只跑这张表里没有的迁移，跑完把版本号记进来
CREATE TABLE IF NOT EXISTS schema_migration (
    version    VARCHAR(64) PRIMARY KEY,
    applied_at TIMESTAMP   NOT NULL DEFAULT now()
);
COMMENT ON TABLE  schema_migration IS '跑过的数据库迁移，一个文件一行';
COMMENT ON COLUMN schema_migration.version IS '迁移文件名去掉 .sql，如 0001_schema_migration';
