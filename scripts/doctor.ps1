# No elevation, global configuration changes or installations.
param([ValidateSet('fake','history')][string]$Mode = 'fake', [int]$BackendPort = 8080, [int]$FrontendPort = 5173)
$ErrorActionPreference = 'Stop'
if (-not (Get-Command node -ErrorAction SilentlyContinue)) { throw 'Install Node.js 22.12+ (includes npm) first.' }
& node "$PSScriptRoot/dev.mjs" doctor $Mode --backend-port $BackendPort --frontend-port $FrontendPort
exit $LASTEXITCODE
