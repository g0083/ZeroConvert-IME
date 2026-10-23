# Exercise the real build/apply orchestrator with a tiny fake toolchain.
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$scripts = Split-Path -Parent $PSScriptRoot
Import-Module (Join-Path $scripts 'distribution.psm1') -Force
Import-Module (Join-Path $scripts 'incremental-trial.psm1') -Force
$repo = [IO.Path]::GetFullPath((Join-Path $scripts '../..'))
$fixture = Join-Path $repo ('.local/trial-retry-tests/' + [guid]::NewGuid().ToString('N'))
$bin = Join-Path $fixture 'bin'
New-Item -ItemType Directory -Path $bin -Force | Out-Null
Add-Type -OutputAssembly (Join-Path $bin 'cargo.exe') -OutputType ConsoleApplication -TypeDefinition @'
using System;
using System.IO;
public class FakeBuild {
    public static void Main(string[] args) {
        bool frontend = args[0] == "frontend";
        string windows = frontend ? Path.GetFullPath("..") : Directory.GetCurrentDirectory();
        Directory.CreateDirectory(Path.Combine(windows, "target/release"));
        File.WriteAllText(Path.Combine(windows, "target/release", frontend ? "frontend.exe" : "ui.exe"), "built " + (frontend ? "frontend" : "ui"));
        File.AppendAllText(Path.Combine(windows, "calls.txt"), (frontend ? "frontend" : "ui") + Environment.NewLine);
        if (frontend || Environment.GetEnvironmentVariable("ZEROCON_TEST_DRIFT") == "continuous") {
            File.AppendAllText(Path.Combine(windows, "crates/ui/src/main.rs"), " changed during build");
        }
    }
}
'@
[IO.File]::WriteAllText((Join-Path $bin 'npm.cmd'), "@echo off`r`n`"%~dp0cargo.exe`" frontend`r`n")
$checks = 0
function Assert($Value, [string]$Message) { if (-not $Value) { throw "FAIL: $Message" }; $script:checks++ }
$oldPath = $env:PATH
$oldMode = $env:ZEROCON_TEST_DRIFT
try {
    $env:PATH = $bin + ';' + $env:PATH
    foreach ($mode in @('once','continuous')) {
        $root = Join-Path $fixture $mode
        foreach ($directory in @('windows/scripts','windows/frontend/src','windows/frontend/node_modules',
            'windows/crates/ui/src','ZeroConvertIME-windows')) {
            New-Item -ItemType Directory -Path (Join-Path $root $directory) -Force | Out-Null
        }
        foreach ($name in @('build-trial.ps1','incremental-trial.psm1','distribution.psm1','apply-trial-package.ps1')) {
            Copy-Item -LiteralPath (Join-Path $scripts $name) -Destination (Join-Path $root "windows/scripts/$name")
        }
        [IO.File]::WriteAllText((Join-Path $root 'windows/frontend/node_modules/.package-lock.json'), '{}')
        [IO.File]::WriteAllText((Join-Path $root 'windows/frontend/package.json'), '{}')
        [IO.File]::WriteAllText((Join-Path $root 'windows/frontend/package-lock.json'), '{}')
        [IO.File]::WriteAllText((Join-Path $root 'windows/frontend/src/main.ts'), 'initial frontend')
        [IO.File]::WriteAllText((Join-Path $root 'windows/crates/ui/src/main.rs'), 'initial ui')
        $trial = Join-Path $root 'ZeroConvertIME-windows'
        Write-ArtifactJson (Join-Path $trial 'build-info.json') @{version='fixture';buildNumber='1'}
        [IO.File]::WriteAllText((Join-Path $trial 'frontend.exe'), 'old frontend')
        [IO.File]::WriteAllText((Join-Path $trial 'ui.exe'), 'old ui')
        $state = Join-Path $trial 'trial-build-state.json'
        Write-ArtifactJson $state @{schemaVersion=1;inputs=(Get-TrialInputSnapshot $root);outputs=@{}}
        $stateHash = Get-ArtifactHash $state
        [IO.File]::WriteAllText((Join-Path $root 'windows/frontend/src/main.ts'), 'edited frontend')
        $env:ZEROCON_TEST_DRIFT = $mode
        $log = Join-Path $root 'build.log'
        # The fake binaries are never launched; applying only copies their text contents.
        $errorLog = Join-Path $root 'build-errors.log'
        $process = Start-Process -FilePath powershell.exe -ArgumentList @('-NoProfile','-ExecutionPolicy','Bypass',
            '-File',('"' + (Join-Path $root 'windows/scripts/build-trial.ps1') + '"'),'-NoRestart') `
            -WindowStyle Hidden -Wait -PassThru -RedirectStandardOutput $log -RedirectStandardError $errorLog
        $code = $process.ExitCode
        $calls = @(Get-Content -LiteralPath (Join-Path $root 'windows/calls.txt'))
        $logText = [IO.File]::ReadAllText($log) + [IO.File]::ReadAllText($errorLog)
        if ($mode -eq 'once') {
            Assert ($code -eq 0) "single drift succeeds: $log"
            Assert (($calls -join ',') -eq 'frontend,ui') 'UI drift builds only UI and reuses completed frontend'
            Assert ([IO.File]::ReadAllText((Join-Path $trial 'frontend.exe')) -eq 'built frontend') 'retained frontend output applied'
            Assert ([IO.File]::ReadAllText((Join-Path $trial 'ui.exe')) -eq 'built ui') 'additional UI output applied'
            $saved = Get-Content -Encoding UTF8 -Raw -LiteralPath $state | ConvertFrom-Json
            Assert (@(Get-TrialInputChanges $saved.inputs (Get-TrialInputSnapshot $root)).Count -eq 0) 'state records inputs only after stable follow-up'
        } else {
            Assert ($code -ne 0 -and $logText.Contains('3 build passes')) 'continuous drift stops after bounded attempts'
            Assert (($calls -join ',') -eq 'frontend,ui,ui') 'retries do not repeat unrelated frontend build'
            Assert ([IO.File]::ReadAllText((Join-Path $trial 'frontend.exe')) -eq 'old frontend') 'unstable build never applies outputs'
            Assert ((Get-ArtifactHash $state) -eq $stateHash) 'unstable build never advances saved state'
        }
        Assert ($logText.Contains('windows/crates/ui/src/main.rs')) 'diagnostics identify changed file'
    }
} finally {
    $env:PATH = $oldPath
    $env:ZEROCON_TEST_DRIFT = $oldMode
}
Write-Host "PASS: $checks retry integration assertions. Evidence: $fixture"
