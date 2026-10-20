$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $projectRoot
$config = Get-Content -Raw -LiteralPath '.runtime/config/local.json' | ConvertFrom-Json
& "$projectRoot/mvnw.cmd" -B -ntp verify -Pintegration "-Dworker.url=https://localhost:$($config.WorkerPort)/workers/api" "-Dhr.url=https://localhost:$($config.ClientPort)/api" "-Dtest.truststore=$projectRoot/.runtime/certs/truststore.p12"
if ($LASTEXITCODE -ne 0) { throw 'Verification failed. See verification/target/failsafe-reports.' }
