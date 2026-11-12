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
$signKeys = @{}
$utf8 = New-Object Text.UTF8Encoding($false)
# Requests that carry a token are signed with the signKey from the same login (docs/API.md). Paths here have no query string.
function Get-SignatureHeaders([string]$Method,[string]$Path,[byte[]]$BodyBytes,[string]$Token) {
    if ($Path.Contains('?')) { throw 'Get-SignatureHeaders does not canonicalize query strings.' }
    $timestamp = [string][DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
    $nonce = [Guid]::NewGuid().ToString()
    if ($null -eq $BodyBytes) { $BodyBytes = [byte[]]@() }
    $sha = [Security.Cryptography.SHA256]::Create()
    $bodyHash = -join ($sha.ComputeHash($BodyBytes) | ForEach-Object { $_.ToString('x2') })
    $canonical = ($Method.ToUpperInvariant(),$Path,'',$timestamp,$nonce,$bodyHash) -join "`n"
    $hmac = New-Object Security.Cryptography.HMACSHA256 -ArgumentList (,$utf8.GetBytes($signKeys[$Token]))
    $signature = [Convert]::ToBase64String($hmac.ComputeHash($utf8.GetBytes($canonical)))
    return @{ 'X-Timestamp'=$timestamp; 'X-Nonce'=$nonce; 'X-Signature'=$signature }
}
function Invoke-Api([string]$Method,[string]$Path,$Body,[string]$Token,[string]$Key) {
    $headers = @{}
    $bytes = $null
    if ($null -ne $Body) { $bytes = $utf8.GetBytes(($Body | ConvertTo-Json -Depth 10 -Compress)) }
    if ($Token) {
        $headers.Authorization = "Bearer $Token"
        (Get-SignatureHeaders $Method $Path $bytes $Token).GetEnumerator() | ForEach-Object { $headers[$_.Key] = $_.Value }
    }
    if ($Key) { $headers['Idempotency-Key'] = $Key }
    $args = @{ Method=$Method; Uri="$BaseUrl$Path"; Headers=$headers; ContentType='application/json; charset=utf-8' }
    if ($null -ne $bytes) { $args.Body = $bytes }
    $response = Invoke-RestMethod @args
    if ($response.code -ne 'OK') { throw "API failed: $($response.code)" }
    return $response.data
}
function Connect-User([string]$Username,[string]$Password) {
    $login = Invoke-Api POST '/api/auth/login' @{username=$Username;password=$Password} '' ''
    $signKeys[$login.token] = $login.signKey
    return $login
}
$admin = Connect-User $AdminUsername $AdminPassword
$category = Invoke-Api POST '/api/admin/categories' @{name="load_$RunId"} $admin.token ''
$product = Invoke-Api POST '/api/admin/products' @{categoryId=$category.id;name="load_product_$RunId";description='Dedicated performance test product';price=10.00;active=$true} $admin.token ''
$null = Invoke-Api POST "/api/admin/products/$($product.id)/stock" @{delta=($Users*100)} $admin.token "seed_stock_$RunId"
$rows = for ($i=1; $i -le $Users; $i++) {
    $username = "load_${RunId}_$i"
    $password = 'LoadTest123!'
    $null = Invoke-Api POST '/api/auth/register' @{username=$username;password=$password} '' ''
    $login = Connect-User $username $password
    $address = Invoke-Api POST '/api/addresses' @{recipient="Load user $i";phone='13800138000';detail='Performance test address';isDefault=$true} $login.token ''
    [PSCustomObject]@{username=$username;password=$password;token=$login.token;addressId=$address.id;productId=$product.id;signKey=$login.signKey}
}
$parent = Split-Path $OutputFile -Parent
if ($parent) { New-Item -ItemType Directory -Force -Path $parent | Out-Null }
# UTF-8 without BOM avoids a BOM becoming part of the first CSV column in JMeter.
$csv = $rows | ConvertTo-Csv -NoTypeInformation
[IO.File]::WriteAllLines([IO.Path]::GetFullPath($OutputFile),$csv,(New-Object Text.UTF8Encoding($false)))
Write-Output "Created $Users users and product $($product.id). CSV: $OutputFile"
Write-Output 'Tokens expire after 12 hours; the signKey column signs requests for the token on the same row. Keep this file private. Use a fresh RunId for another dataset.'
