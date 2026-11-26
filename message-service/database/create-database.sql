-- 使用数据库管理员执行一次，再给 MESSAGE_DB_USER 授予该库的必要权限。
CREATE DATABASE IF NOT EXISTS MESSAGESERVICE CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
-- 消息服务启动时通过 src/main/resources/schema.sql 创建表，不会 DROP 表。
