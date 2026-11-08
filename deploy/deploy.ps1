# MindButler 部署脚本（本地 Windows 执行，推到服务器）
#
# 用法：
#   .\deploy.ps1                      # 用下面的默认值
#   .\deploy.ps1 -Host 1.2.3.4 -User root
#
# 前置：本机 ssh 已能免密登录服务器（公钥已加入服务器 ~/.ssh/authorized_keys）。
# 服务器上需要：nginx、java 17（或更高）、MySQL、Redis。

param(
    [string]$Host = "124.220.107.165",
    [string]$User = "root",
    [string]$RemoteRoot = "/opt/mindbutler",
    [string]$ServiceName = "mindbutler"
)

$ErrorActionPreference = "Stop"
$repo = Split-Path -Parent $PSScriptRoot          # 后端仓库根
$front = "D:\桌面\Vue\ai-chat-project"            # 前端仓库

Write-Host "== 1/5 构建后端 jar" -ForegroundColor Cyan
Push-Location $repo
cmd /c "mvnw.cmd -o -DskipTests package" | Out-Null
if ($LASTEXITCODE -ne 0) { throw "后端打包失败" }
$jar = Get-ChildItem "$repo\target\*.jar" | Sort-Object LastWriteTime | Select-Object -Last 1
Pop-Location

Write-Host "== 2/5 构建前端 dist" -ForegroundColor Cyan
Push-Location $front
if (-not (Test-Path "$front\node_modules")) { cmd /c "npm.cmd ci" | Out-Null }
cmd /c "npm.cmd run build" | Out-Null
if ($LASTEXITCODE -ne 0) { throw "前端构建失败" }
Pop-Location

$target = "$User@$Host"
Write-Host "== 3/5 上传 jar 与 dist -> $target`:$RemoteRoot" -ForegroundColor Cyan
ssh $target "mkdir -p $RemoteRoot/dist $RemoteRoot/logs"
scp $jar.FullName "$target`:$RemoteRoot/app.jar"
# 先传到临时目录，再整目录替换，避免删到一半网站 404
scp -r "$front\dist\*" "$target`:$RemoteRoot/dist.new/"
ssh $target "rm -rf $RemoteRoot/dist.old && mv $RemoteRoot/dist $RemoteRoot/dist.old 2>/dev/null; mv $RemoteRoot/dist.new $RemoteRoot/dist"

Write-Host "== 4/5 重启后端" -ForegroundColor Cyan
ssh $target "systemctl restart $ServiceName && sleep 3 && systemctl is-active $ServiceName"

Write-Host "== 5/5 重载 nginx 并自检" -ForegroundColor Cyan
ssh $target "nginx -t && nginx -s reload"
ssh $target "curl -s -o /dev/null -w 'index=%{http_code} ' http://127.0.0.1/; curl -s -o /dev/null -w 'api=%{http_code}`n' http://127.0.0.1/api/chat/models"

Write-Host "完成：http://$Host" -ForegroundColor Green
