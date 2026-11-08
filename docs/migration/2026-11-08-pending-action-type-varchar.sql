-- 记忆变更提案（聊天里改/删记忆）
--
-- tb_pending_action.action_type 原来是 ENUM('memory','task','mixed')，
-- 新增 memory_change 会被 MySQL 截断（Data truncated for column 'action_type'）。
-- 改成 VARCHAR 后以后再加动作类型不用再改表结构。
--
-- 可重复执行：列已经是 VARCHAR 时 MODIFY 到同样定义不会有副作用。

ALTER TABLE tb_pending_action
    MODIFY COLUMN action_type VARCHAR(32) NOT NULL
        COMMENT '提案类型：memory/task/mixed/memory_change';
