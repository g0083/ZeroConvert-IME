$ErrorActionPreference = 'Stop'
$assistRepo = Split-Path $PSScriptRoot
$assistAssets = Join-Path $assistRepo 'android/app/src/main/assets'
& node (Join-Path $assistRepo 'scripts/generate-android-assist-vocabulary.mjs')
if ($LASTEXITCODE -ne 0) { throw 'Assist vocabulary generation failed.' }
& node (Join-Path $assistRepo 'scripts/generate-android-keyboard-typos.mjs')
if ($LASTEXITCODE -ne 0) { throw 'Keyboard typo dictionary generation failed.' }
$legacyCorrector = Join-Path $assistAssets 'models/zerocon-model-corrector.gguf'
if (Test-Path -LiteralPath $legacyCorrector) {
    Remove-Item -LiteralPath $legacyCorrector -Force
}
& node (Join-Path $assistRepo 'windows/scripts/generate-reverse-dictionary.mjs') --source (Join-Path $assistAssets 'dictionary/louds') --output (Join-Path $assistAssets 'dictionary/reverse-v2.bin')
if ($LASTEXITCODE -ne 0) { throw 'Reverse dictionary generation failed.' }
Get-FileHash -Algorithm SHA256 -LiteralPath (Join-Path $assistAssets 'dictionary/reverse-v2.bin')
