param(
    [string]$BaseUrl = 'http://localhost:8080',
    [ValidateRange(1,5000)][int]$Users = 100,
    [string]$AdminUsername = 'admin',
    [string]$AdminPassword = $env:ADMIN_PASSWORD,
    [string]$RunId = (Get-Date -Format 'yyyyMMddHHmmss'),
    [string]$OutputFile = (Join-Path (Split-Path $PSScriptRoot -Parent) 'data\jmeter-users.csv')
)
$ErrorActionPreference = 'Stop'
if ([string]::IsNullOrWhiteSpace($AdminPassword)) { throw 'Set ADMIN_PASSWORD or pass -AdminPassword (demo: DemoAdmin123!).' }
if ($RunId -notmatch '^[A-Za-z0-9_]{1,24}$') { throw 'RunId must contain 1..24 letters, numbers or underscores.' }
function Invoke-Api([string]$Method,[string]$Path,$Body,[string]$Token,[string]$Key) {
    $headers = @{}
    if ($Token) { $headers.Authorization = "Bearer $Token" }
    if ($Key) { $headers['Idempotency-Key'] = $Key }
    $args = @{ Method=$Method; Uri="$BaseUrl$Path"; Headers=$headers; ContentType='application/json; charset=utf-8' }
    if ($null -ne $Body) { $args.Body = [Text.Encoding]::UTF8.GetBytes(($Body | ConvertTo-Json -Depth 10 -Compress)) }
    $response = Invoke-RestMethod @args
    if ($response.code -ne 'OK') { throw "API failed: $($response.code)" }
    return $response.data
}
$admin = Invoke-Api POST '/api/auth/login' @{username=$AdminUsername;password=$AdminPassword} '' ''
$category = Invoke-Api POST '/api/admin/categories' @{name="load_$RunId"} $admin.token ''
$product = Invoke-Api POST '/api/admin/products' @{categoryId=$category.id;name="load_product_$RunId";description='Dedicated performance test product';price=10.00;active=$true} $admin.token ''
$null = Invoke-Api POST "/api/admin/products/$($product.id)/stock" @{delta=($Users*100)} $admin.token "seed_stock_$RunId"
$rows = for ($i=1; $i -le $Users; $i++) {
    $username = "load_${RunId}_$i"
    $password = 'LoadTest123!'
    $null = Invoke-Api POST '/api/auth/register' @{username=$username;password=$password} '' ''
    $login = Invoke-Api POST '/api/auth/login' @{username=$username;password=$password} '' ''
    $address = Invoke-Api POST '/api/addresses' @{recipient="Load user $i";phone='13800138000';detail='Performance test address';isDefault=$true} $login.token ''
    [PSCustomObject]@{username=$username;password=$password;token=$login.token;addressId=$address.id;productId=$product.id}
}
$parent = Split-Path $OutputFile -Parent
if ($parent) { New-Item -ItemType Directory -Force -Path $parent | Out-Null }
# UTF-8 without BOM avoids a BOM becoming part of the first CSV column in JMeter.
$csv = $rows | ConvertTo-Csv -NoTypeInformation
[IO.File]::WriteAllLines([IO.Path]::GetFullPath($OutputFile),$csv,(New-Object Text.UTF8Encoding($false)))
Write-Output "Created $Users users and product $($product.id). CSV: $OutputFile"
Write-Output 'Tokens expire after 12 hours. Keep this file private. Use a fresh RunId for another dataset.'
