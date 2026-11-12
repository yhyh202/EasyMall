param([Parameter(Mandatory=$true)][string]$StateFile)
$ErrorActionPreference='Stop'
$state=Get-Content -Raw -LiteralPath $StateFile | ConvertFrom-Json
$root=[IO.Path]::GetFullPath((Join-Path (Split-Path $PSScriptRoot -Parent) 'data'))
$run=[IO.Path]::GetFullPath($state.runDirectory)
if (-not $run.StartsWith($root+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)) { throw 'Run directory must be inside this workspace data/.' }
if ($state.databaseName -ne 'easymall_bench') { throw 'Not an isolated benchmark state file.' }
$app=Get-CimInstance Win32_Process -Filter "ProcessId = $($state.appPid)"
if ($app) {
    if (-not $app.CommandLine.Contains((Join-Path $run 'easymall.jar'))) { throw 'App PID no longer belongs to this benchmark.' }
    $jar=Join-Path $run 'easymall.jar'
    # Oracle javapath/java.exe is a launcher; terminate its actual JVM as well.
    Get-CimInstance Win32_Process -Filter "Name = 'java.exe'" | Where-Object {
        $_.ProcessId -ne $state.appPid -and $_.CommandLine -and $_.CommandLine.Contains($jar)
    } | ForEach-Object { Stop-Process -Id $_.ProcessId }
    Stop-Process -Id $state.appPid -ErrorAction SilentlyContinue
}
$db=Get-CimInstance Win32_Process -Filter "ProcessId = $($state.databasePid)"
if ($db) {
    if (-not $db.CommandLine.Contains((Join-Path $run 'mysql-data'))) { throw 'Database PID no longer belongs to this benchmark.' }
    & (Join-Path $state.mysqlBin 'mysqladmin.exe') "--defaults-file=$($state.mysqlClientFile)" shutdown
    if ($LASTEXITCODE -ne 0) { throw 'MySQL graceful shutdown failed.' }
}
Write-Output "Stopped isolated benchmark processes. Data and reports retained at $run"
