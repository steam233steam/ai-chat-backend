-- 多模型切换：记录每条消息实际使用的模型
-- 历史数据 model 为 NULL，表示该消息产生于 model 列上线之前。
USE aichat;

ALTER TABLE tb_chat_message
  ADD COLUMN model VARCHAR(64) NULL
  COMMENT '本轮使用的模型名' AFTER content;
