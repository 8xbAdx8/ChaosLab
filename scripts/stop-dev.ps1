$ErrorActionPreference = 'Stop'
if (-not (Get-Command node -ErrorAction SilentlyContinue)) { throw 'Install Node.js 22.12+ first.' }
& node "$PSScriptRoot/dev.mjs" stop
exit $LASTEXITCODE
