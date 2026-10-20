$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'local-env.ps1')
Set-Location -LiteralPath $projectRoot
$resultDir = Join-Path $runtimeDir 'verification'
New-Item -ItemType Directory -Force -Path $resultDir | Out-Null
$responseFile = Join-Path $resultDir 'response.xml'
$asadmin = Join-Path $settings.PayaraDir 'bin/asadmin.bat'
$checks = @()
$sentinelId = $null
$stopped = $false
function Invoke-XmlRequest($Method, $Url, $Expected, $Body) {
    $arguments = @('--noproxy','*','--silent','--show-error','--cacert',"$runtimeDir/certs/localhost.crt",'--connect-timeout','3','--max-time','15','--header','Accept: application/xml','--request',$Method,'--output',$responseFile,'--write-out','%{http_code}')
    if ($Body) { $arguments += @('--header','Content-Type: application/xml','--data-binary',$Body) }
    $arguments += $Url
    $status = (& curl.exe @arguments) -join ''
    if ($LASTEXITCODE -ne 0 -or [int]$status -ne $Expected) { throw "Unexpected HTTP $status for $Method $Url" }
    if ($Expected -eq 204) { return $null }
    return [xml](Get-Content -LiteralPath $responseFile -Encoding UTF8 -Raw)
}
function Wait-Worker {
    for ($attempt = 0; $attempt -lt 30; $attempt++) {
        & curl.exe --noproxy '*' --silent --fail --cacert "$runtimeDir/certs/localhost.crt" --connect-timeout 1 --max-time 2 --output NUL "$env:SOA_WORKER_URL/workers"
        if ($LASTEXITCODE -eq 0) { return }
        Start-Sleep -Milliseconds 500
    }
    throw 'Worker did not restart.'
}
try {
    $payload = '<worker><name>restart-verification</name><coordinates><x>1</x><y>2</y></coordinates><salary>123</salary><startDate>2025-09-01</startDate><organizationId>20</organizationId></worker>'
    $before = Invoke-XmlRequest POST "$env:SOA_WORKER_URL/workers" 201 $payload
    $sentinelId = [string]$before.worker.id
    & $asadmin --host 127.0.0.1 --port 14848 stop-domain domain1
    if ($LASTEXITCODE -ne 0) { throw 'Cannot stop Worker for the outage test.' }
    $stopped = $true
    $errorResponse = Invoke-XmlRequest POST "https://localhost:$($settings.ClientPort)/api/hr/index/all/20/1.1" 502
    if ($errorResponse.error.code -ne 'WORKER_SERVICE_UNAVAILABLE') { throw 'Wrong upstream error response.' }
    $checks += 'HR returns XML 502 while Worker is stopped'
    & $asadmin --host 127.0.0.1 --port 14848 start-domain domain1
    if ($LASTEXITCODE -ne 0) { throw 'Cannot restart Worker.' }
    $stopped = $false
    Wait-Worker
    $after = Invoke-XmlRequest GET "$env:SOA_WORKER_URL/workers/$sentinelId" 200
    if ($after.worker.name -ne $before.worker.name -or $after.worker.creationDate -ne $before.worker.creationDate -or [float]$after.worker.salary -ne 123) { throw 'Data changed or disappeared after restart.' }
    $checks += 'H2 data and server-generated fields survive Worker restart'
    $indexed = Invoke-XmlRequest POST "https://localhost:$($settings.ClientPort)/api/hr/index/all/20/1" 200
    if ([int]$indexed.indexResult.updatedWorkers -lt 1) { throw 'HR did not recover.' }
    $checks += 'HR reconnects with certificate verification after Worker restart'
    foreach ($port in @($settings.WorkerPort,$settings.ClientPort)) {
        $status = (& curl.exe --noproxy '*' --silent --connect-timeout 2 --max-time 3 --output NUL --write-out '%{http_code}' "http://localhost:$port/") -join ''
        if ($status -notin @('000','400','403','426')) { throw "Plain HTTP was not rejected on port $port (HTTP $status)" }
    }
    $checks += 'Both service ports reject plaintext HTTP'
    [xml]$domain = Get-Content -LiteralPath "$($settings.PayaraDir)/glassfish/domains/domain1/config/domain.xml" -Raw
    $serverConfig = $domain.domain.configs.config | Where-Object { $_.name -eq 'server-config' }
    $plainListener = $serverConfig.'network-config'.'network-listeners'.'network-listener' | Where-Object { $_.name -eq 'http-listener-1' }
    if ($plainListener.enabled -ne 'false') { throw 'Payara plaintext connector is enabled.' }
    [xml]$tomcat = Get-Content -LiteralPath "$($settings.TomcatBase)/conf/server.xml" -Raw
    foreach ($connector in $tomcat.Server.Service.Connector) { if ($connector.SSLEnabled -ne 'true') { throw 'Tomcat has a plaintext connector.' } }
    $checks += 'Payara HTTP listener disabled; Tomcat has only a TLS connector'
    [IO.File]::WriteAllText((Join-Path $resultDir 'resilience.json'),(@{passed=$checks} | ConvertTo-Json),[Text.UTF8Encoding]::new($false))
    $checks | ForEach-Object { Write-Host "PASS: $_" }
} finally {
    if ($stopped) { & $asadmin --host 127.0.0.1 --port 14848 start-domain domain1; Wait-Worker }
    if ($sentinelId) { $null = Invoke-XmlRequest DELETE "$env:SOA_WORKER_URL/workers/$sentinelId" 204 }
}
