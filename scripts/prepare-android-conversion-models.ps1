# Copy verified conversion weights; retain retired local assets outside new APKs.
[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot
$catalog = Get-Content -Raw -Encoding UTF8 (Join-Path $root 'windows/deployment/model-catalog.json') | ConvertFrom-Json
$destination = Join-Path $root 'android/app/src/main/assets/models'
New-Item -ItemType Directory -Force -Path $destination | Out-Null
foreach ($model in $catalog.models | Where-Object { $_.id -like 'nano_v4_*' }) {
    $candidates = @(
        (Join-Path $root "artifacts/models/zerocon/$($model.filename)"),
        (Join-Path $root "artifacts/models/$($model.filename)")
    )
    $source = $candidates | Where-Object {
        (Test-Path -LiteralPath $_ -PathType Leaf) -and
        (Get-FileHash -LiteralPath $_ -Algorithm SHA256).Hash -eq $model.sha256
    } | Select-Object -First 1
    if (-not $source) { throw "Matching model missing: $($model.filename)" }
    $target = Join-Path $destination $model.filename
    Copy-Item -LiteralPath $source -Destination $target -Force
    if ((Get-Item -LiteralPath $target).Length -ne $model.bytes -or
        (Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash -ne $model.sha256) {
        throw "Copied model differs from catalog: $target"
    }
    Write-Host "Android model ready: $($model.name)"
}
