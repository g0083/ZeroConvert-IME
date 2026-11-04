# Compile the bounded CPU keyboard worker from source; never modify a live EXE.
[CmdletBinding()]
param([string]$RuntimeDirectory)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
Import-Module (Join-Path $PSScriptRoot 'distribution.psm1')
$repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
if (-not $RuntimeDirectory) { $RuntimeDirectory = Join-Path $repoRoot 'artifacts/runtime/windows/llama_cpu' }
$sourceRoot = Join-Path $repoRoot 'windows/native/keyboard-prior'
$inputs = @(Get-ChildItem -LiteralPath $sourceRoot -File | Sort-Object Name)
$content = ($inputs | ForEach-Object { $_.Name + ':' + (Get-ArtifactHash $_.FullName) }) -join "`n"
foreach ($name in @('llama.dll','llama.lib','ggml-cpu.dll')) {
    $content += "`n" + $name + ':' + (Get-ArtifactHash (Join-Path $RuntimeDirectory $name))
}
$algorithm = [Security.Cryptography.SHA256]::Create()
try { $key = [BitConverter]::ToString($algorithm.ComputeHash([Text.Encoding]::UTF8.GetBytes($content))).Replace('-','').ToLowerInvariant() }
finally { $algorithm.Dispose() }
$output = Join-Path $repoRoot ('windows/target/keyboard-prior/' + $key)
$executable = Join-Path $output 'keyboard-prior.exe'
if (Test-Path -LiteralPath $output) {
    $record = Get-Content -Raw -LiteralPath (Join-Path $output 'build.json') | ConvertFrom-Json
    if ((Get-ArtifactHash $executable) -ne $record.executable_sha256) { throw 'Keyboard worker build cache changed.' }
} else {
    & python.exe (Join-Path $repoRoot 'scripts/run.py') 'windows/native/keyboard-prior/build.py' --output $output --runtime $RuntimeDirectory | Out-Host
    if ($LASTEXITCODE -ne 0) { throw 'Keyboard worker build failed.' }
}
return $executable
