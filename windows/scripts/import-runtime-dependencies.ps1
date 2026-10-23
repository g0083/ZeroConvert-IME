# One-time migration of redistributable native engines; never imports app binaries or settings.
[CmdletBinding()]
param([Parameter(Mandatory)][string]$SourceDirectory, [string]$DestinationDirectory)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
Import-Module (Join-Path $PSScriptRoot 'distribution.psm1') -Force
$repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
if (-not $DestinationDirectory) { $DestinationDirectory = Join-Path $repoRoot 'artifacts/runtime/windows' }
if (Test-Path -LiteralPath $DestinationDirectory) { throw 'Dependency destination already exists; choose a new directory to refresh dependencies.' }
$source = (Resolve-Path -LiteralPath $SourceDirectory).ProviderPath
New-Item -ItemType Directory -Path $DestinationDirectory -Force | Out-Null
$files = @()
foreach ($engine in @('llama_cpu', 'llama_vulkan')) {
    $engineRoot = Get-ArtifactPath $source $engine
    if (-not (Test-Path -LiteralPath (Join-Path $engineRoot 'llama-server.exe'))) { throw "Engine missing: $engine" }
    foreach ($entry in Get-ArtifactEntries $engineRoot) { $files += "$engine/$($entry.path)" }
}
foreach ($file in Get-ChildItem -LiteralPath $source -File) {
    if ($file.Name -match '^(concrt140|msvcp140(_[a-z0-9_]+)?|vccorlib140|vcruntime140(_[a-z0-9_]+)?)\.dll$' -or
        $file.Name -eq 'plutil.exe') { $files += $file.Name }
}
foreach ($relative in $files) {
    $from = Get-ArtifactPath $source $relative
    $to = Get-ArtifactPath $DestinationDirectory $relative
    New-Item -ItemType Directory -Path (Split-Path -Parent $to) -Force | Out-Null
    $before = Get-ArtifactHash $from
    Copy-Item -LiteralPath $from -Destination $to
    if ((Get-ArtifactHash $to) -ne $before -or (Get-ArtifactHash $from) -ne $before) { throw "Dependency changed during import: $relative" }
}
Write-ArtifactJson (Join-Path $DestinationDirectory 'dependencies-manifest.json') @{
    schemaVersion = 1; importedAt = [DateTimeOffset]::Now.ToString('o')
    sourceDirectory = $source; files = @(Get-ArtifactEntries $DestinationDirectory)
}
Write-Host "Dependencies imported: $DestinationDirectory"
