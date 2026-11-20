# Build once; both local trial deployment and installers consume this immutable package.
[CmdletBinding()]
param([string]$DependencyDirectory, [switch]$SkipBuild, [string]$SwiftRuntimeDirectory)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
Import-Module (Join-Path $PSScriptRoot 'distribution.psm1') -Force
$repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$windowsRoot = Join-Path $repoRoot 'windows'
if (-not $DependencyDirectory) {
    . (Join-Path $PSScriptRoot 'modern-llama.ps1')
    $modernLlama = Initialize-ModernLlama
    $DependencyDirectory = $modernLlama.runtime
}
$dependencyRoot = (Resolve-Path -LiteralPath $DependencyDirectory).ProviderPath
$dependencies = Get-Content -Encoding UTF8 -Raw -LiteralPath (Join-Path $dependencyRoot 'dependencies-manifest.json') | ConvertFrom-Json
if ($dependencies.schemaVersion -ne 1) { throw 'Unsupported dependency manifest.' }
if (-not $dependencies.PSObject.Properties['llamaRevision'] -or $dependencies.llamaRevision -ne '7d516e2') {
    throw 'The modern Swift ABI requires the b12125 dependency set. Omit -DependencyDirectory to prepare it automatically.'
}
Test-ArtifactEntries $dependencyRoot $dependencies.files
foreach ($entry in $dependencies.files) {
    if ($entry.path -notmatch '^(llama_cpu/|llama_vulkan/|plutil\.exe$|(?:concrt140|msvcp140(?:_[a-z0-9_]+)?|vccorlib140|vcruntime140(?:_[a-z0-9_]+)?)\.dll$)') {
        throw "Unexpected dependency file: $($entry.path)"
    }
}
# The model store is canonical; this build never reads the live trial environment.
& (Join-Path $PSScriptRoot 'sync-distribution-models.ps1') | Out-Host
$packagesRoot = Join-Path $repoRoot 'dist/packages'
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss-fff'
$packageRoot = Join-Path $packagesRoot $stamp
$payloadRoot = Join-Path $packageRoot 'payload'
$previousChannel = $env:AZOOKEY_BUILD_CHANNEL
$previousBuildNumber = $env:AZOOKEY_BUILD_NUMBER
try {
    if (-not $SkipBuild) {
        $env:AZOOKEY_BUILD_CHANNEL = 'validation'
        $env:AZOOKEY_BUILD_NUMBER = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds().ToString()
        Push-Location $windowsRoot
        try {
            & node.exe scripts/sync-version.mjs | Out-Host
            if ($LASTEXITCODE -ne 0) { throw 'Build metadata generation failed.' }
        } finally { Pop-Location }
        $builtFiles = & (Join-Path $PSScriptRoot 'build-installer-sources.ps1') -SwiftRuntimeDirectory $SwiftRuntimeDirectory
    }
    $receipt = Get-Content -Encoding UTF8 -Raw -LiteralPath (Join-Path $windowsRoot 'target/installer-source-build.json') | ConvertFrom-Json
    if ($receipt.assetLayout -ne 4) { throw 'Run without -SkipBuild to generate a complete build receipt.' }
    $builtFiles = [ordered]@{}
    foreach ($entry in $receipt.files.PSObject.Properties) {
        if ((Get-ArtifactHash $entry.Value.path) -ne $entry.Value.sha256) { throw "Cached build changed: $($entry.Name). Run without -SkipBuild." }
        $builtFiles[$entry.Name] = $entry.Value.path
    }
    $metadataFiles = @{ buildInfo = $receipt.buildInfo.path; installer = $receipt.installerMetadata.path }
    foreach ($entry in @($receipt.buildInfo, $receipt.installerMetadata)) {
        if ((Get-ArtifactHash $entry.path) -ne $entry.sha256) { throw 'Build metadata changed. Run without -SkipBuild.' }
    }
    New-Item -ItemType Directory -Path $payloadRoot -Force | Out-Null
    function Copy-PayloadFile([string]$Source, [string]$RelativePath) {
        $destination = Join-Path $payloadRoot $RelativePath
        New-Item -ItemType Directory -Path (Split-Path -Parent $destination) -Force | Out-Null
        $before = Get-ArtifactHash $Source
        Copy-Item -LiteralPath $Source -Destination $destination -Force
        $after = Get-ArtifactHash $Source
        if ($before -ne $after -or $before -ne (Get-ArtifactHash $destination)) {
            throw "Runtime changed during snapshot; retry after deployment finishes: $Source"
        }
    }

    foreach ($entry in $dependencies.files) {
        Copy-PayloadFile (Get-ArtifactPath $dependencyRoot $entry.path) $entry.path
    }
    Test-ArtifactEntries $payloadRoot $dependencies.files
    # 変換モデルは配布物に同梱しない。英字辞書・翻訳モデルと同じく、利用者が
    # cache（%LOCALAPPDATA%\ZeroConIME\models）を持たない場合は設定画面から
    # model-catalog.json の GitHub リリース固定アセットをダウンロードする。
    # 既定モデル nano_v4_1_iq4_emb8 は必須として扱う（docs/model-assets.md）。
    # Use maintained distribution defaults, never this user's live settings or learning.
    # The translation model is not bundled: enabling 翻訳機能 in the settings app
    # downloads the pinned PLaMo 3 GGUF from Hugging Face on demand.
    foreach ($file in Get-ChildItem -LiteralPath (Join-Path $windowsRoot 'deployment') -File) {
        if ($file.Extension -in @('.ps1', '.bat', '.md', '.json', '.txt')) {
            Copy-PayloadFile $file.FullName $file.Name
        }
    }

    & python (Join-Path $repoRoot 'scripts/prepare_dictionary.py') --output (Join-Path $payloadRoot 'Dictionary')
    if ($LASTEXITCODE -ne 0) { throw 'Default dictionary preparation failed.' }
    $emojiSource = Join-Path $windowsRoot 'server-swift\azooKey_emoji_dictionary_storage\EmojiDictionary'
    foreach ($file in Get-ChildItem -LiteralPath $emojiSource -File -Recurse) {
        $relative = $file.FullName.Substring($emojiSource.TrimEnd('\').Length + 1)
        Copy-PayloadFile $file.FullName (Join-Path 'EmojiDictionary' $relative)
    }
    if ($builtFiles) {
        foreach ($name in $builtFiles.Keys) { Copy-PayloadFile $builtFiles[$name] $(if ($name -eq 'azookey_windows.dll') { 'azookey.dll' } elseif ($name -eq 'x86\azookey_windows.dll') { 'azookey32.dll' } else { $name }) }
    }
    foreach ($entry in $receipt.files.PSObject.Properties) {
        $name = if ($entry.Name -eq 'azookey_windows.dll') { 'azookey.dll' } elseif ($entry.Name -eq 'x86\azookey_windows.dll') { 'azookey32.dll' } else { $entry.Name }
        if ((Get-ArtifactHash (Join-Path $payloadRoot $name)) -ne $entry.Value.sha256) { throw "Build changed during packaging: $name" }
    }
    Copy-PayloadFile $builtFiles['frontend.exe'] 'azookey-updater-helper.exe'
    # Keep the installer's license page in step with the repository root LICENSE file.
    Copy-Item -LiteralPath (Join-Path $repoRoot 'LICENSE') -Destination (Join-Path $packageRoot 'USE-TERMS.txt') -Force
    if ((Get-ArtifactHash (Join-Path $packageRoot 'USE-TERMS.txt')) -ne (Get-ArtifactHash (Join-Path $repoRoot 'LICENSE'))) { throw 'Installer license page does not match the repository LICENSE.' }
    Copy-PayloadFile (Join-Path $windowsRoot 'installer/Azookey Startup.xml') '.azookey-startup-task.xml'
    Copy-PayloadFile $metadataFiles.buildInfo 'build-info.json'
    Copy-Item -LiteralPath $metadataFiles.installer -Destination (Join-Path $packageRoot 'AppVersion.iss')
    $info = Get-Content -Encoding UTF8 -Raw -LiteralPath (Join-Path $payloadRoot 'build-info.json') | ConvertFrom-Json
    $manifest = [ordered]@{
        schemaVersion = 1; packageType = 'prepared-windows-distribution'
        createdAt = [DateTimeOffset]::Now.ToString('o'); build = $info
        sourceBuild = (-not $SkipBuild)
        dependencyManifestSha256 = Get-ArtifactHash (Join-Path $dependencyRoot 'dependencies-manifest.json')
        files = @(Get-ArtifactEntries $payloadRoot)
    }
    Write-ArtifactJson (Join-Path $payloadRoot 'package-manifest.json') $manifest
    Write-ArtifactJson (Join-Path $packageRoot 'package.json') @{
        schemaVersion = 1
        manifestSha256 = Get-ArtifactHash (Join-Path $payloadRoot 'package-manifest.json')
        installerMetadataSha256 = Get-ArtifactHash (Join-Path $packageRoot 'AppVersion.iss')
    }
    $null = Read-PreparedPackage $packageRoot
    Write-ArtifactJson (Join-Path $packagesRoot 'latest.json') @{ directory = $stamp }
    Write-Host "Prepared package: $packageRoot"
    Write-Host "Build: $($info.version) / $($info.channel)"
    return $packageRoot
} finally {
    $env:AZOOKEY_BUILD_CHANNEL = $previousChannel
    $env:AZOOKEY_BUILD_NUMBER = $previousBuildNumber
}
