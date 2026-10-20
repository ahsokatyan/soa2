param([string]$JavaHome,[ValidateSet('PostgreSQL','H2')][string]$Database = 'PostgreSQL')
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $projectRoot
$validationRoot = (Get-Content -Raw -LiteralPath "$projectRoot/.runtime/last-helios-stage.txt").Trim()
$allowedPrefix = [IO.Path]::GetFullPath("$projectRoot/.runtime").TrimEnd('\') + '\'
if (-not [IO.Path]::GetFullPath($validationRoot).StartsWith($allowedPrefix,[StringComparison]::OrdinalIgnoreCase)) { throw 'Validation must run inside .runtime.' }
if (-not $JavaHome) { $JavaHome = Split-Path -Parent (Split-Path -Parent (Get-Command java.exe).Source) }
$java = Join-Path $JavaHome 'bin/java.exe'
$env:JAVA_HOME = $JavaHome
Remove-Item Env:_JAVA_OPTIONS -ErrorAction SilentlyContinue
& $java -version
function Invoke-Tool([string[]]$Arguments) {
    & $java -cp "$validationRoot/tools;$validationRoot/tools/lib/*" DeployTool @Arguments
    if ($LASTEXITCODE -ne 0) { throw "DeployTool failed: $($Arguments[0])" }
}
$serverDir = Join-Path $validationRoot 'servers'
New-Item -ItemType Directory -Force -Path $serverDir | Out-Null
if (-not (Test-Path "$serverDir/payara6")) { Expand-Archive -LiteralPath "$validationRoot/distributions/payara-6.2025.11.zip" -DestinationPath $serverDir }
if (-not (Test-Path "$serverDir/apache-tomcat-10.1.61")) { Expand-Archive -LiteralPath "$validationRoot/distributions/apache-tomcat-10.1.61.zip" -DestinationPath $serverDir }
Invoke-Tool @('verify',$validationRoot)
Invoke-Tool @('configure',$validationRoot,'8548','8810','5215','367')
$env:SOA_INTERNAL_TOKEN = (Get-Content -Raw -LiteralPath "$validationRoot/runtime/secrets/internal-token").Trim()
$env:SOA_STORE_PASSWORD = (Get-Content -Raw -LiteralPath "$validationRoot/runtime/secrets/store-password").Trim()
Remove-Item Env:SOA_JDBC_PASSWORD -ErrorAction SilentlyContinue
Remove-Item Env:SOA_JDBC_PASSWORD_FILE -ErrorAction SilentlyContinue
if ($Database -eq 'PostgreSQL') {
    & powershell.exe -NoProfile -ExecutionPolicy Bypass -File "$projectRoot/scripts/postgres-local.ps1"
    if ($LASTEXITCODE -ne 0) { throw 'Local PostgreSQL setup failed' }
    $env:SOA_JDBC_URL = 'jdbc:postgresql://127.0.0.1:15432/s367056?connectTimeout=3&socketTimeout=15'
    $env:SOA_JDBC_USER = 'studs'
    $env:SOA_JDBC_DRIVER = 'org.postgresql.Driver'
    $env:SOA_JDBC_SCHEMA = 'soa_lab2'
    $env:SOA_JDBC_PASSWORD_FILE = "$projectRoot/.runtime/postgresql-validation/test-password"
    Invoke-Tool @('check-db',$validationRoot)
} else {
    $env:SOA_JDBC_URL = "jdbc:h2:file:$($validationRoot.Replace('\','/'))/runtime/data/workers;DB_CLOSE_ON_EXIT=FALSE"
    $env:SOA_JDBC_USER = 'sa'
    $env:SOA_JDBC_PASSWORD = ''
    $env:SOA_JDBC_DRIVER = 'org.h2.Driver'
    $env:SOA_JDBC_SCHEMA = ''
}
$env:SOA_WORKER_URL = 'https://localhost:8548/workers/api'
$env:SOA_PUBLIC_WORKER_URL = $env:SOA_WORKER_URL
$env:SOA_PUBLIC_HR_URL = 'https://localhost:8810/api'
$env:SOA_CLIENT_ORIGINS = 'https://localhost:8810'
$env:SOA_TRUSTSTORE = "$validationRoot/runtime/certs/truststore.p12"
$env:SOA_ORGANIZATIONS = '10,20,30'
$env:CLIENT_URL = 'https://localhost:8810'
$env:WORKER_URL = $env:SOA_WORKER_URL
$cli = "$serverDir/payara6/glassfish/lib/client/appserver-cli.jar"
$domainParent = "$validationRoot/runtime/payara-domains"
$payaraStarted = $false
$tomcatStarted = $false
try {
    Invoke-Tool @('ports',$validationRoot,'8548','8810','5215','367')
    Invoke-Tool @('prepare-deploy',$validationRoot)
    & $java -jar $cli --host 127.0.0.1 --port 5215 start-domain --domaindir $domainParent soa
    if ($LASTEXITCODE -ne 0) { throw 'Server-config Payara failed to start' }
    $payaraStarted = $true
    & $java -jar $cli --host 127.0.0.1 --port 5215 deploy --force=true --name workers --contextroot workers "$validationRoot/apps/workers.war"
    if ($LASTEXITCODE -ne 0) { throw 'Server-config Worker failed to deploy' }
    Invoke-Tool @('wait-worker',$validationRoot,'8548','8810')
    $tomcatDir = "$serverDir/apache-tomcat-10.1.61"
    $tomcatBase = "$validationRoot/runtime/tomcat"
    Copy-Item -LiteralPath "$validationRoot/apps/ROOT.war" -Destination "$tomcatBase/webapps/ROOT.war" -Force
    $arguments = @('-Xms32m','-Xmx256m','-XX:MaxMetaspaceSize=192m','-XX:ActiveProcessorCount=2','-Dfile.encoding=UTF-8','-Djava.net.preferIPv4Stack=true',"-Dcatalina.home=$tomcatDir","-Dcatalina.base=$tomcatBase","-Djava.io.tmpdir=$tomcatBase/temp","-Djava.util.logging.config.file=$tomcatBase/conf/logging.properties",'-Djava.util.logging.manager=org.apache.juli.ClassLoaderLogManager','-cp',"$tomcatDir/bin/bootstrap.jar;$tomcatDir/bin/tomcat-juli.jar",'org.apache.catalina.startup.Bootstrap','start')
    $quoted = $arguments | ForEach-Object { '"' + $_ + '"' }
    $process = Start-Process -FilePath $java -ArgumentList $quoted -WorkingDirectory $validationRoot -WindowStyle Hidden -PassThru -RedirectStandardOutput "$validationRoot/runtime/logs/tomcat.out.log" -RedirectStandardError "$validationRoot/runtime/logs/tomcat.err.log"
    [IO.File]::WriteAllText("$validationRoot/runtime/tomcat.pid",[string]$process.Id)
    $tomcatStarted = $true
    Invoke-Tool @('wait-client',$validationRoot,'8548','8810')
    & "$projectRoot/mvnw.cmd" -B -ntp verify -Pintegration '-Dworker.url=https://localhost:8548/workers/api' '-Dhr.url=https://localhost:8810/api' "-Dtest.truststore=$validationRoot/runtime/certs/truststore.p12"
    if ($LASTEXITCODE -ne 0) { throw 'Integration verification failed against server configuration' }
    & npm.cmd --prefix verification/ui test
    if ($LASTEXITCODE -ne 0) { throw 'Browser verification failed against server configuration' }
    Invoke-Tool @('smoke',$validationRoot,'8548','8810')
    Invoke-Tool @('status',$validationRoot,'8548','8810')
    if ($Database -eq 'PostgreSQL') {
        & powershell.exe -NoProfile -ExecutionPolicy Bypass -File "$PSScriptRoot/test-postgres-resilience.ps1" -InstallationRoot $validationRoot -JavaHome $JavaHome
        if ($LASTEXITCODE -ne 0) { throw 'PostgreSQL resilience verification failed' }
    }
    $reportRoot = "$projectRoot/verification/target/helios-$($Database.ToLowerInvariant())"
    New-Item -ItemType Directory -Force -Path $reportRoot | Out-Null
    Copy-Item -LiteralPath "$projectRoot/verification/target/failsafe-reports" -Destination $reportRoot -Recurse -Force
    Copy-Item -LiteralPath "$projectRoot/verification/target/browser" -Destination $reportRoot -Recurse -Force
    if ($Database -eq 'PostgreSQL') { Copy-Item -LiteralPath "$validationRoot/runtime/logs/postgres-resilience.json" -Destination $reportRoot }
    Write-Host "PASS: $Database, Java 17, API integration suite, database-specific checks, 12 browser scenarios and 10-operation portable smoke check."
} finally {
    if ($tomcatStarted) { & $java -cp "$validationRoot/tools;$validationRoot/tools/lib/*" DeployTool stop-tomcat $validationRoot; if ($LASTEXITCODE -ne 0) { Write-Warning 'Could not stop the validation Tomcat. Inspect its recorded PID.' } }
    if ($payaraStarted) { & $java -jar $cli --host 127.0.0.1 --port 5215 stop-domain --domaindir $domainParent soa }
}
