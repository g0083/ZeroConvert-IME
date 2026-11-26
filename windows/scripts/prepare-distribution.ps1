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
foreach ($entry in $dependencies.files) {
    if ($entry.path -notmatch '^(llama_cpu/|llama_vulkan/|plutil\.exe$|(?:concrt140|msvcp140(?:_[a-z0-9_]+)?|vccorlib140|vcruntime140(?:_[a-z0-9_]+)?)\.dll$)') {
        throw "Unexpected dependency file: $($entry.path)"
    }
}
# llama の配布対象は llama-server 起動に必要な閉包だけに限定する。
# 上流 zip は各バックエンドごとに対応 CLI/bench ツールを丸ごと含むが
# (
#   llama-server.exe -> llama-server-impl.dll ->
#     llama-common.dll / mtmd.dll / llama.dll / ggml.dll / ggml-base.dll -> libomp.dll
#   動的ロード: ggml-cpu*.dll, ggml-vulkan.dll および旧 ggml.dll が import する ggml-rpc.dll
# ) それ以外は実行時に読み込まれない。
$essentialLlamaLeaf = @(
    'llama-server.exe', 'llama-server-impl.dll', 'llama-common.dll', 'llama.dll', 'mtmd.dll',
    'ggml.dll', 'ggml-base.dll', 'ggml-rpc.dll', 'libomp.dll', 'LICENSE-LLVM-OpenMP',
    'ggml-base.def', 'ggml-base.lib', 'ggml.def', 'ggml.lib', 'llama.def', 'llama.lib'
)
function Test-DistributableDependency([string]$Path) {
    if ($Path -notmatch '^(llama_cpu|llama_vulkan)/') { return $true }
    $leaf = Split-Path -Leaf $Path
    if ($leaf -in $essentialLlamaLeaf) { return $true }
    if ($leaf -like 'ggml-cpu*.dll') { return $true }
    if ($leaf -eq 'ggml-vulkan.dll') { return $true }
    return $false
}
$distributableDependencies = @($dependencies.files | Where-Object { Test-DistributableDependency $_.path })
if (-not $distributableDependencies.Count) { throw 'No distributable llama files selected.' }
Test-ArtifactEntries $dependencyRoot $distributableDependencies
foreach ($required in @('llama_cpu/llama-server.exe','llama_vulkan/llama-server.exe')) {
    if ($required -notin @($distributableDependencies.path)) { throw "Essential runtime missing: $required" }
}
# CPU/Vulkan は単一の llama/ に統合する。重複する共通 DLL を片方だけ
# 格納し、差分 ggml-vulkan.dll だけを追加する。両 backend の
# llama-server.exe は同一バイナリで、違いは -ngl 層数だけ。
$cpuEntries = @($distributableDependencies | Where-Object { $_.path -match '^llama_cpu/' })
$vulkanEntries = @($distributableDependencies | Where-Object { $_.path -match '^llama_vulkan/' })
$nonLlamaEntries = @($distributableDependencies | Where-Object { $_.path -notmatch '^(llama_cpu|llama_vulkan)/' })
$cpuByLeaf = @{}
foreach ($entry in $cpuEntries) { $cpuByLeaf[(Split-Path -Leaf $entry.path)] = $entry }
foreach ($entry in $vulkanEntries) {
    $leaf = Split-Path -Leaf $entry.path
    if ($cpuByLeaf.ContainsKey($leaf) -and $leaf -ne 'ggml-vulkan.dll') {
        if ($cpuByLeaf[$leaf].sha256 -ne $entry.sha256) { throw "Divergent shared llama file: $leaf" }
    }
}
$payloadDependencies = @()
foreach ($entry in $nonLlamaEntries) {
    $payloadDependencies += [pscustomobject]@{ path = $entry.path; bytes = $entry.bytes; sha256 = $entry.sha256; sourcePath = $entry.path }
}
foreach ($entry in $cpuEntries) {
    $leaf = Split-Path -Leaf $entry.path
    $payloadDependencies += [pscustomobject]@{ path = "llama/$leaf"; bytes = $entry.bytes; sha256 = $entry.sha256; sourcePath = $entry.path }
}
$vulkanDllEntries = @($vulkanEntries | Where-Object { (Split-Path -Leaf $_.path) -eq 'ggml-vulkan.dll' })
if (-not $vulkanDllEntries.Count) { throw 'Essential runtime missing: llama_vulkan/ggml-vulkan.dll' }
foreach ($entry in $vulkanDllEntries) {
    $payloadDependencies += [pscustomobject]@{ path = 'llama/ggml-vulkan.dll'; bytes = $entry.bytes; sha256 = $entry.sha256; sourcePath = $entry.path }
}
foreach ($required in @('llama/llama-server.exe','llama/ggml-vulkan.dll')) {
    if ($required -notin @($payloadDependencies.path)) { throw "Essential runtime missing: $required" }
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
    $receiptProperties = @($receipt.files.PSObject.Properties)
    foreach ($entry in $receiptProperties) {
        $builtFiles[$entry.Name] = $entry.Value.path
    }
    if ($SkipBuild) {
        $checkPaths = [string[]]@($receiptProperties | ForEach-Object { $_.Value.path })
        $actualHashes = [ZeroConArtifactCrypto]::HashFilesParallel($checkPaths)
        for ($i = 0; $i -lt $receiptProperties.Count; $i++) {
            if ($actualHashes[$i] -ne $receiptProperties[$i].Value.sha256) {
                throw "Cached build changed: $($receiptProperties[$i].Name). Run without -SkipBuild."
            }
        }
    }
    $metadataFiles = @{ buildInfo = $receipt.buildInfo.path; installer = $receipt.installerMetadata.path }
    $metaHashes = [ZeroConArtifactCrypto]::HashFilesParallel([string[]]@($receipt.buildInfo.path, $receipt.installerMetadata.path))
    if ($metaHashes[0] -ne $receipt.buildInfo.sha256 -or $metaHashes[1] -ne $receipt.installerMetadata.sha256) {
        throw 'Build metadata changed. Run without -SkipBuild.'
    }
    New-Item -ItemType Directory -Path $payloadRoot -Force | Out-Null
    $manifestByPath = New-Object 'Collections.Specialized.OrderedDictionary' ([StringComparer]::OrdinalIgnoreCase)
    function Copy-PayloadFile([string]$Source, [string]$RelativePath, [string]$ExpectedSha256 = $null) {
        $destination = Join-Path $payloadRoot $RelativePath
        $copied = Copy-ArtifactFileVerified $Source $destination $ExpectedSha256
        $normalizedPath = $RelativePath.Replace('\', '/')
        $manifestByPath[$normalizedPath] = [pscustomobject]@{
            path = $normalizedPath
            bytes = $copied.bytes
            sha256 = $copied.sha256
        }
    }

    foreach ($entry in $payloadDependencies) {
        Copy-PayloadFile (Get-ArtifactPath $dependencyRoot $entry.sourcePath) $entry.path $entry.sha256
    }
    # 変換モデルは配布物に同梱しない。英字辞書・翻訳モデルと同じく、利用者が
    # cache（%LOCALAPPDATA%\ZeroConIME\models）を持たない場合は設定画面から
    # model-catalog.json の GitHub リリース固定アセットをダウンロードする。
    # 既定モデル standard_v4_2_iq4_emb8 は必須として扱う（docs/model-assets.md）。
    # Use maintained distribution defaults, never this user's live settings or learning.
    # The translation model is not bundled: enabling 翻訳機能 in the settings app
    # downloads the pinned PLaMo 3 GGUF from Hugging Face on demand.
    foreach ($file in Get-ChildItem -LiteralPath (Join-Path $windowsRoot 'deployment') -File) {
        if ($file.Extension -in @('.ps1', '.bat', '.md', '.json', '.txt')) {
            Copy-PayloadFile $file.FullName $file.Name
        }
    }
    foreach ($notice in @('LEGAL', 'LICENSE-2.0.txt', 'Wikidata.txt')) {
        Copy-PayloadFile (Join-Path $repoRoot "data/dictionary/AdditionalDictionaryNotices/$notice") "AdditionalDictionaryNotices/$notice"
    }

    # 日本語辞書は同梱しない（初回起動時に設定アプリが取得して展開する）。
    # 配布物に含めない辞書は `windows/deployment/dictionary-catalog.json` の
    # 固定アセットとして GitHub リリースへ置く（scripts/build-dictionary-asset.py）。
    # 旧パッケージから残った Dictionary/ は apply 時の差分削除に任せる。
    $bundledDictionary = @(Get-ChildItem -LiteralPath $payloadRoot -Filter 'Dictionary' -Directory -ErrorAction SilentlyContinue)
    if ($bundledDictionary.Count) { throw 'Dictionary must not be bundled in the payload.' }
    $emojiSource = Join-Path $windowsRoot 'server-swift\azooKey_emoji_dictionary_storage\EmojiDictionary'
    foreach ($file in Get-ChildItem -LiteralPath $emojiSource -File -Recurse) {
        $relative = $file.FullName.Substring($emojiSource.TrimEnd('\').Length + 1)
        Copy-PayloadFile $file.FullName (Join-Path 'EmojiDictionary' $relative)
    }
    if ($builtFiles) {
        foreach ($name in $builtFiles.Keys) {
            $destName = if ($name -eq 'azookey_windows.dll') { 'azookey.dll' } elseif ($name -eq 'x86\azookey_windows.dll') { 'azookey32.dll' } else { $name }
            Copy-PayloadFile $builtFiles[$name] $destName $receipt.files.$name.sha256
        }
    }
    Copy-PayloadFile $builtFiles['frontend.exe'] 'azookey-updater-helper.exe' $receipt.files.'frontend.exe'.sha256
    # Keep the installer's license page in step with the repository root LICENSE file.
    $licenseCopied = Copy-ArtifactFileVerified (Join-Path $repoRoot 'LICENSE') (Join-Path $packageRoot 'USE-TERMS.txt')
    $null = $licenseCopied
    Copy-PayloadFile (Join-Path $windowsRoot 'installer/Azookey Startup.xml') '.azookey-startup-task.xml'
    Copy-PayloadFile $metadataFiles.buildInfo 'build-info.json' $receipt.buildInfo.sha256
    Copy-Item -LiteralPath $metadataFiles.installer -Destination (Join-Path $packageRoot 'AppVersion.iss')
    $info = Get-Content -Encoding UTF8 -Raw -LiteralPath (Join-Path $payloadRoot 'build-info.json') | ConvertFrom-Json
    $manifest = [ordered]@{
        schemaVersion = 1; packageType = 'prepared-windows-distribution'
        createdAt = [DateTimeOffset]::Now.ToString('o'); build = $info
        sourceBuild = (-not $SkipBuild)
        dependencyManifestSha256 = Get-ArtifactHash (Join-Path $dependencyRoot 'dependencies-manifest.json')
        files = @($manifestByPath.Values)
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
