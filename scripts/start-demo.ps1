$ErrorActionPreference = 'Stop'
Push-Location (Split-Path $PSScriptRoot -Parent)
try {
    & mvn -B -ntp package
    if ($LASTEXITCODE -ne 0) { throw 'Build or tests failed.' }
    & java -jar target/easymall-1.0.0.jar --spring.profiles.active=demo
} finally { Pop-Location }
