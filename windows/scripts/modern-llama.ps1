# Dot-source before Swift/native builds. Generates new artifacts, never edits
# SwiftPM checkouts or deployed dependencies.
function Initialize-ModernLlama {
    $repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
    $json = & python.exe (Join-Path $repoRoot 'scripts/run.py') 'windows/scripts/prepare-modern-llama.py'
    if ($LASTEXITCODE -ne 0) { throw 'Modern llama.cpp preparation failed.' }
    $prepared = ($json -join "`n") | ConvertFrom-Json
    $env:ZEROCON_CONVERTER_SOURCE = $prepared.converter
    $env:AZOOKEY_LLAMA_BACKEND_DIR = Join-Path $prepared.runtime 'llama_cpu'
    $env:PATH = $env:AZOOKEY_LLAMA_BACKEND_DIR + ';' + $env:PATH
    return $prepared
}
