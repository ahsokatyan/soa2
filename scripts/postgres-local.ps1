param([ValidateSet('Start','Stop')][string]$Action = 'Start')
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$pgRoot = Join-Path $projectRoot '.runtime/postgresql-validation'
$pgBin = Join-Path $pgRoot 'pgsql/bin'
$pgData = Join-Path $pgRoot 'data'
$passwordFile = Join-Path $pgRoot 'test-password'
if ($Action -eq 'Stop') {
    if (Test-Path -LiteralPath "$pgData/postmaster.pid") {
        & "$pgBin/pg_ctl.exe" -D $pgData -w -t 30 stop -m fast
        if ($LASTEXITCODE -ne 0) { throw 'Could not stop the local validation PostgreSQL' }
    }
    exit 0
}
New-Item -ItemType Directory -Force -Path $pgRoot | Out-Null
if (-not (Test-Path -LiteralPath "$pgBin/pg_ctl.exe")) {
    $archive = Join-Path $projectRoot '.runtime/downloads/postgresql-17.11-3-windows-x64-binaries.zip'
    if (-not (Test-Path -LiteralPath $archive)) {
        & curl.exe --fail --location --retry 2 --connect-timeout 20 --max-time 900 --output $archive 'https://get.enterprisedb.com/postgresql/postgresql-17.11-3-windows-x64-binaries.zip'
        if ($LASTEXITCODE -ne 0) { throw 'PostgreSQL download failed' }
    }
    if ((Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash -ne '4B8DB0930C38F6EF845DB919551DEDDA3B6B845AEB0927B3D79A6E8E9E4537CF') { throw 'Unexpected PostgreSQL archive checksum' }
    & tar.exe -xf $archive -C $pgRoot pgsql/bin pgsql/lib pgsql/share
    if ($LASTEXITCODE -ne 0) { throw 'PostgreSQL extraction failed' }
}
$newCluster = -not (Test-Path -LiteralPath "$pgData/PG_VERSION")
if ($newCluster) {
    $random = New-Object byte[] 32
    $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($random) } finally { $rng.Dispose() }
    [IO.File]::WriteAllText($passwordFile,([BitConverter]::ToString($random).Replace('-','').ToLowerInvariant()))
    & "$pgBin/initdb.exe" -D $pgData -U soa_test_admin -A scram-sha-256 --pwfile=$passwordFile --encoding=UTF8 --locale=C
    if ($LASTEXITCODE -ne 0) { throw 'Local PostgreSQL initdb failed' }
}
if (-not (Test-Path -LiteralPath "$pgData/postmaster.pid")) {
    $probe = New-Object Net.Sockets.TcpListener([Net.IPAddress]::Loopback,15432)
    try { $probe.Start() } finally { $probe.Stop() }
    & "$pgBin/pg_ctl.exe" -D $pgData -l "$pgRoot/server.log" -o '-h 127.0.0.1 -p 15432 -c shared_buffers=32MB -c max_connections=20' -w -t 30 start
    if ($LASTEXITCODE -ne 0) { throw 'Local PostgreSQL startup failed' }
}
if ($newCluster) {
    $env:PGPASSWORD = [IO.File]::ReadAllText($passwordFile)
    try {
        # Test-only role, deliberately without superuser or CREATEDB privileges.
        "CREATE ROLE studs LOGIN PASSWORD '$env:PGPASSWORD' NOSUPERUSER NOCREATEDB NOCREATEROLE;`nCREATE DATABASE s367056 OWNER studs;" | & "$pgBin/psql.exe" -X -v ON_ERROR_STOP=1 -h 127.0.0.1 -p 15432 -U soa_test_admin -d postgres
        if ($LASTEXITCODE -ne 0) { throw 'Local PostgreSQL role/database initialization failed' }
    } finally { Remove-Item Env:PGPASSWORD -ErrorAction SilentlyContinue }
}
Write-Host 'Local PostgreSQL: 127.0.0.1:15432, database s367056, non-superuser studs. No connection to Helios.'
$env:PGPASSWORD = [IO.File]::ReadAllText($passwordFile)
try {
    'CREATE SCHEMA IF NOT EXISTS soa_no_create AUTHORIZATION soa_test_admin; REVOKE ALL ON SCHEMA soa_no_create FROM PUBLIC, studs; GRANT USAGE ON SCHEMA soa_no_create TO studs;' | & "$pgBin/psql.exe" -X -v ON_ERROR_STOP=1 -h 127.0.0.1 -p 15432 -U soa_test_admin -d s367056
    if ($LASTEXITCODE -ne 0) { throw 'Local restricted-schema initialization failed' }
} finally { Remove-Item Env:PGPASSWORD -ErrorAction SilentlyContinue }
