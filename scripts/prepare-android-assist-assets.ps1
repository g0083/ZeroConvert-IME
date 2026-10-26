param([string]$CorrectorPath)
$ErrorActionPreference = 'Stop'
$assistRepo = Split-Path $PSScriptRoot
if (-not $CorrectorPath) { $CorrectorPath = Join-Path $assistRepo 'artifacts/models/zerocon/zerocon-model-corrector.gguf' }
$assistCatalog = Get-Content -Raw (Join-Path $assistRepo 'windows/deployment/model-catalog.json') | ConvertFrom-Json
$assistSpec = $assistCatalog.models | Where-Object id -eq 'corrector'
if (-not $assistSpec -or (Get-FileHash -Algorithm SHA256 -LiteralPath $CorrectorPath).Hash -ne $assistSpec.sha256) {
    throw 'Corrector model SHA-256 does not match the model catalog.'
}
$assistAssets = Join-Path $assistRepo 'android/app/src/main/assets'
& node (Join-Path $assistRepo 'scripts/generate-android-assist-vocabulary.mjs')
if ($LASTEXITCODE -ne 0) { throw 'Assist vocabulary generation failed.' }
& node (Join-Path $assistRepo 'scripts/generate-android-keyboard-typos.mjs')
if ($LASTEXITCODE -ne 0) { throw 'Keyboard typo dictionary generation failed.' }
$assistModelDir = Join-Path $assistAssets 'models'
New-Item -ItemType Directory -Force -Path $assistModelDir | Out-Null
Copy-Item -LiteralPath $CorrectorPath -Destination (Join-Path $assistModelDir 'zerocon-model-corrector.gguf')
& node (Join-Path $assistRepo 'windows/scripts/generate-reverse-dictionary.mjs') --source (Join-Path $assistAssets 'dictionary/louds') --output (Join-Path $assistAssets 'dictionary/reverse-v2.bin')
if ($LASTEXITCODE -ne 0) { throw 'Reverse dictionary generation failed.' }
Get-FileHash -Algorithm SHA256 -LiteralPath (Join-Path $assistModelDir 'zerocon-model-corrector.gguf'),(Join-Path $assistAssets 'dictionary/reverse-v2.bin')
