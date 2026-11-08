# 部署

三个文件：

| 文件 | 用途 |
|---|---|
| `deploy.ps1` | 本地执行：打包 → scp → 重启后端 → reload nginx → 自检 |
| `mindbutler.service` | 服务器 systemd 单元（jar 常驻、开机自启、日志追加） |
| `nginx-mindbutler.conf` | 服务器 nginx：静态托管 + `/api` 反代 + SSE 三件套 |

## 服务器首次准备（一次性）

```bash
mkdir -p /opt/mindbutler/{dist,logs}

# 1) 装环境（Ubuntu/Debian）
apt update && apt install -y openjdk-17-jre nginx mysql-server redis-server

# 2) 建库导表
mysql -uroot -p -e "CREATE DATABASE IF NOT EXISTS aichat DEFAULT CHARSET utf8mb4"
mysql -uroot -p aichat < /opt/mindbutler/schema.sql     # 从仓库 docs/schema.sql 拷过来

# 3) 生产配置（不进 git，权限 600）
cat > /opt/mindbutler/app.env <<'EOF'
SPRING_DATASOURCE_URL=jdbc:mysql://127.0.0.1:3306/aichat?useSSL=false&serverTimezone=Asia/Shanghai
SPRING_DATASOURCE_USERNAME=aichat
SPRING_DATASOURCE_PASSWORD=改成你的密码
SPRING_DATA_REDIS_HOST=127.0.0.1
SPRING_AI_OPENAI_API_KEY=改成你的 key
SPRING_AI_OPENAI_BASE_URL=https://api.deepseek.com
APP_CORS_ALLOWED_ORIGINS=http://改成你的域名或IP
APP_CHAT_DAILY_LIMIT=200
EOF
chmod 600 /opt/mindbutler/app.env

# 4) systemd
cp mindbutler.service /etc/systemd/system/
systemctl daemon-reload && systemctl enable --now mindbutler

# 5) nginx
cp nginx-mindbutler.conf /etc/nginx/conf.d/mindbutler.conf
rm -f /etc/nginx/sites-enabled/default        # 避免默认站点抢占 80
nginx -t && systemctl reload nginx
```

## 日常发布

```powershell
cd D:\maven-project\aichat
.\deploy\deploy.ps1
```

脚本最后会打印两个自检码：`index=200 api=401`（401 说明反代通了、只是没登录）。

## 排查

| 现象 | 看哪里 |
|---|---|
| 502 | `systemctl status mindbutler`、`tail -50 /opt/mindbutler/logs/app.log` |
| 页面 404 / 白屏 | `ls /opt/mindbutler/dist`（是不是空的）、`nginx -t` |
| 消息不逐字出现 | nginx 里 `proxy_buffering off` 是否被别的 include 覆盖 |
| 启动即退出 | 看日志里的 `app.mindbutler.task.zone 只允许 Asia/Shanghai` 这类 fail-fast 提示 |
| 数据库连不上 | `mysql -h127.0.0.1 -uaichat -p`；确认 `app.env` 已被 `EnvironmentFile` 读到 |

## 现有线上环境的实际情况（2026-11-08 核对）

上面是"新建一台机器"的模板；已经在跑的这台（`124.220.107.165`，Ubuntu 24.04）用的是另一套路径，别按模板改：

| 项 | 实际值 |
|---|---|
| 用户 / 地址 | `ubuntu@124.220.107.165`，`http://124.220.107.165` |
| jar | `/home/ubuntu/aichat.jar` |
| 前端 | `/home/ubuntu/frontend/dist` |
| 服务 | `aichat.service`（`WorkingDirectory=/home/ubuntu`，JVM `-Xms256m -Xmx384m`） |
| 环境变量 | `/etc/aichat.env`（600，含 DeepSeek key；**不要覆盖**） |
| nginx | `/etc/nginx/sites-available/aichat`，`/api/` 反代到 `127.0.0.1:8080` |
| 迁移脚本 | 上传到 `/home/ubuntu/database/` 再 `sudo mysql aichat < xxx.sql` |
| 备份 | `/home/ubuntu/backups/<时间戳>/`（jar + dist + 库 + nginx + unit） |

**MCP 工具有额外依赖**（jar 里不含）：服务器要装 `nodejs`，并把仓库里的 `mcp-servers/info-server.mjs` 放到 `/home/ubuntu/mcp-servers/info-server.mjs`（相对路径来自 `WorkingDirectory`）。没装 node 时 MCP 起不来但**不影响聊天**，只在第一次对话的日志里打一条 `MCP 服务 info 启动失败`。

本次发布用的客户端脚本（不在此仓库，个人工具）：`D:\桌面\ai专用开发地\aichat-deploy\deploy.py`，参数 `--check / --all / --backend-only / --frontend-only / --migration`。
