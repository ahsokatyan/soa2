param([switch]$ClientOnly)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$runtimeDir = Join-Path $projectRoot '.runtime'
if (-not (Test-Path "$runtimeDir/config/local.json")) { Write-Host 'Nothing configured.'; exit 0 }
$settings = Get-Content -Raw -LiteralPath "$runtimeDir/config/local.json" | ConvertFrom-Json
if (Test-Path "$runtimeDir/config/tomcat.pid") {
    $tomcatProcessId = [int](Get-Content -LiteralPath "$runtimeDir/config/tomcat.pid")
    $process = Get-CimInstance Win32_Process -Filter "ProcessId=$tomcatProcessId" -ErrorAction SilentlyContinue
    if ($process -and $process.CommandLine -like "*$($settings.TomcatDir)*" -and $process.CommandLine -like '*org.apache.catalina.startup.Bootstrap*') { [Diagnostics.Process]::GetProcessById($tomcatProcessId).Kill(); Write-Host 'Tomcat stopped.' }
}
if (-not $ClientOnly) {
    & "$($settings.PayaraDir)/bin/asadmin.bat" --host 127.0.0.1 --port 14848 stop-domain domain1
    if ($LASTEXITCODE -ne 0) { throw 'Payara stop failed; check whether it is running.' }
}
