-- AI Chat 数据库初始化脚本（MySQL 8.0）
-- 用法：mysql -h 127.0.0.1 -P 3306 -uroot -p < docs/schema.sql

CREATE DATABASE IF NOT EXISTS aichat
  DEFAULT CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;

USE aichat;

CREATE TABLE IF NOT EXISTS tb_user (
  uid VARCHAR(32) PRIMARY KEY COMMENT '用户唯一标识',
  name VARCHAR(64) COMMENT '用户昵称',
  phone VARCHAR(20) UNIQUE COMMENT '手机号',
  password VARCHAR(128) COMMENT '密码（BCrypt 加密，首次为 NULL）',
  create_time DATETIME COMMENT '创建时间',
  update_time DATETIME COMMENT '更新时间'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
