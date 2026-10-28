-- 会话锁模型：会话首条消息锁定模型，之后切换模型需新建会话
-- 存量会话 model 为 NULL，升级后第一次发消息时才会锁定，不影响历史数据读取。
-- 必须显式声明连接字符集，否则 COMMENT 里的中文会按 latin1/gbk 解读成乱码。
SET NAMES utf8mb4;
USE aichat;

ALTER TABLE tb_chat_session
  ADD COLUMN model VARCHAR(64) NULL
  COMMENT '会话锁定的模型（首条消息写入后固定，切换模型需新建会话）' AFTER title;
