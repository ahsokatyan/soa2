$projectRoot = Split-Path -Parent $PSScriptRoot
$runtimeDir = Join-Path $projectRoot '.runtime'
if (-not (Test-Path "$runtimeDir/config/local.json")) { throw 'Run bootstrap.ps1 and prepare-local.ps1 first.' }
$settings = Get-Content -Raw -LiteralPath "$runtimeDir/config/local.json" | ConvertFrom-Json
$java = (Get-Command java.exe).Source
$env:JAVA_HOME = Split-Path -Parent (Split-Path -Parent $java)
$env:SOA_INTERNAL_TOKEN = (Get-Content -Raw -LiteralPath "$runtimeDir/config/internal-token.txt").Trim()
$env:SOA_JDBC_URL = "jdbc:h2:file:$($runtimeDir.Replace('\','/'))/data/workers;DB_CLOSE_ON_EXIT=FALSE"
$env:SOA_JDBC_DRIVER = 'org.h2.Driver'
$env:SOA_JDBC_USER = 'sa'
$env:SOA_JDBC_PASSWORD = ''
$env:SOA_JDBC_SCHEMA = ''
Remove-Item Env:SOA_JDBC_PASSWORD_FILE -ErrorAction SilentlyContinue
$env:SOA_CLIENT_ORIGINS = "https://localhost:$($settings.ClientPort)"
$env:SOA_WORKER_URL = "https://localhost:$($settings.WorkerPort)/workers/api"
$env:SOA_PUBLIC_WORKER_URL = $env:SOA_WORKER_URL
$env:SOA_TRUSTSTORE = "$runtimeDir/certs/truststore.p12"
$env:SOA_STORE_PASSWORD = $settings.StorePassword
$env:SOA_ORGANIZATIONS = '10,20,30'
