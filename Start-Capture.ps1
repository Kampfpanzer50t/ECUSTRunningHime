param(
    [ValidateSet('proxy', 'local')][string]$Mode = 'proxy',
    [ValidateRange(1024, 65535)][int]$Port = 8080,
    [string]$TargetHost = 'run.ecust.edu.cn',
    [string]$UpstreamProxy,
    [switch]$UseSystemProxy,
    [switch]$KeepSecrets,
    [switch]$SaveSessionAuth
)
$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot
$mitmdump = Join-Path $PSScriptRoot '.venv\Scripts\mitmdump.exe'
if (-not (Test-Path -LiteralPath $mitmdump)) { throw '请先运行 .\Setup.ps1。' }
if ($TargetHost -notmatch '^[a-zA-Z0-9.-]+$') { throw 'TargetHost 必须是主机名。' }
if ($Mode -eq 'local' -and ($UseSystemProxy -or $UpstreamProxy)) {
    throw 'local 模式不能与 UseSystemProxy 或 UpstreamProxy 同时使用。'
}
if ($UpstreamProxy -and $UpstreamProxy -notmatch '^https?://[^/]+$') {
    throw 'UpstreamProxy 格式应为 http://127.0.0.1:端口。'
}
$privateDirectory = Join-Path $PSScriptRoot 'private'
New-Item -ItemType Directory -Path $privateDirectory -Force | Out-Null
. (Join-Path $PSScriptRoot 'Proxy-Settings.ps1')
$backupPath = Join-Path $privateDirectory 'proxy-backup.json'
$pendingMarker = Join-Path $privateDirectory 'proxy-restore-pending'
$changed = $false
if ($UseSystemProxy) {
    if (Test-Path -LiteralPath $pendingMarker) {
        throw '上次代理设置尚未确认恢复，请先运行 .\Restore-Proxy.ps1。'
    }
    $settings = Get-ItemProperty -LiteralPath 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Internet Settings'
    if ($settings.AutoConfigURL) {
        throw '当前使用 PAC 自动代理。请手动选择上游代理或使用 local 模式，避免覆盖现有网络配置。'
    }
    # 现有全局代理作为上游保留；协议分开的代理需用户显式指定。
    if ($settings.ProxyEnable -and $settings.ProxyServer -and -not $UpstreamProxy) {
        if ($settings.ProxyServer -match '[;=]' -or $settings.ProxyServer -match '^https?://') {
            throw '现有代理格式需要显式指定 -UpstreamProxy。'
        }
        $UpstreamProxy = 'http://' + $settings.ProxyServer
    }
    if ($UpstreamProxy -match ('^https?://(127\.0\.0\.1|localhost):' + $Port + '$')) {
        throw '上游代理不能指向抓包工具自己的端口。'
    }
    Save-HuatiProxy -Path $backupPath
}
$captureMode = if ($Mode -eq 'local') { 'local:WeChatAppEx.exe' }
               elseif ($UpstreamProxy) { 'upstream:' + $UpstreamProxy }
               else { 'regular' }
$arguments = @(
    '--mode', $captureMode,
    '--listen-host', '127.0.0.1', '--listen-port', "$Port",
    '--allow-hosts', ('^' + [regex]::Escape($TargetHost) + '(:443)?$'),
    '--set', ('confdir=' + (Join-Path $privateDirectory 'mitmproxy')),
    '--set', ('huati_host=' + $TargetHost),
    '--set', ('huati_output=' + (Join-Path $privateDirectory 'capture.jsonl')),
    '--set', ('huati_keep_secrets=' + $KeepSecrets.IsPresent.ToString().ToLowerInvariant()),
    '-s', (Join-Path $PSScriptRoot 'capture_addon.py'),
    '--set', 'flow_detail=0'
)
if ($SaveSessionAuth) {
    $arguments += @('--set', ('huati_auth_output=' + (Join-Path $privateDirectory 'captured-auth.json')))
}
try {
    if ($UseSystemProxy) {
        Set-Content -LiteralPath $pendingMarker -Value 'restore required' -Encoding utf8
        $changed = $true
        $registryPath = 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Internet Settings'
        New-ItemProperty -LiteralPath $registryPath -Name ProxyEnable -Value 1 -PropertyType DWord -Force | Out-Null
        New-ItemProperty -LiteralPath $registryPath -Name ProxyServer -Value "127.0.0.1:$Port" -PropertyType String -Force | Out-Null
        New-ItemProperty -LiteralPath $registryPath -Name ProxyOverride -Value '<local>' -PropertyType String -Force | Out-Null
        Set-HuatiProxyConnection -Flags 3 -Server "127.0.0.1:$Port" -Bypass '<local>' -AutoConfig ''
    }
    Write-Host "抓包模式：$captureMode；输出：private\capture.jsonl"
    Write-Host '只拦截指定域名。HTTPS 解密需先在本机信任 private\mitmproxy 内的代理证书。'
    Write-Host '使用 Ctrl+C 停止；启用系统代理时将恢复原设置。'
    & $mitmdump @arguments
    if ($LASTEXITCODE -notin @(0, 130)) { throw "抓包程序退出，代码 $LASTEXITCODE。" }
}
finally {
    if ($changed) {
        Restore-HuatiProxy -Path $backupPath
        Remove-Item -LiteralPath $pendingMarker -ErrorAction SilentlyContinue
        Write-Host '原系统代理设置已恢复。'
    }
}
