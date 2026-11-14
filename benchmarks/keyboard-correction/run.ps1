[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$OutputDirectory,
    [string]$ModelPath,
    [string]$TestFilter = 'sharedTouchCorrection|touchWeightCannot|fixedDictionaryCorrection|keyboardTypoReadinessEvaluation'
)
$ErrorActionPreference = 'Stop'
$repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$windowsRoot = Join-Path $repoRoot 'windows'
$outputRoot = [IO.Path]::GetFullPath($OutputDirectory)
New-Item -ItemType Directory -Force -Path $outputRoot | Out-Null
$casePath = Join-Path $outputRoot 'cases.json'
if (Test-Path -LiteralPath $casePath) { throw "Preserving existing evaluation: $casePath" }

Push-Location $windowsRoot
try {
    . ./scripts/modern-llama.ps1
    $runtime = Initialize-ModernLlama
    if ($env:SDKROOT -notmatch '^(.*[\\/]Windows\.platform)[\\/]Developer[\\/]') {
        throw 'SDKROOT must identify the installed Swift Windows SDK.'
    }
    $platform = $Matches[1]
    $version = Split-Path (Split-Path $platform -Parent) -Leaf
    $swiftRoot = Split-Path (Split-Path (Split-Path $platform -Parent) -Parent) -Parent
    $libraryRoot = Join-Path $platform 'Developer/Library'
    $testing = Get-ChildItem -LiteralPath $libraryRoot -Directory -Filter 'Testing-*' |
        Where-Object { Test-Path (Join-Path $_.FullName 'usr/bin64/Testing.dll') } |
        Sort-Object Name -Descending | Select-Object -First 1
    $xctest = Get-ChildItem -LiteralPath $libraryRoot -Directory -Filter 'XCTest-*' |
        Where-Object { Test-Path (Join-Path $_.FullName 'usr/bin64/XCTest.dll') } |
        Sort-Object Name -Descending | Select-Object -First 1
    if (!$testing -or !$xctest) { throw 'Swift Testing and XCTest runtimes are required.' }
    $release = Join-Path $windowsRoot 'server-swift/.build-modern/x86_64-unknown-windows-msvc/release'
    $env:PATH = @($release, (Join-Path $swiftRoot "Runtimes/$version/usr/bin"),
        (Join-Path $testing.FullName 'usr/bin64'), (Join-Path $xctest.FullName 'usr/bin64'), $env:PATH) -join ';'
    # The native bundle avoids Swift 6.4's dynamic test-library discovery failure
    # on this Windows host. The repository's VM runner uses the same exe copy.
    & swift build --package-path server-swift --scratch-path server-swift/.build-modern --build-system native --build-tests -c release -Xswiftc -enable-testing -Xcc -D_CRT_USE_C_COMPLEX_H -Xcxx -D_CRT_USE_C_COMPLEX_H
    if ($LASTEXITCODE -ne 0) { throw 'Swift correction test build failed.' }
    $runner = Join-Path $release 'azookey-serverPackageTests.exe'
    Copy-Item -LiteralPath (Join-Path $release 'azookey-serverPackageTests.xctest') -Destination $runner -Force
    $env:ZEROCON_TYPO_EVALUATION_OUTPUT = $casePath
    if ($ModelPath) {
        $env:ZEROCON_TYPO_EVALUATION_MODEL = (Resolve-Path -LiteralPath $ModelPath).Path
    } else {
        Remove-Item Env:ZEROCON_TYPO_EVALUATION_MODEL -ErrorAction SilentlyContinue
    }
    & $runner --testing-library swift-testing --no-parallel --filter $TestFilter
    $testExit = $LASTEXITCODE
    if (!(Test-Path -LiteralPath $casePath)) { throw 'Evaluation did not run or did not produce cases.json.' }
    $metadata = [ordered]@{
        testExitCode = $testExit
        converter = $runtime.converter
        model = $env:ZEROCON_TYPO_EVALUATION_MODEL
        modelSha256 = $(if ($ModelPath) { (Get-FileHash -LiteralPath $env:ZEROCON_TYPO_EVALUATION_MODEL -Algorithm SHA256).Hash.ToLowerInvariant() } else { $null })
        swift = (& swift --version | Select-Object -First 1)
        os = [Environment]::OSVersion.VersionString
        processor = $env:PROCESSOR_IDENTIFIER
        inputStyle = 'roman2kana'
        learning = 'nothing'
        backend = 'cpu'
        correctionCompared = @($false, $true)
        touchNeighborWeight = 0.9
        touchAlternativeLookupLimit = 4
        touchOriginalLookupLimit = 1
    }
    $metadata | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $outputRoot 'environment.json') -Encoding utf8
    if ($testExit -ne 0) { throw "Correction evaluation failed ($testExit). See $casePath" }
} finally { Pop-Location }
