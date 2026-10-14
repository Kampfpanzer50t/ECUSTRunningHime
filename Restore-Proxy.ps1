$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'Proxy-Settings.ps1')
$backupPath = Join-Path $PSScriptRoot 'private\proxy-backup.json'
if (-not (Test-Path -LiteralPath $backupPath)) { throw '没有找到代理备份文件。' }
Restore-HuatiProxy -Path $backupPath
Remove-Item -LiteralPath (Join-Path $PSScriptRoot 'private\proxy-restore-pending') -ErrorAction SilentlyContinue
Write-Host '已恢复抓包前保存的系统代理设置。'
