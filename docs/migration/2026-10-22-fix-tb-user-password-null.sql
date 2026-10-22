-- 旧库 tb_user.password 为 NOT NULL 时，新用户验证码登录会报：
-- Column 'password' cannot be null
-- 该迁移只放宽可空约束，不修改已有数据。
USE aichat;

ALTER TABLE tb_user
  MODIFY COLUMN password VARCHAR(255) NULL
  COMMENT '密码（BCrypt 加密，首次为 NULL）';
