param([string]$Python)
$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot
if (-not $Python) {
    $bundledPython = Join-Path $env:USERPROFILE '.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe'
    if (Test-Path -LiteralPath $bundledPython) { $Python = $bundledPython }
    else { $Python = (Get-Command python -ErrorAction Stop).Source }
}
& $Python -m venv .venv
if ($LASTEXITCODE -ne 0) { throw '创建 Python 环境失败；请用 -Python 指定 Python 3.12 或更新版本。' }
$venvPython = Join-Path $PSScriptRoot '.venv\Scripts\python.exe'
& $venvPython -m pip install -r (Join-Path $PSScriptRoot 'requirements-capture.txt')
if ($LASTEXITCODE -ne 0) { throw '安装抓包依赖失败。' }
Write-Host '环境已准备好。提交和预览工具 huati.py 本身只依赖 Python 标准库。'
