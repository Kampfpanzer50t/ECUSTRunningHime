param([string]$Endpoint, [int]$Port = 8765)
$ErrorActionPreference = 'Stop'
$runnerPath = Join-Path $PSScriptRoot 'runner.cjs'
$dataPath = Join-Path $PSScriptRoot 'private'
$nodeCommand = Get-Command node -ErrorAction SilentlyContinue
if (-not $nodeCommand) { throw 'Install Node.js 22 LTS first: https://nodejs.org/' }
if ($Endpoint) {
    & $nodeCommand.Source $runnerPath init --data $dataPath --endpoint $Endpoint
    if ($LASTEXITCODE -ne 0) { throw 'Pairing initialization failed. Existing data was preserved.' }
}
& $nodeCommand.Source $runnerPath serve --data $dataPath --port $Port
if ($LASTEXITCODE -ne 0) { throw 'Executor stopped. Check the data directory and port.' }
