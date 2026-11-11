# Run separately from the trial IME. The existing bundled llama runtime may be
# too old for PLaMo 3; explicitly select a recent upstream llama-server.exe.
[CmdletBinding()]
param(
    [string]$LlamaServerPath = '',
    [string]$ModelPath = '',
    [int]$GpuLayers = 0,
    [switch]$DownloadModel
)
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
if (-not $LlamaServerPath) {
    $LlamaServerPath = Join-Path $repoRoot 'artifacts/tools/plamo-llama-b12125/llama-server.exe'
    if (-not (Test-Path -LiteralPath $LlamaServerPath -PathType Leaf)) {
        throw 'Specify -LlamaServerPath with a recent upstream llama-server.exe supporting PLaMo 3.'
    }
}
if (-not $ModelPath) {
    $ModelPath = Join-Path $repoRoot 'artifacts/models/plamo-3-translate-2b/plamo-3-translate-2b-Q4_K_M.gguf'
}
$server = (Resolve-Path -LiteralPath $LlamaServerPath).Path
if (-not (Test-Path -LiteralPath $ModelPath -PathType Leaf)) {
    if (-not $DownloadModel) { throw "Model not found: $ModelPath. Use -DownloadModel to download the official 2B Q4_K_M model (about 1.81 GB)." }
    $modelDirectory = Split-Path $ModelPath -Parent
    New-Item -ItemType Directory -Force -Path $modelDirectory | Out-Null
    $partial = "$ModelPath.downloading"
    Invoke-WebRequest -Uri 'https://huggingface.co/pfnet/plamo-3-translate-2b-GGUF/resolve/4f672106b847200bfdc0f6af6c321cd94eba1aed/plamo-3-translate-2b-Q4_K_M.gguf' -OutFile $partial
    if ((Get-FileHash -LiteralPath $partial -Algorithm SHA256).Hash -ne '1ffd9073afcc70e35ccc942428aa2b73d7aa2b9ea779bf504b775369e0276c13') {
        throw "Official model checksum mismatch: $partial"
    }
    Move-Item -LiteralPath $partial -Destination $ModelPath
}
$model = (Resolve-Path -LiteralPath $ModelPath).Path
$logDirectory = Join-Path $repoRoot 'artifacts/logs/translation'
New-Item -ItemType Directory -Force -Path $logDirectory | Out-Null
Write-Host "PLaMo 3 Translate 2B: http://127.0.0.1:18092 (close this process to stop)"
Write-Host "Model SHA256: $((Get-FileHash -LiteralPath $model -Algorithm SHA256).Hash)"
# /completion receives PFML3 directly; no chat template or assistant prefill.
& $server -m $model --host 127.0.0.1 --port 18092 --alias plamo-3-translate-2b -c 4096 -ngl $GpuLayers --parallel 1 --log-file (Join-Path $logDirectory 'llama-server.log')
if ($LASTEXITCODE -ne 0) { throw "Translation server exited with code $LASTEXITCODE. Use a llama.cpp build supporting PLaMo 3 and inspect artifacts/logs/translation/llama-server.log." }
