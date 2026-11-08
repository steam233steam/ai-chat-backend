-- 2026-11-08 记忆时间衰减：记录「上次被注入」与「被注入次数」
--
-- 为什么不在表里存一个 weight 列：
--   衰减是时间的连续函数，存下来就一定是「上次刷新那一刻」的旧值，
--   还得配一个定时任务天天重算。改成查询时用 last_used_at 现算，
--   表里只留事实（什么时候用过、用过几次），权重永远是最新的。
--
-- 兼容性：可重复执行（先查 information_schema 再决定要不要加）。

SET @db := DATABASE();

SET @sql := (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE tb_user_memory ADD COLUMN last_used_at DATETIME NULL COMMENT ''上次被注入进提示词的时间''',
    'DO 0')
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'tb_user_memory' AND COLUMN_NAME = 'last_used_at'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE tb_user_memory ADD COLUMN use_count INT NOT NULL DEFAULT 0 COMMENT ''被注入进提示词的次数''',
    'DO 0')
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'tb_user_memory' AND COLUMN_NAME = 'use_count'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
