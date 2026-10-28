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

-- 会话元数据：MySQL 为主存储，Redis 只做缓存
CREATE TABLE IF NOT EXISTS tb_chat_session (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
  user_id     VARCHAR(32) NOT NULL COMMENT '用户 uid',
  session_id  VARCHAR(64) NOT NULL COMMENT '会话标识',
  title       VARCHAR(100) DEFAULT NULL COMMENT '会话标题',
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
              ON UPDATE CURRENT_TIMESTAMP COMMENT '最近更新时间',
  UNIQUE KEY uk_user_session (user_id, session_id),
  KEY idx_user_update (user_id, update_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 聊天消息：一条消息一行，id 保证稳定排序（不依赖时间精度）
CREATE TABLE IF NOT EXISTS tb_chat_message (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
  user_id     VARCHAR(32) NOT NULL COMMENT '用户 uid',
  session_id  VARCHAR(64) NOT NULL COMMENT '所属会话',
  role        VARCHAR(16) NOT NULL COMMENT 'user/assistant/system',
  content     TEXT NOT NULL COMMENT '消息内容',
  model       VARCHAR(64) COMMENT '本轮使用的模型名（多模型切换后每条消息记录实际走的是哪个模型）',
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '消息时间',
  KEY idx_user_session (user_id, session_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 已有库升级：见 docs/migration/2026-10-28-add-chat-message-model.sql
