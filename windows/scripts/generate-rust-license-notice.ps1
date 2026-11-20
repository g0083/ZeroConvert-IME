# Generates the Rust crate attribution list for the shipped Windows binaries.
# Run: powershell -NoProfile -ExecutionPolicy Bypass -File windows/scripts/generate-rust-license-notice.ps1
# Re-run whenever Cargo.lock changes; the output is checked into windows/deployment.
[CmdletBinding()]
param([string]$OutputPath)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$windowsRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
if (-not $OutputPath) { $OutputPath = Join-Path $windowsRoot 'deployment/THIRD_PARTY_RUST.md' }
$manifest = Join-Path $windowsRoot 'Cargo.toml'

# Crates that produce a shipped executable or DLL.
$shippedPackages = @('azookey-server', 'azookey-windows', 'frontend', 'launcher', 'ui')

$raw = & cargo metadata --manifest-path $manifest --format-version 1 --offline
if ($LASTEXITCODE -ne 0) { $raw = & cargo metadata --manifest-path $manifest --format-version 1 }
if ($LASTEXITCODE -ne 0) { throw 'cargo metadata failed' }
$metadata = ($raw -join "`n") | ConvertFrom-Json

$packages = @{}
foreach ($package in $metadata.packages) { $packages[$package.id] = $package }
$nodes = @{}
foreach ($node in $metadata.resolve.nodes) { $nodes[$node.id] = $node }

$queue = [Collections.Generic.Queue[string]]::new()
foreach ($package in $metadata.packages) {
    if (-not $package.source -and $package.name -in $shippedPackages) { $queue.Enqueue($package.id) }
}

# Walk normal (non-dev, non-build) dependency edges from the shipped crates.
$visited = @{}
while ($queue.Count -gt 0) {
    $id = $queue.Dequeue()
    if ($visited.ContainsKey($id)) { continue }
    $visited[$id] = $true
    $node = $nodes[$id]
    if (-not $node) { continue }
    foreach ($dep in $node.deps) {
        $normal = @($dep.dep_kinds | Where-Object { $_.kind -ne 'dev' -and $_.kind -ne 'build' })
        if ($normal.Count -gt 0) { $queue.Enqueue($dep.pkg) }
    }
}

$crates = @{}
foreach ($id in $visited.Keys) {
    $package = $packages[$id]
    # Workspace crates are covered by the other sections; proc-macro crates only run at build time.
    if (-not $package.source) { continue }
    if (@($package.targets | Where-Object { $_.kind -contains 'proc-macro' }).Count -gt 0) { continue }
    $license = if ($package.license) { $package.license } else { 'NOASSERTION' }
    $crates["$($package.name) $($package.version)"] = $license
}

$licenseFamilies = $crates.Values | Group-Object | Sort-Object -Property Count -Descending

$builder = [System.Text.StringBuilder]::new()
$null = $builder.AppendLine('# Third-Party Rust Crates (ZeroCon IME for Windows)')
$null = $builder.AppendLine()
$null = $builder.AppendLine('Generated from `windows/Cargo.toml` and `Cargo.lock` by `windows/scripts/generate-rust-license-notice.ps1`.')
$null = $builder.AppendLine('Only normal (non-dev, non-build, non-proc-macro) dependencies reachable from the shipped binaries are listed.')
$null = $builder.AppendLine()
$null = $builder.AppendLine('Do not hand-edit. Regenerate after changing `Cargo.lock`.')
$null = $builder.AppendLine()
$null = $builder.AppendLine('## License summary')
$null = $builder.AppendLine()
$null = $builder.AppendLine('| License expression | Crates |')
$null = $builder.AppendLine('| --- | ---: |')
foreach ($family in $licenseFamilies) {
    $null = $builder.AppendLine("| $($family.Name) | $($family.Count) |")
}
$null = $builder.AppendLine()
$null = $builder.AppendLine('## Crates')
$null = $builder.AppendLine()
$null = $builder.AppendLine('| Crate | License |')
$null = $builder.AppendLine('| --- | --- |')
foreach ($key in ($crates.Keys | Sort-Object)) {
    $null = $builder.AppendLine("| $key | $($crates[$key]) |")
}

$resolved = [IO.Path]::GetFullPath($OutputPath)
[IO.File]::WriteAllText($resolved, $builder.ToString(), [Text.UTF8Encoding]::new($false))
Write-Host "Wrote $resolved ($($crates.Count) crates)"
