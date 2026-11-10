-- 2026-11-10：执行记录存完整结果（原来 detail 是 VARCHAR(512)，MCP 查询结果放不下）
SET @has := (SELECT COUNT(*) FROM information_schema.COLUMNS
             WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'tb_task_run' AND COLUMN_NAME = 'detail');
SET @sql := IF(@has > 0,
  'ALTER TABLE tb_task_run MODIFY COLUMN detail TEXT NULL COMMENT ''成功摘要或失败原因（MCP 查询结果可能是多行全文）''',
  'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
