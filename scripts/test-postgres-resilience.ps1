param([Parameter(Mandatory=$true)][string]$InstallationRoot,[Parameter(Mandatory=$true)][string]$JavaHome)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$allowedPrefix = [IO.Path]::GetFullPath("$projectRoot/.runtime").TrimEnd('\') + '\'
if (-not [IO.Path]::GetFullPath($InstallationRoot).StartsWith($allowedPrefix,[StringComparison]::OrdinalIgnoreCase)) { throw 'Test installation must be inside .runtime' }
if (-not $env:SOA_JDBC_URL.StartsWith('jdbc:postgresql://127.0.0.1:15432/s367056?')) { throw 'Resilience test is restricted to the local test PostgreSQL' }
$java = Join-Path $JavaHome 'bin/java.exe'
$classPath = "$InstallationRoot/tools;$InstallationRoot/tools/lib/*"
$cli = "$InstallationRoot/servers/payara6/glassfish/lib/client/appserver-cli.jar"
$responseFile = "$InstallationRoot/runtime/logs/resilience-response.xml"
$checks = @()
$id = $null
$dbStopped = $false
$payaraStopped = $false
function Request($Method,$Url,$Expected,$Body) {
    $arguments = @('--noproxy','*','--silent','--show-error','--cacert',"$InstallationRoot/runtime/certs/localhost.crt",'--connect-timeout','3','--max-time','15','--header','Accept: application/xml','--request',$Method,'--output',$responseFile,'--write-out','%{http_code}')
    if ($Body) { $arguments += @('--header','Content-Type: application/xml','--data-binary',$Body) }
    $arguments += $Url
    $status = (& curl.exe @arguments) -join ''
    if ($LASTEXITCODE -ne 0 -or [int]$status -ne $Expected) { throw "Unexpected HTTP $status for $Method $Url" }
    if ($Expected -ne 204) { return [xml](Get-Content -LiteralPath $responseFile -Raw -Encoding UTF8) }
}
function Check-Fails($ExpectedText) {
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try { $output = (& $java -cp $classPath DeployTool check-db $InstallationRoot 2>&1 | Out-String); $code = $LASTEXITCODE }
    finally { $ErrorActionPreference = $previous }
    if ($code -eq 0 -or -not $output.Contains($ExpectedText)) { throw "Expected preflight failure: $ExpectedText" }
    $secret = [IO.File]::ReadAllText($env:SOA_JDBC_PASSWORD_FILE)
    if ($secret -and $output.Contains($secret)) { throw 'Database check leaked a password' }
}
try {
    $correctPasswordFile = $env:SOA_JDBC_PASSWORD_FILE
    $invalidPasswordFile = "$InstallationRoot/runtime/secrets/test-invalid-password"
    [IO.File]::WriteAllText($invalidPasswordFile,'test-only-invalid-password')
    try { $env:SOA_JDBC_PASSWORD_FILE = $invalidPasswordFile; Check-Fails '28P01' }
    finally { $env:SOA_JDBC_PASSWORD_FILE = $correctPasswordFile }
    $checks += 'Preflight rejects wrong password without printing it'
    $correctUrl = $env:SOA_JDBC_URL
    try { $env:SOA_JDBC_URL = 'jdbc:h2:mem:forbidden-fallback'; Check-Fails 'Helios requires PostgreSQL' }
    finally { $env:SOA_JDBC_URL = $correctUrl }
    $checks += 'Helios preflight rejects H2 configuration'
    $payload = '<worker><name>postgres-restart-check</name><coordinates><x>9223372036854775807</x><y>-100</y></coordinates><salary>123</salary><startDate>2025-09-01</startDate><organizationId>20</organizationId></worker>'
    $before = Request POST 'https://localhost:8548/workers/api/workers' 201 $payload
    $id = [string]$before.worker.id
    & powershell.exe -NoProfile -ExecutionPolicy Bypass -File "$PSScriptRoot/postgres-local.ps1" -Action Stop
    if ($LASTEXITCODE -ne 0) { throw 'Cannot stop local test PostgreSQL' }
    $dbStopped = $true
    Check-Fails 'SQLSTATE 08'
    $errorXml = Request GET "https://localhost:8548/workers/api/workers/$id" 503
    if ($errorXml.error.code -ne 'SERVICE_UNAVAILABLE') { throw 'Wrong database outage error' }
    $null = Request POST 'https://localhost:8810/api/hr/index/all/20/1' 502
    $checks += 'Database outage produces XML 503 in Worker and 502 in HR; preflight fails'
    & powershell.exe -NoProfile -ExecutionPolicy Bypass -File "$PSScriptRoot/postgres-local.ps1"
    if ($LASTEXITCODE -ne 0) { throw 'Cannot restart local test PostgreSQL' }
    $dbStopped = $false
    & $java -cp $classPath DeployTool wait-worker $InstallationRoot 8548 8810
    if ($LASTEXITCODE -ne 0) { throw 'Worker did not recover after PostgreSQL restart' }
    $after = Request GET "https://localhost:8548/workers/api/workers/$id" 200
    if ($after.worker.OuterXml -ne $before.worker.OuterXml) { throw 'Data changed after PostgreSQL restart' }
    $checks += 'All worker fields survive PostgreSQL restart'
    & $java -jar $cli --host 127.0.0.1 --port 5215 stop-domain --domaindir "$InstallationRoot/runtime/payara-domains" soa
    if ($LASTEXITCODE -ne 0) { throw 'Cannot stop test Payara' }
    $payaraStopped = $true
    $null = Request POST 'https://localhost:8810/api/hr/index/all/20/1' 502
    & $java -jar $cli --host 127.0.0.1 --port 5215 start-domain --domaindir "$InstallationRoot/runtime/payara-domains" soa
    if ($LASTEXITCODE -ne 0) { throw 'Cannot restart test Payara' }
    $payaraStopped = $false
    & $java -cp $classPath DeployTool wait-worker $InstallationRoot 8548 8810
    if ($LASTEXITCODE -ne 0) { throw 'Worker did not recover after Payara restart' }
    $after = Request GET "https://localhost:8548/workers/api/workers/$id" 200
    if ($after.worker.OuterXml -ne $before.worker.OuterXml) { throw 'Data changed after Payara restart' }
    $null = Request POST 'https://localhost:8810/api/hr/index/all/20/1' 200
    $checks += 'All fields survive Payara restart; HR reconnects using verified HTTPS'
    [IO.File]::WriteAllText("$InstallationRoot/runtime/logs/postgres-resilience.json",(@{passed=$checks} | ConvertTo-Json),[Text.UTF8Encoding]::new($false))
    $checks | ForEach-Object { Write-Host "PASS: $_" }
} finally {
    if ($dbStopped) { & powershell.exe -NoProfile -ExecutionPolicy Bypass -File "$PSScriptRoot/postgres-local.ps1" }
    if ($payaraStopped) { & $java -jar $cli --host 127.0.0.1 --port 5215 start-domain --domaindir "$InstallationRoot/runtime/payara-domains" soa }
    if ($id) { $null = Request DELETE "https://localhost:8548/workers/api/workers/$id" 204 }
}
