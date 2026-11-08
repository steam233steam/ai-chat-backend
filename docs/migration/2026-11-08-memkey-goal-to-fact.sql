-- 记忆维度 goal -> fact：语义从"近期目标"改为"关于用户的陈述性事实"
-- 可重复执行；已迁移过的库再次执行无副作用
UPDATE tb_user_memory SET mem_key = 'fact' WHERE mem_key = 'goal';