param([switch]$SkipBuild)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $projectRoot
if (-not $SkipBuild) {
    & "$projectRoot/mvnw.cmd" -B -ntp package
    if ($LASTEXITCODE -ne 0) { throw 'Maven build failed' }
}
$stagingParent = Join-Path $projectRoot ('.runtime/helios-package-' + [Guid]::NewGuid().ToString('N'))
$stage = Join-Path $stagingParent 'soa2-helios'
foreach ($folder in @('apps','distributions','tools/lib','docs')) { New-Item -ItemType Directory -Force -Path (Join-Path $stage $folder) | Out-Null }
Copy-Item -Path "$projectRoot/deployment/helios/*" -Destination $stage -Recurse -Force
Copy-Item -LiteralPath "$projectRoot/worker-service/target/workers.war" -Destination "$stage/apps/workers.war"
Copy-Item -LiteralPath "$projectRoot/hr-service/target/ROOT.war" -Destination "$stage/apps/ROOT.war"
$drivers = @(Get-ChildItem -LiteralPath "$projectRoot/worker-service/target/workers/WEB-INF/lib" -Filter 'postgresql-*.jar' -File)
if ($drivers.Count -ne 1) { throw 'Expected exactly one PostgreSQL driver in the built Worker WAR' }
Copy-Item -LiteralPath $drivers[0].FullName -Destination "$stage/tools/lib"
New-Item -ItemType Directory -Force -Path "$stage/tools/src" | Out-Null
Copy-Item -LiteralPath "$projectRoot/common/src/main/java/ru/itmo/soa/common/Settings.java","$projectRoot/common/src/main/java/ru/itmo/soa/common/JdbcDatabase.java" -Destination "$stage/tools/src"
foreach ($archive in @('payara-6.2025.11.zip','apache-tomcat-10.1.61.zip')) {
    Copy-Item -LiteralPath "$projectRoot/.runtime/downloads/$archive" -Destination "$stage/distributions/$archive"
}
Copy-Item -LiteralPath "$projectRoot/DEPLOY_HELIOS.md" -Destination "$stage/README.md"
Copy-Item -LiteralPath "$projectRoot/openapi.yaml","$projectRoot/internal-api.yaml" -Destination "$stage/docs"
Copy-Item -LiteralPath "$projectRoot/README.md" -Destination "$stage/docs/TASK_LAB2.md"
# Mechanical encoding normalization ensures POSIX scripts work after SCP.
$utf8 = New-Object Text.UTF8Encoding($false)
Get-ChildItem -LiteralPath $stage -Recurse -File | Where-Object { $_.Extension -in @('.sh','.conf','.md','.java','.yaml') } | ForEach-Object {
    $content = [IO.File]::ReadAllText($_.FullName).Replace("`r`n","`n")
    [IO.File]::WriteAllText($_.FullName,$content,$utf8)
}
& javac.exe --release 17 -encoding UTF-8 -d "$stage/tools" "$stage/tools/DeployTool.java" "$stage/tools/src/Settings.java" "$stage/tools/src/JdbcDatabase.java"
if ($LASTEXITCODE -ne 0) { throw 'Deployment utility compilation failed' }
$manifest = Get-ChildItem -LiteralPath $stage -Recurse -File | Sort-Object FullName | ForEach-Object {
    $relative = $_.FullName.Substring($stage.Length + 1).Replace('\','/')
    # This file is intentionally editable before setup (JAVA_HOME / ports).
    if ($relative -ne 'config/helios.conf') { (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant() + '  ' + $relative }
}
[IO.File]::WriteAllText("$stage/SHA256SUMS",($manifest -join "`n") + "`n",$utf8)
New-Item -ItemType Directory -Force -Path "$projectRoot/dist" | Out-Null
$archivePath = Join-Path $projectRoot 'dist/soa2-helios.tar.gz'
& tar.exe -czf $archivePath -C $stagingParent soa2-helios
if ($LASTEXITCODE -ne 0) { throw 'Archive creation failed' }
$hash = (Get-FileHash -LiteralPath $archivePath -Algorithm SHA256).Hash.ToLowerInvariant()
[IO.File]::WriteAllText("$archivePath.sha256","$hash  soa2-helios.tar.gz`n",$utf8)
[IO.File]::WriteAllText("$projectRoot/.runtime/last-helios-stage.txt",$stage,$utf8)
Write-Host "Ready: $archivePath"
Write-Host "SHA-256: $hash"
Write-Host "Staging directory: $stage"
