-- MindButler P1.5：任务栏挂聊天界面侧边（Trae 式 UI）
-- 任务表加 created_from_session 字段（溯源用，记录任务从哪个会话发起）。
-- 可见性仍全账号（不按 session 过滤）：任务栏在聊天界面侧边展示当前用户全部任务。
SET NAMES utf8mb4;
USE aichat;

ALTER TABLE tb_user_task
  ADD COLUMN created_from_session VARCHAR(64) NULL COMMENT 'P1.5：任务从哪个会话发起（溯源用，不影响可见性）' AFTER source_message_id;
