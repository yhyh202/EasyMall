param(
    [string]$MySqlBin = 'D:\develop\mysql-8.0.34-winx64\bin',
    [int]$DatabasePort = 13327,
    [int]$AppPort = 18081
)
# Creates a new isolated local MySQL instance. No existing schema is touched.
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
foreach ($port in @($DatabasePort,$AppPort)) {
    if (Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue) { throw "Port $port is in use." }
}
$run = Join-Path $root ('data\benchmark-' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
New-Item -ItemType Directory -Path $run | Out-Null
$dbPath = Join-Path $run 'mysql-data'
$mysqld = Join-Path $MySqlBin 'mysqld.exe'
$mysql = Join-Path $MySqlBin 'mysql.exe'
$basedir = Split-Path $MySqlBin -Parent
$init = Start-Process -FilePath $mysqld -ArgumentList @('--no-defaults','--initialize-insecure',"--basedir=`"$basedir`"","--datadir=`"$dbPath`"") -WindowStyle Hidden -PassThru -Wait -RedirectStandardOutput (Join-Path $run 'mysql-init.out.log') -RedirectStandardError (Join-Path $run 'mysql-init.err.log')
if ($init.ExitCode -ne 0) { throw "MySQL initialization failed: $run" }
$db = Start-Process -FilePath $mysqld -ArgumentList @('--no-defaults',"--basedir=`"$basedir`"","--datadir=`"$dbPath`"","--port=$DatabasePort",'--bind-address=127.0.0.1','--mysqlx=0','--innodb-buffer-pool-size=268435456','--max-connections=120','--performance-schema=ON','--console') -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $run 'mysql.out.log') -RedirectStandardError (Join-Path $run 'mysql.err.log')
$app = $null
try {
    $ready = $false
    for ($i=0;$i -lt 60;$i++) {
        & $mysql --no-defaults --host=127.0.0.1 "--port=$DatabasePort" --user=root --connect-timeout=2 --execute='SELECT 1' 2>$null | Out-Null
        if ($LASTEXITCODE -eq 0) { $ready=$true; break }
        Start-Sleep -Seconds 1
    }
    if (-not $ready) { throw 'Isolated MySQL did not become ready.' }
    $password = 'Bench_' + [Guid]::NewGuid().ToString('N')
    $sql = "CREATE DATABASE easymall_bench CHARACTER SET utf8mb4; CREATE DATABASE easymall_bench_tests CHARACTER SET utf8mb4; ALTER USER 'root'@'localhost' IDENTIFIED BY '$password';"
    $sql | & $mysql --no-defaults --host=127.0.0.1 "--port=$DatabasePort" --user=root
    if ($LASTEXITCODE -ne 0) { throw 'Creating benchmark databases failed.' }
    $clientFile = Join-Path $run 'mysql-client.cnf'
    [IO.File]::WriteAllText($clientFile,"[client]`nhost=127.0.0.1`nport=$DatabasePort`nuser=root`npassword=$password`n",(New-Object Text.UTF8Encoding($false)))
    $properties = Join-Path $run 'benchmark.properties'
    $settings = @"
server.address=127.0.0.1
server.port=$AppPort
spring.datasource.url=jdbc:mysql://127.0.0.1:$DatabasePort/easymall_bench?serverTimezone=UTC&forceConnectionTimeZoneToSession=true
spring.datasource.username=root
spring.datasource.password=$password
spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver
spring.datasource.hikari.maximum-pool-size=20
app.admin-password=DemoAdmin123!
app.seed-data=true
management.endpoints.web.exposure.include=health,metrics
logging.level.root=WARN
debug=false
"@
    [IO.File]::WriteAllText($properties,$settings,(New-Object Text.UTF8Encoding($false)))
    $jar = Join-Path $run 'easymall.jar'
    Copy-Item -LiteralPath (Join-Path $root 'target\easymall-1.0.0.jar') -Destination $jar
    $configUri = 'file:' + ($properties -replace '\\','/')
    $app = Start-Process -FilePath 'java.exe' -ArgumentList @('-Xms128m','-Xmx384m','-jar',"`"$jar`"",'--spring.profiles.active=mysql',"--spring.config.additional-location=`"$configUri`"") -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $run 'app.out.log') -RedirectStandardError (Join-Path $run 'app.err.log')
    $ready=$false
    for ($i=0;$i -lt 90;$i++) {
        try { $health=Invoke-RestMethod "http://127.0.0.1:$AppPort/actuator/health" -TimeoutSec 2; if ($health.status -eq 'UP') { $ready=$true; break } } catch {}
        Start-Sleep -Seconds 1
    }
    if (-not $ready) { throw "Benchmark app did not become ready: $run" }
    $state = @{runDirectory=$run;mysqlBin=$MySqlBin;mysqlClientFile=$clientFile;databasePort=$DatabasePort;appPort=$AppPort;databasePid=$db.Id;appPid=$app.Id;databaseName='easymall_bench';baseUrl="http://127.0.0.1:$AppPort";appHeapMb=384;bufferPoolMb=256;connectionPool=20}
    $state | ConvertTo-Json | Set-Content -Encoding UTF8 (Join-Path $run 'state.json')
    Write-Output "Ready. State file: $(Join-Path $run 'state.json')"
} catch {
    if ($app -and -not $app.HasExited) { Stop-Process -Id $app.Id }
    if (-not $db.HasExited) { Stop-Process -Id $db.Id }
    # Java/MySQL launchers on Windows can leave worker children after the parent exits.
    Get-CimInstance Win32_Process -Filter "Name = 'java.exe' OR Name = 'mysqld.exe'" | Where-Object {
        $_.CommandLine -and $_.CommandLine.Contains($run)
    } | ForEach-Object { Stop-Process -Id $_.ProcessId -ErrorAction SilentlyContinue }
    throw
}
