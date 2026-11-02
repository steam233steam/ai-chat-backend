-- MindButler P1：长期记忆 + 口语化任务（v3 施工版）
-- 3 张表：
--   tb_user_memory      结构化画像（固定 schema + status 生命周期 + expires_at 过期）
--   tb_user_task        任务/计划（口语化解析后落库，recurrence 重复）
--   tb_pending_action   待确认动作（AI 提案、用户确认才生效；确认态独立成状态机）
-- 与 docs/schema.sql 现有列宽对齐：user_id 统一 VARCHAR(32)。
SET NAMES utf8mb4;
USE aichat;

-- 用户长期记忆（只存最终结果；待确认在 tb_pending_action）
CREATE TABLE IF NOT EXISTS tb_user_memory (
  id                BIGINT       NOT NULL AUTO_INCREMENT,
  user_id           VARCHAR(32)  NOT NULL COMMENT '用户 uid，与 tb_user.uid 对齐',
  mem_key           VARCHAR(32)  NOT NULL COMMENT 'P1: style|goal|schedule|relation；应用层枚举校验',
  content           VARCHAR(512) NOT NULL COMMENT '记忆内容，应用层限制 256 字符',
  mem_type          ENUM('stable','dynamic') NOT NULL DEFAULT 'stable' COMMENT 'stable 长存；dynamic 会过期',
  status            ENUM('active','superseded','deleted') NOT NULL DEFAULT 'active',
  confidence        DECIMAL(3,2) NOT NULL DEFAULT 1.00 COMMENT '提取置信度，仅排序/审计，不决定生效',
  source            ENUM('manual','ai') NOT NULL DEFAULT 'manual',
  source_message_id BIGINT       NULL COMMENT '来源聊天消息 id',
  replaces_id       BIGINT       NULL COMMENT '替换的旧记忆 id（旧条置 superseded，可追溯）',
  expires_at        DATETIME     NULL COMMENT 'dynamic 过期时间；stable 为 NULL',
  version           INT          NOT NULL DEFAULT 0 COMMENT '乐观锁',
  create_time       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_memory_inject (user_id, status, mem_type, update_time),
  KEY idx_mem_key (user_id, mem_key, status),
  KEY idx_memory_expire (user_id, expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户长期记忆（结构化画像）';

-- 用户任务/计划（口语化解析后落库）
CREATE TABLE IF NOT EXISTS tb_user_task (
  id                BIGINT       NOT NULL AUTO_INCREMENT,
  user_id           VARCHAR(32)  NOT NULL,
  title             VARCHAR(128) NOT NULL,
  due_at            DATETIME     NULL COMMENT 'P1 固定 Asia/Shanghai；无截止时间可为 NULL',
  timezone          VARCHAR(40)  NOT NULL DEFAULT 'Asia/Shanghai' COMMENT 'P1 只允许 Asia/Shanghai',
  recurrence        ENUM('once','daily','weekly','monthly') NOT NULL DEFAULT 'once' COMMENT 'P1 只开放 once/daily/weekly；monthly 预留 P1.5',
  recurrence_rule   VARCHAR(128) NULL COMMENT 'weekly: BYDAY=MO；monthly P1.5: BYMONTHDAY=31',
  status            ENUM('pending','done','cancelled') NOT NULL DEFAULT 'pending',
  deleted_at        DATETIME     NULL COMMENT '软删；非 NULL 表示已删除',
  last_notified_at  DATETIME     NULL COMMENT 'P3 推送用；P1 GET /due 不回写',
  completed_at      DATETIME     NULL COMMENT 'once: 完成时间；recurring: 上一次完成时间',
  raw_input         VARCHAR(512) NULL COMMENT '触发句截断，最多 512 字符',
  source_message_id BIGINT       NULL,
  version           INT          NOT NULL DEFAULT 0,
  create_time       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_task_due (user_id, status, due_at),
  KEY idx_task_user (user_id, status, update_time),
  KEY idx_task_remind (status, due_at, last_notified_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户任务/计划';

-- 待确认动作（AI 提案，用户确认后才写进 memory/task；确认态独立状态机）
CREATE TABLE IF NOT EXISTS tb_pending_action (
  id                BIGINT       NOT NULL AUTO_INCREMENT,
  user_id           VARCHAR(32)  NOT NULL,
  session_id        VARCHAR(64)  NOT NULL,
  action_type       ENUM('memory','task','mixed') NOT NULL,
  payload           JSON         NOT NULL COMMENT '提案内容（memories + tasks），限制 16KB',
  status            ENUM('pending','confirmed','rejected','expired') NOT NULL DEFAULT 'pending',
  expires_at        DATETIME     NOT NULL,
  source_message_id BIGINT       NULL,
  pending_slot      VARCHAR(140) GENERATED ALWAYS AS (
                      CASE WHEN status = 'pending' THEN CONCAT(user_id, ':', session_id) ELSE NULL END
                    ) STORED COMMENT '保证同一 user+session 只有一个 pending；非 pending 为 NULL',
  create_time       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_pending_slot (pending_slot),
  KEY idx_pending_current (user_id, session_id, status, expires_at),
  KEY idx_pending_expire (status, expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='待用户确认的记忆/任务提案';
