param([int]$BackendPort = 8080, [int]$FrontendPort = 5173)
$ErrorActionPreference = 'Stop'
if (-not (Get-Command node -ErrorAction SilentlyContinue)) { throw 'Install Node.js 22.12+ first.' }
& node "$PSScriptRoot/dev.mjs" start history --backend-port $BackendPort --frontend-port $FrontendPort
exit $LASTEXITCODE
