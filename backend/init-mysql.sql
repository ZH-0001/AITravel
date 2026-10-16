-- 在 MySQL 8 中执行；仅在数据库不存在时创建，不删除现有数据。
CREATE DATABASE IF NOT EXISTS aitravel
  CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
-- 表结构由 src/main/resources/schema.sql 或应用首次启动初始化。
