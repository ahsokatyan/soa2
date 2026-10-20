param([int]$WorkerPort = 8443, [int]$ClientPort = 8444)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$runtimeDir = Join-Path $projectRoot '.runtime'
$payaraDir = Join-Path $runtimeDir 'payara6'
$tomcatDir = Join-Path $runtimeDir 'apache-tomcat-10.1.61'
$tomcatBase = Join-Path $runtimeDir 'tomcat-base'
if (-not (Test-Path "$payaraDir/glassfish/domains/domain1/config/domain.xml") -or -not (Test-Path "$tomcatDir/bin/bootstrap.jar")) { throw 'Run scripts/bootstrap.ps1 first.' }
$certDir = Join-Path $runtimeDir 'certs'
$configDir = Join-Path $runtimeDir 'config'
New-Item -ItemType Directory -Force -Path $certDir,$configDir,(Join-Path $runtimeDir 'data'),(Join-Path $runtimeDir 'logs') | Out-Null
foreach ($directory in @('conf','webapps','logs','temp','work')) { New-Item -ItemType Directory -Force -Path (Join-Path $tomcatBase $directory) | Out-Null }
if (-not (Test-Path "$tomcatBase/conf/catalina.properties")) { Copy-Item -Path "$tomcatDir/conf/*" -Destination "$tomcatBase/conf" -Recurse }
$storePassword = 'local-lab-password'
$keytool = (Get-Command keytool.exe).Source
function Invoke-Keytool([string[]]$Arguments) { & $keytool @Arguments; if ($LASTEXITCODE -ne 0) { throw 'keytool failed' } }
$serverStore = Join-Path $certDir 'servers.p12'
if (-not (Test-Path -LiteralPath $serverStore)) {
    Invoke-Keytool @('-genkeypair','-alias','lab','-keyalg','RSA','-keysize','2048','-validity','3650','-dname','CN=localhost,OU=SOA Lab2,O=ITMO','-ext','SAN=dns:localhost,ip:127.0.0.1','-storetype','PKCS12','-keystore',$serverStore,'-storepass',$storePassword,'-keypass',$storePassword,'-noprompt')
    Invoke-Keytool @('-exportcert','-rfc','-alias','lab','-keystore',$serverStore,'-storepass',$storePassword,'-file',(Join-Path $certDir 'localhost.crt'))
    Invoke-Keytool @('-importcert','-noprompt','-alias','lab','-file',(Join-Path $certDir 'localhost.crt'),'-keystore',(Join-Path $certDir 'truststore.p12'),'-storetype','PKCS12','-storepass',$storePassword)
}
$domainDir = Join-Path $payaraDir 'glassfish/domains/domain1'
Invoke-Keytool @('-importkeystore','-noprompt','-srckeystore',$serverStore,'-srcstoretype','PKCS12','-srcstorepass',$storePassword,'-srcalias','lab','-destkeystore',"$domainDir/config/keystore.p12",'-deststorepass','changeit','-destkeypass','changeit','-destalias','lab')

$tokenFile = Join-Path $configDir 'internal-token.txt'
if (-not (Test-Path -LiteralPath $tokenFile)) {
    $randomBytes = New-Object byte[] 32
    $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($randomBytes) } finally { $rng.Dispose() }
    $token = [Convert]::ToBase64String($randomBytes)
    [IO.File]::WriteAllText($tokenFile,$token)
}
# Only generated runtime configuration is modified here; source files are untouched.
[xml]$domain = Get-Content -LiteralPath "$domainDir/config/domain.xml" -Raw
$serverConfig = $domain.domain.configs.config | Where-Object { $_.name -eq 'server-config' }
foreach ($listener in $serverConfig.'network-config'.'network-listeners'.'network-listener') {
    if ($listener.name -eq 'http-listener-1') { $listener.SetAttribute('enabled','false') }
    if ($listener.name -eq 'http-listener-2') { $listener.SetAttribute('port',"$WorkerPort"); $listener.SetAttribute('enabled','true'); $listener.SetAttribute('address','127.0.0.1') }
    if ($listener.name -eq 'admin-listener') { $listener.SetAttribute('port','14848'); $listener.SetAttribute('address','127.0.0.1') }
}
foreach ($protocol in $serverConfig.'network-config'.protocols.protocol) {
    if ($protocol.name -eq 'http-listener-2') { $protocol.ssl.SetAttribute('cert-nickname','lab'); $protocol.ssl.SetAttribute('tls-enabled','true') }
}
$xmlSettings = New-Object System.Xml.XmlWriterSettings
$xmlSettings.Encoding = New-Object System.Text.UTF8Encoding($false)
$xmlSettings.Indent = $true
$xmlWriter = [System.Xml.XmlWriter]::Create("$domainDir/config/domain.xml",$xmlSettings)
try { $domain.Save($xmlWriter) } finally { $xmlWriter.Dispose() }
$serverXml = @"
<?xml version="1.0" encoding="UTF-8"?>
<Server port="-1" shutdown="SHUTDOWN">
  <Service name="Catalina">
    <Connector port="$ClientPort" address="127.0.0.1" protocol="org.apache.coyote.http11.Http11NioProtocol" SSLEnabled="true" scheme="https" secure="true" maxThreads="60">
      <SSLHostConfig protocols="TLSv1.2,+TLSv1.3"><Certificate certificateKeystoreFile="$($serverStore.Replace('\','/'))" certificateKeystorePassword="$storePassword" certificateKeystoreType="PKCS12" certificateKeyAlias="lab" type="RSA"/></SSLHostConfig>
    </Connector>
    <Engine name="Catalina" defaultHost="localhost"><Host name="localhost" appBase="webapps" unpackWARs="true" autoDeploy="true"/></Engine>
  </Service>
</Server>
"@
[IO.File]::WriteAllText((Join-Path $tomcatBase 'conf/server.xml'),$serverXml,[Text.UTF8Encoding]::new($false))
$settings = @{ WorkerPort=$WorkerPort; ClientPort=$ClientPort; PayaraDir=$payaraDir; TomcatDir=$tomcatDir; TomcatBase=$tomcatBase; StorePassword=$storePassword }
[IO.File]::WriteAllText((Join-Path $configDir 'local.json'),($settings | ConvertTo-Json),[Text.UTF8Encoding]::new($false))
Write-Host "Local HTTPS configured: Worker $WorkerPort, client/HR $ClientPort"
