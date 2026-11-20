# 変換モデル（ZeroCon Model v4.1）はインストーラー・APKに同梱しない。
# このスクリプトは配布元（GitHub リリースの model assets）へ置く前提で、
# カタログと一致する canonical ストア（artifacts/models/zerocon）の
# 存在・サイズ・SHA-256 を検証するだけを行う。 -RuntimeDirectory 由来の
# 実行中環境には触れない。
[CmdletBinding()]
param([string]$RuntimeDirectory)
$ErrorActionPreference = 'Stop'
$repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$catalog = Get-Content -Raw -LiteralPath (Join-Path $repoRoot 'windows\deployment\model-catalog.json') | ConvertFrom-Json
$canonical = Join-Path $repoRoot 'artifacts\models\zerocon'
New-Item -ItemType Directory -Path $canonical -Force | Out-Null
function Model-Hash([string]$Path) {
    $file = [IO.File]::OpenRead($Path)
    $hash = [Security.Cryptography.SHA256]::Create()
    try { [BitConverter]::ToString($hash.ComputeHash($file)).Replace('-', '').ToLowerInvariant() }
    finally { $hash.Dispose(); $file.Dispose() }
}
$failed = $false
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
    if ((Model-Hash $destination) -ne $model.sha256) {
        Write-Warning "Hash mismatch: $destination"
        $failed = $true
    }
    Write-Host "Model ready: $($model.name) -> $destination"
}
if ($failed) { throw "Catalog models are not all ready. Upload each missing file to the GitHub release tagged '$($catalog.releaseTag)' (or place it in $canonical)." }
$base = $catalog.downloadBaseUrl
if (-not $base) { $base = "https://github.com/g0083/ZeroConvert-IME/releases/download/$($catalog.releaseTag)" }
Write-Host ""
Write-Host "Release assets (download base): $base"
foreach ($model in $catalog.models) {
    Write-Host ("  - {0}  # {1} bytes, sha256 {2}" -f $model.filename, $model.bytes, $model.sha256)
}
