param(
    [Parameter(Mandatory=$true)][string]$TestDatabaseUrl,
    [string]$Username = $env:DB_USERNAME,
    [string]$Password = $env:DB_PASSWORD
)
# Run only against a dedicated test database. Tests create users, products and orders;
# they do not delete unrelated data. Demo bootstrap is used with the overridden datasource.
$ErrorActionPreference = 'Stop'
if ($TestDatabaseUrl -notmatch '^jdbc:mysql://') { throw 'Expected a JDBC MySQL URL for an isolated test database.' }
$env:SPRING_DATASOURCE_URL=$TestDatabaseUrl
$env:SPRING_DATASOURCE_USERNAME=$Username
$env:SPRING_DATASOURCE_PASSWORD=$Password
$env:SPRING_DATASOURCE_DRIVER_CLASS_NAME='com.mysql.cj.jdbc.Driver'
Push-Location (Split-Path $PSScriptRoot -Parent)
try { & mvn -B -ntp test; if ($LASTEXITCODE -ne 0) { throw 'MySQL integration tests failed.' } }
finally { Pop-Location }
