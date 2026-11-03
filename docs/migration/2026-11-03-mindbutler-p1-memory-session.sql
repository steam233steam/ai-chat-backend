-- MindButler P1.1：记忆会话隔离
-- 给 tb_user_memory 加 session_id 列（VARCHAR(64) NULL）：
--   NULL = 全局画像（跨会话生效，默认）
--   有值 = 仅该会话生效（会话级人设，如"这个会话里你是猫娘"）
-- 存量数据 session_id 全为 NULL → 行为不变（全局），向后兼容。
SET NAMES utf8mb4;
USE aichat;

ALTER TABLE tb_user_memory
  ADD COLUMN session_id VARCHAR(64) NULL COMMENT '会话隔离：NULL=全局画像；有值=仅该会话生效（P1.1）' AFTER user_id,
  ADD KEY idx_memory_session (user_id, session_id, status);
