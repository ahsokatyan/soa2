param([switch]$MavenOnly)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$runtimeDir = Join-Path $projectRoot '.runtime'
$downloadsDir = Join-Path $runtimeDir 'downloads'
New-Item -ItemType Directory -Force -Path $downloadsDir | Out-Null
function Get-Distribution($Name, $Url, $Directory) {
    $archive = Join-Path $downloadsDir $Name
    if (-not (Test-Path -LiteralPath $archive)) {
        & curl.exe --fail --location --retry 2 --connect-timeout 20 --max-time 900 --output $archive $Url
        if ($LASTEXITCODE -ne 0) { throw "Download failed: $Url" }
    }
    $checksum = (& curl.exe --fail --location --silent --show-error --connect-timeout 20 --max-time 60 "$Url.sha1") -join ''
    if ($LASTEXITCODE -ne 0) { throw "Cannot download checksum for $Name" }
    $expected = [regex]::Match($checksum, '[a-fA-F0-9]{40}').Value
    if (-not $expected -or (Get-FileHash -LiteralPath $archive -Algorithm SHA1).Hash -ne $expected) { throw "Checksum mismatch: $archive. Remove the incomplete archive and retry." }
    if (-not (Test-Path -LiteralPath (Join-Path $runtimeDir $Directory))) { Expand-Archive -LiteralPath $archive -DestinationPath $runtimeDir }
}
Get-Distribution 'apache-maven-3.9.11-bin.zip' 'https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.11/apache-maven-3.9.11-bin.zip' 'apache-maven-3.9.11'
if (-not $MavenOnly) {
    Get-Distribution 'payara-6.2025.11.zip' 'https://repo.maven.apache.org/maven2/fish/payara/distributions/payara/6.2025.11/payara-6.2025.11.zip' 'payara6'
    Get-Distribution 'apache-tomcat-10.1.61.zip' 'https://repo.maven.apache.org/maven2/org/apache/tomcat/tomcat/10.1.61/tomcat-10.1.61.zip' 'apache-tomcat-10.1.61'
}
