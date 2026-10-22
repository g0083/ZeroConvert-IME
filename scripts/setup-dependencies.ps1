<# Fetch pinned source dependencies and apply the tracked ZeroCon llama patch. #>
param([switch]$ApplyPatchOnly)
$ErrorActionPreference = 'Stop'
$repositoryRoot = Split-Path -Parent $PSScriptRoot

if (-not $ApplyPatchOnly) {
    & git -C $repositoryRoot submodule update --init --recursive
    if ($LASTEXITCODE -ne 0) { throw 'Submodule checkout failed.' }
}

$llamaDirectory = Join-Path $repositoryRoot 'android/app/src/main/cpp/llama.cpp'
$patchFile = Join-Path $repositoryRoot 'patches/llama.cpp/zerocon-compat.patch'
& git -C $llamaDirectory apply --reverse --check -- $patchFile 2>$null
if ($LASTEXITCODE -eq 0) {
    Write-Host 'ZeroCon llama compatibility patch is already applied.'
    exit 0
}
& git -C $llamaDirectory apply --check -- $patchFile
if ($LASTEXITCODE -ne 0) { throw 'Patch does not match this llama checkout; existing changes were retained.' }
& git -C $llamaDirectory apply -- $patchFile
if ($LASTEXITCODE -ne 0) { throw 'Failed to apply compatibility patch.' }
Write-Host 'Applied ZeroCon llama compatibility patch.'
