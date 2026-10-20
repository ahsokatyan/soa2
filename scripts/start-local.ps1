param([switch]$SkipBuild, [switch]$ClientOnly)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'local-env.ps1')
Set-Location -LiteralPath $projectRoot
if (-not $SkipBuild) { & "$projectRoot/mvnw.cmd" -B -ntp package; if ($LASTEXITCODE -ne 0) { throw 'Build failed' } }
$asadmin = Join-Path $settings.PayaraDir 'bin/asadmin.bat'
$adminArguments = @('--host','127.0.0.1','--port','14848')
if (-not $ClientOnly) {
    $probe = New-Object Net.Sockets.TcpClient
    try {
        try { $probe.Connect('127.0.0.1',14848) } catch [Net.Sockets.SocketException] {}
        if ($probe.Connected) { throw 'Payara admin port 14848 is already in use. Stop the local servers first.' }
    } finally { $probe.Dispose() }
    # Register a fresh deployment after startup. Even an inactive old deployment
    # can make Payara open JARs while booting on Windows.
    $domainFile = Join-Path $settings.PayaraDir 'glassfish/domains/domain1/config/domain.xml'
    [xml]$domain = Get-Content -LiteralPath $domainFile -Raw
    Copy-Item -LiteralPath $domainFile -Destination "$runtimeDir/config/domain.before-deploy.xml" -Force
    foreach ($node in $domain.SelectNodes('/domain/servers/server/application-ref[@ref="workers"] | /domain/applications/application[@name="workers"]')) { $null = $node.ParentNode.RemoveChild($node) }
    $xmlSettings = New-Object System.Xml.XmlWriterSettings
    $xmlSettings.Encoding = New-Object System.Text.UTF8Encoding($false)
    $xmlSettings.Indent = $true
    $xmlWriter = [System.Xml.XmlWriter]::Create($domainFile,$xmlSettings)
    try { $domain.Save($xmlWriter) } finally { $xmlWriter.Dispose() }
    $oldDeployment = [IO.Path]::GetFullPath((Join-Path $settings.PayaraDir 'glassfish/domains/domain1/applications/workers'))
    $backupDir = [IO.Path]::GetFullPath((Join-Path $runtimeDir ('deployment-backups/workers-' + [DateTime]::Now.ToString('yyyyMMdd-HHmmss') + '-' + [Guid]::NewGuid().ToString('N').Substring(0,8))))
    $allowedPrefix = [IO.Path]::GetFullPath($runtimeDir).TrimEnd('\') + '\'
    if (-not $oldDeployment.StartsWith($allowedPrefix,[StringComparison]::OrdinalIgnoreCase) -or -not $backupDir.StartsWith($allowedPrefix,[StringComparison]::OrdinalIgnoreCase)) { throw 'Deployment paths must stay inside .runtime.' }
    if (Test-Path -LiteralPath $oldDeployment) {
        New-Item -ItemType Directory -Force -Path (Split-Path -Parent $backupDir) | Out-Null
        Move-Item -LiteralPath $oldDeployment -Destination $backupDir
        Write-Host "Previous generated deployment saved to $backupDir"
    }
    & $asadmin @adminArguments start-domain domain1
    if ($LASTEXITCODE -ne 0) { throw 'Payara could not start. If already running, stop it with stop-local.ps1 before rebuilding.' }
    & $asadmin @adminArguments deploy --force=true --name workers --contextroot workers "$projectRoot/worker-service/target/workers.war"
    if ($LASTEXITCODE -ne 0) { throw 'Worker deployment failed. Check Payara server.log.' }
}
$tomcatDir = $settings.TomcatDir
$tomcatBase = $settings.TomcatBase
Copy-Item -LiteralPath "$projectRoot/hr-service/target/ROOT.war" -Destination "$tomcatBase/webapps/ROOT.war" -Force
$arguments = @("-Dcatalina.home=$tomcatDir","-Dcatalina.base=$tomcatBase","-Djava.io.tmpdir=$tomcatBase/temp","-Djava.util.logging.config.file=$tomcatBase/conf/logging.properties",'-Djava.util.logging.manager=org.apache.juli.ClassLoaderLogManager','-Xms64m','-Xmx384m','-cp',"$tomcatDir/bin/bootstrap.jar;$tomcatDir/bin/tomcat-juli.jar",'org.apache.catalina.startup.Bootstrap','start')
# Start-Process needs explicitly quoted arguments for paths containing spaces.
$quotedArguments = $arguments | ForEach-Object { '"' + $_ + '"' }
$process = Start-Process -FilePath $java -ArgumentList $quotedArguments -WorkingDirectory $projectRoot -WindowStyle Hidden -PassThru -RedirectStandardOutput "$runtimeDir/logs/tomcat.out.log" -RedirectStandardError "$runtimeDir/logs/tomcat.err.log"
[IO.File]::WriteAllText("$runtimeDir/config/tomcat.pid",[string]$process.Id)
foreach ($url in @("$env:SOA_WORKER_URL/workers", "https://localhost:$($settings.ClientPort)/client-config.js")) {
    $ready = $false
    for ($attempt = 0; $attempt -lt 30; $attempt++) {
        & curl.exe --noproxy '*' --silent --fail --cacert "$runtimeDir/certs/localhost.crt" --connect-timeout 1 --max-time 2 --output NUL $url
        if ($LASTEXITCODE -eq 0) { $ready = $true; break }
        Start-Sleep -Milliseconds 500
    }
    if (-not $ready) { throw "Service did not become ready: $url. Check .runtime/logs and Payara server.log." }
}
Write-Host "Tomcat process $($process.Id) ready. Client: https://localhost:$($settings.ClientPort)/"
Write-Host "Worker: $env:SOA_WORKER_URL/workers"
Write-Host 'Check logs and run scripts/verify-local.ps1 after startup.'
