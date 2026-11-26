# 変換モデル（ZeroCon Model v4.2）はインストーラー・APKに同梱しない。
# このスクリプトは配布元（GitHub リリースの model assets）へ置く前提で、
# カタログと一致する canonical ストア（artifacts/models/zerocon）の
# 存在・サイズ・SHA-256 を検証するだけを行う。 -RuntimeDirectory 由来の
# 実行中環境には触れない。
[CmdletBinding()]
param([string]$RuntimeDirectory)
$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot 'distribution.psm1') -Force
$repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$catalog = Get-Content -Raw -LiteralPath (Join-Path $repoRoot 'windows\deployment\model-catalog.json') | ConvertFrom-Json
$canonical = Join-Path $repoRoot 'artifacts\models\zerocon'
New-Item -ItemType Directory -Path $canonical -Force | Out-Null
$failed = $false
$presentModels = New-Object 'Collections.Generic.List[object]'
foreach ($model in $catalog.models) {
    $destination = Join-Path $canonical $model.filename
    if (-not (Test-Path -LiteralPath $destination -PathType Leaf)) {
        Write-Warning "Missing model: $($model.filename) ($($model.name)). See windows/deployment/model-catalog.json."
        $failed = $true
        continue
    }
    $actual = (Get-Item -LiteralPath $destination).Length
    if ($actual -ne $model.bytes) {
        Write-Warning "Size mismatch: $($model.filename) (expected $($model.bytes), actual $actual)"
        $failed = $true
    }
    $presentModels.Add([pscustomobject]@{ model = $model; path = $destination })
}
if ($presentModels.Count -gt 0) {
    [string[]]$pathsToHash = @($presentModels | ForEach-Object { $_.path })
    $hashes = [ZeroConArtifactCrypto]::HashFilesParallel($pathsToHash)
    for ($i = 0; $i -lt $presentModels.Count; $i++) {
        $item = $presentModels[$i]
        if ($hashes[$i] -ne $item.model.sha256) {
            Write-Warning "Hash mismatch: $($item.path)"
            $failed = $true
        }
        Write-Host "Model ready: $($item.model.name) -> $($item.path)"
    }
}
if ($failed) { throw "Catalog models are not all ready. Upload each missing file to the GitHub release tagged '$($catalog.releaseTag)' (or place it in $canonical)." }
$base = $catalog.downloadBaseUrl
if (-not $base) { $base = "https://github.com/g0083/ZeroConvert-IME/releases/download/$($catalog.releaseTag)" }
Write-Host ""
Write-Host "Release assets (download base): $base"
foreach ($model in $catalog.models) {
    Write-Host ("  - {0}  # {1} bytes, sha256 {2}" -f $model.filename, $model.bytes, $model.sha256)
}
