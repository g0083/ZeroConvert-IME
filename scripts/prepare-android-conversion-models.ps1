# 変換モデルはAPKに同梱しない（端末のキャッシュ `files/models/` へ取得）。
# このスクリプトは配布元（GitHub リリースの model assets）用の canonical
# ストアがカタログと一致することだけを検証する。Windows 側の
# windows/scripts/sync-distribution-models.ps1 と同じ前提。
[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot
$catalog = Get-Content -Raw -Encoding UTF8 (Join-Path $root 'windows/deployment/model-catalog.json') | ConvertFrom-Json
$canonical = Join-Path $root 'artifacts/models/zerocon'
New-Item -ItemType Directory -Force -Path $canonical | Out-Null
foreach ($model in $catalog.models) {
    $target = Join-Path $canonical $model.filename
    if (-not (Test-Path -LiteralPath $target -PathType Leaf)) {
        throw "Matching model missing: $($model.filename) (place it in $canonical, then upload to the GitHub release tagged '$($catalog.releaseTag)')"
    }
    if ((Get-Item -LiteralPath $target).Length -ne $model.bytes -or
        (Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash -ne $model.sha256) {
        throw "Canonical model differs from catalog: $target"
    }
    Write-Host "Android release model ready: $($model.name) -> $target"
}
Write-Host "Note: models are downloaded on device; nothing is copied into APK assets."
