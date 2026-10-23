# Canonical distribution models; old experiments and running IME files are preserved.
[CmdletBinding()]
param([string]$RuntimeDirectory)
$ErrorActionPreference = 'Stop'
$repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
if (-not $RuntimeDirectory) { $RuntimeDirectory = Join-Path $repoRoot 'ZeroConvertIME-windows' }
$catalog = Get-Content -Raw -LiteralPath (Join-Path $repoRoot 'windows\deployment\model-catalog.json') | ConvertFrom-Json
$canonical = Join-Path $repoRoot 'artifacts\models\zerocon'
New-Item -ItemType Directory -Path $canonical -Force | Out-Null
function Model-Hash([string]$Path) {
    $file = [IO.File]::OpenRead($Path)
    $hash = [Security.Cryptography.SHA256]::Create()
    try { [BitConverter]::ToString($hash.ComputeHash($file)).Replace('-', '').ToLowerInvariant() }
    finally { $hash.Dispose(); $file.Dispose() }
}
foreach ($model in $catalog.models) {
    $destination = Join-Path $canonical $model.filename
    if (-not (Test-Path -LiteralPath $destination -PathType Leaf)) {
        $sources = @(
            (Join-Path $RuntimeDirectory ('models\' + $model.filename)),
            (Join-Path $RuntimeDirectory ('models\' + $model.legacyFilename)),
            (Join-Path $repoRoot ('artifacts\models\' + $model.legacyFilename))
        )
        $source = $sources | Where-Object { (Test-Path -LiteralPath $_ -PathType Leaf) -and (Model-Hash $_) -eq $model.sha256 } | Select-Object -First 1
        if (-not $source) { throw "Matching model not found: $($model.name). See windows/deployment/model-catalog.json." }
        Copy-Item -LiteralPath $source -Destination $destination
    }
    if ((Model-Hash $destination) -ne $model.sha256) { throw "Model hash mismatch: $destination" }
    Write-Host "Model ready: $($model.name) -> $destination"
}
