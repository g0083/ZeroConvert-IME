# Package a verified common distribution; never snapshots the running IME.
[CmdletBinding()]
param(
    [string]$PackageDirectory, [switch]$UseLatestPackage,
    [string]$DependencyDirectory, [string]$IsccPath,
    [switch]$PrepareOnly, [switch]$SkipBuild, [string]$SwiftRuntimeDirectory,
    [string]$RuntimeDirectory
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
Import-Module (Join-Path $PSScriptRoot 'distribution.psm1') -Force
$repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$windowsRoot = Join-Path $repoRoot 'windows'
if ($RuntimeDirectory) { throw '-RuntimeDirectory is retired. Import dependencies once with import-runtime-dependencies.ps1; use -DependencyDirectory for a dedicated dependency store.' }
if ($PackageDirectory -and $UseLatestPackage) { throw 'Choose -PackageDirectory or -UseLatestPackage.' }
if (($PackageDirectory -or $UseLatestPackage) -and ($SkipBuild -or $DependencyDirectory -or $SwiftRuntimeDirectory)) { throw 'An existing package cannot be combined with build options.' }
function Assert-File([string]$Path) {
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { throw "Required file missing: $Path" }
}

function Find-Iscc {
    $candidates = @($IsccPath, $env:ISCC_EXE,
        (Join-Path $repoRoot '.local\tools\InnoSetup6\ISCC.exe'))
    $command = Get-Command ISCC.exe -ErrorAction SilentlyContinue
    if ($command) { $candidates += $command.Source }
    foreach ($base in @(${env:ProgramFiles(x86)}, $env:ProgramFiles, $env:LOCALAPPDATA)) {
        if ($base) {
            $candidates += Join-Path $base 'Inno Setup 6\ISCC.exe'
            $candidates += Join-Path $base 'Inno Setup 7\ISCC.exe'
            $candidates += Join-Path $base 'Programs\Inno Setup 6\ISCC.exe'
            $candidates += Join-Path $base 'Programs\Inno Setup 7\ISCC.exe'
        }
    }
    foreach ($candidate in $candidates) {
        if ($candidate -and (Test-Path -LiteralPath $candidate -PathType Leaf)) {
            return (Resolve-Path -LiteralPath $candidate).Path
        }
    }
    throw 'Inno Setup not found. Install Inno Setup 6 from https://jrsoftware.org/isdl.php or pass -IsccPath <ISCC.exe>.'
}

$compiler = if ($PrepareOnly) { $null } else { Find-Iscc }
if ($UseLatestPackage) {
    $trialReceipt = Join-Path $repoRoot 'ZeroConvertIME-windows/trial-deployment.json'
    if (Test-Path -LiteralPath $trialReceipt) {
        $trialState = Get-Content -Encoding UTF8 -Raw -LiteralPath $trialReceipt | ConvertFrom-Json
        if ($trialState.PSObject.Properties['mode'] -and $trialState.mode -eq 'incremental') {
            throw 'Trial has incremental development updates. Run Build-Package.bat and Apply-Trial.bat before packaging the tested full build. Use -PackageDirectory only to explicitly package an older build.'
        }
    }
    $PackageDirectory = Get-LatestPackageDirectory $repoRoot
}
if (-not $PackageDirectory) {
    $PackageDirectory = & (Join-Path $PSScriptRoot 'prepare-distribution.ps1') -DependencyDirectory $DependencyDirectory -SkipBuild:$SkipBuild -SwiftRuntimeDirectory $SwiftRuntimeDirectory
}
$package = Read-PreparedPackage $PackageDirectory
$packageRoot = $package.Directory
$payloadRoot = $package.Payload
$info = $package.Build
if ($PrepareOnly) { Write-Host "Prepared package: $packageRoot"; return }
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss-fff'
$outputRoot = Join-Path $repoRoot "dist/installers/$stamp"
New-Item -ItemType Directory -Path $outputRoot -Force | Out-Null
Copy-Item -LiteralPath (Join-Path $payloadRoot 'package-manifest.json'), (Join-Path $payloadRoot 'build-info.json') -Destination $outputRoot
Write-ArtifactJson (Join-Path $outputRoot 'package-source.json') @{ packageDirectory = $packageRoot; manifestSha256 = $package.Record.manifestSha256 }
    $outputName = "ZeroConvert-IME-setup-$($info.version)"
    Write-Host "Compiler: $compiler"
    Write-Host "Output: $outputRoot"
    & $compiler '/Qp' '/DLocalZeroConPackage=1' "/DPayloadDir=$payloadRoot" "/DBuildMetadataFile=$packageRoot\AppVersion.iss" "/O$outputRoot" "/F$outputName" (Join-Path $windowsRoot 'installer\Installer.iss')
    if ($LASTEXITCODE -ne 0) { throw "Installer compiler failed: $LASTEXITCODE" }
    $null = Read-PreparedPackage $packageRoot
    $installer = Join-Path $outputRoot "$outputName.exe"
    Assert-File $installer
    if ($info.channel -ne 'validation' -or $info.installerGeneration -lt 1) { throw 'Invalid local package identity.' }
    if ((Get-Item -LiteralPath $installer).Length -le 0) { throw 'Installer is empty.' }
    $checksum = Get-ArtifactHash $installer
    [IO.File]::WriteAllText((Join-Path $outputRoot 'SHA256SUMS.txt'), "$checksum  $outputName.exe`r`n", [Text.Encoding]::ASCII)
    Write-Host "SHA-256: $checksum"
    Copy-Item -LiteralPath (Join-Path $repoRoot 'Install-ZeroCon.bat') -Destination $outputRoot
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'install-with-legacy-removal.ps1') -Destination $outputRoot
    Write-Host "Created installer: $installer" -ForegroundColor Green
    Write-Host 'Copy the installer EXE to the other PC and run it there. This command does not install it here.'
