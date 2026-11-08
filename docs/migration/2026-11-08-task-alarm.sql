-- 任务"闹钟"内核：动作类型 + 执行记录
-- 可重复执行；列已存在时跳过 ALTER

SET @has_action_type = (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'tb_user_task' AND COLUMN_NAME = 'action_type');
SET @ddl = IF(@has_action_type = 0,
  'ALTER TABLE tb_user_task ADD COLUMN action_type VARCHAR(16) NOT NULL DEFAULT ''notify'' COMMENT ''到点触发什么动作：notify|webhook'' AFTER recurrence_rule',
  'SELECT 1');
PREPARE s FROM @ddl;
EXECUTE s;
DEALLOCATE PREPARE s;

SET @has_action_config = (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'tb_user_task' AND COLUMN_NAME = 'action_config');
SET @ddl = IF(@has_action_config = 0,
  'ALTER TABLE tb_user_task ADD COLUMN action_config VARCHAR(1024) NULL COMMENT ''动作参数 JSON，如 {"url":"..."}'' AFTER action_type',
  'SELECT 1');
PREPARE s FROM @ddl;
EXECUTE s;
DEALLOCATE PREPARE s;

CREATE TABLE IF NOT EXISTS tb_task_run (
  id           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  task_id      BIGINT       NOT NULL COMMENT '任务 id',
  user_id      VARCHAR(64)  NOT NULL COMMENT '归属用户',
  action_type  VARCHAR(16)  NOT NULL COMMENT '触发时执行的动作',
  trigger_type VARCHAR(16)  NOT NULL DEFAULT 'scheduled' COMMENT 'scheduled|manual',
  status       VARCHAR(16)  NOT NULL COMMENT 'success|failed',
  detail       VARCHAR(512) NULL COMMENT '成功摘要或失败原因',
  started_at   DATETIME     NOT NULL COMMENT '开始时间',
  finished_at  DATETIME     NULL COMMENT '结束时间',
  create_time  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '写入时间',
  PRIMARY KEY (id),
  KEY idx_task_run_task (task_id, started_at),
  KEY idx_task_run_user (user_id, started_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '任务执行记录（闹钟响过几次、成没成）';
