Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Get-ArtifactProcessPath([int]$ProcessId) {
    # .NET Framework Process.Path requires more privileges than this limited query.
    if (-not ('ZeroConDistributionProcess' -as [type])) {
        Add-Type -TypeDefinition @'
using System;
using System.Text;
using System.Runtime.InteropServices;
public static class ZeroConDistributionProcess {
    [DllImport("kernel32.dll", SetLastError=true)] static extern IntPtr OpenProcess(uint rights, bool inherit, int pid);
    [DllImport("kernel32.dll", CharSet=CharSet.Unicode, SetLastError=true)] static extern bool QueryFullProcessImageName(IntPtr handle, uint flags, StringBuilder text, ref int size);
    [DllImport("kernel32.dll")] static extern bool CloseHandle(IntPtr handle);
    [DllImport("kernel32.dll", SetLastError=true)] static extern bool TerminateProcess(IntPtr handle, uint code);
    [DllImport("kernel32.dll", SetLastError=true)] static extern bool GetExitCodeProcess(IntPtr handle, out uint code);
    [DllImport("kernel32.dll")] static extern uint WaitForSingleObject(IntPtr handle, uint milliseconds);
    public static string ImagePath(int pid) {
        var handle = OpenProcess(0x1000, false, pid);
        if (handle == IntPtr.Zero) return null;
        try {
            var text = new StringBuilder(32768); int size = text.Capacity;
            return QueryFullProcessImageName(handle, 0, text, ref size) ? text.ToString() : null;
        } finally { CloseHandle(handle); }
    }
    public static bool CanStop(int pid) {
        var handle = OpenProcess(0x100001, false, pid);
        if (handle == IntPtr.Zero) return false;
        CloseHandle(handle); return true;
    }
    public static bool IsRunning(int pid) {
        var handle = OpenProcess(0x1000, false, pid);
        if (handle == IntPtr.Zero) return false;
        try { uint code; return GetExitCodeProcess(handle, out code) && code == 259; }
        finally { CloseHandle(handle); }
    }
    public static void Stop(int pid) {
        var handle = OpenProcess(0x100001, false, pid);
        if (handle == IntPtr.Zero) {
            var error = Marshal.GetLastWin32Error();
            if (error == 87) return; // Process exited after enumeration.
            throw new System.ComponentModel.Win32Exception(error);
        }
        try {
            if (!TerminateProcess(handle, 0)) {
                var error = Marshal.GetLastWin32Error();
                if (!IsRunning(pid)) return;
                throw new System.ComponentModel.Win32Exception(error);
            }
            if (WaitForSingleObject(handle, 5000) != 0) throw new Exception("Trial process did not exit: " + pid);
        } finally { CloseHandle(handle); }
    }
}
'@
    }
    [ZeroConDistributionProcess]::ImagePath($ProcessId)
}

function Get-ArtifactHash([string]$Path) {
    $stream = [IO.File]::OpenRead($Path)
    $algorithm = [Security.Cryptography.SHA256]::Create()
    try { [BitConverter]::ToString($algorithm.ComputeHash($stream)).Replace('-','').ToLowerInvariant() }
    finally { $stream.Dispose(); $algorithm.Dispose() }
}

function Get-ArtifactPath([string]$Root, [string]$RelativePath) {
    if ([string]::IsNullOrWhiteSpace($RelativePath) -or
        $RelativePath -match '(^[\\/]|:|(^|[\\/])\.\.?([\\/]|$))') {
        throw "Unsafe artifact path: $RelativePath"
    }
    $base = [IO.Path]::GetFullPath($Root).TrimEnd('\')
    $path = [IO.Path]::GetFullPath((Join-Path $base $RelativePath))
    if (-not $path.StartsWith($base + '\', [StringComparison]::OrdinalIgnoreCase)) {
        throw "Artifact escapes root: $RelativePath"
    }
    # Do not traverse junctions/symlinks when reading or deploying artifacts.
    $cursor = $path
    while ($cursor) {
        if ((Test-Path -LiteralPath $cursor) -and
            ((Get-Item -LiteralPath $cursor -Force).Attributes -band [IO.FileAttributes]::ReparsePoint)) {
            throw "Artifact uses a reparse point: $cursor"
        }
        $cursor = Split-Path -Parent $cursor
    }
    return $path
}

function Write-ArtifactJson([string]$Path, $Value) {
    $temporary = $Path + '.' + [guid]::NewGuid().ToString('N') + '.tmp'
    [IO.File]::WriteAllText($temporary, ($Value | ConvertTo-Json -Depth 12), (New-Object Text.UTF8Encoding($false)))
    if (Test-Path -LiteralPath $Path) { [IO.File]::Replace($temporary, $Path, [NullString]::Value) }
    else { Move-Item -LiteralPath $temporary -Destination $Path }
}

function Get-ArtifactEntries([string]$Root, [string[]]$Exclude = @(), [switch]$NamesOnly) {
    $base = [IO.Path]::GetFullPath($Root).TrimEnd('\')
    # Reject directory links before recursive enumeration.
    $pending = New-Object 'Collections.Generic.Queue[string]'
    $pending.Enqueue($base)
    while ($pending.Count) {
        foreach ($item in Get-ChildItem -LiteralPath $pending.Dequeue() -Force) {
            if ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) { throw "Reparse point: $($item.FullName)" }
            if ($item.PSIsContainer) { $pending.Enqueue($item.FullName); continue }
            $relative = $item.FullName.Substring($base.Length + 1).Replace('\', '/')
            if ($relative -in $Exclude) { continue }
            if ($NamesOnly) { $relative; continue }
            [pscustomobject]@{ path = $relative; bytes = $item.Length; sha256 = Get-ArtifactHash $item.FullName }
        }
    }
}

function Test-ArtifactEntries([string]$Root, $Entries) {
    # PowerShell hashtables use culture comparisons, which can conflate distinct kana filenames.
    $seen = [Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
    foreach ($entry in $Entries) {
        $path = Get-ArtifactPath $Root $entry.path
        $key = $entry.path.Replace('\', '/')
        if (-not $seen.Add($key)) { throw "Duplicate artifact: $key" }
        if (-not (Test-Path -LiteralPath $path -PathType Leaf) -or
            (Get-Item -LiteralPath $path).Length -ne $entry.bytes -or
            (Get-ArtifactHash $path) -ne $entry.sha256) { throw "Artifact mismatch: $($entry.path)" }
    }
}

function Read-PreparedPackage([string]$Directory) {
    $directoryPath = (Resolve-Path -LiteralPath $Directory).ProviderPath
    $recordPath = Get-ArtifactPath $directoryPath 'package.json'
    $record = Get-Content -Encoding UTF8 -Raw -LiteralPath $recordPath | ConvertFrom-Json
    if ($record.schemaVersion -ne 1) { throw 'Unsupported prepared package schema.' }
    $payload = Get-ArtifactPath $directoryPath 'payload'
    $manifestPath = Get-ArtifactPath $payload 'package-manifest.json'
    if ((Get-ArtifactHash $manifestPath) -ne $record.manifestSha256 -or
        (Get-ArtifactHash (Get-ArtifactPath $directoryPath 'AppVersion.iss')) -ne $record.installerMetadataSha256) {
        throw 'Prepared package metadata changed.'
    }
    $manifest = Get-Content -Encoding UTF8 -Raw -LiteralPath $manifestPath | ConvertFrom-Json
    if ($manifest.schemaVersion -ne 1) { throw 'Unsupported payload manifest schema.' }
    Test-ArtifactEntries $payload $manifest.files
    $actual = @(Get-ArtifactEntries $payload @('package-manifest.json') -NamesOnly)
    if ($actual.Count -ne @($manifest.files).Count) { throw 'Unexpected files in prepared payload.' }
    foreach ($required in @('frontend.exe','launcher.exe','ui.exe','azookey-server.exe','azookey-server.dll',
        'azookey.dll','azookey32.dll','settings.json','build-info.json',
        'models/zerocon-nano-v4-q8_0.gguf','models/zerocon-nano-v4-iq4nl-emb8.gguf','models/zerocon-model-corrector.gguf',
        'llama_cpu/llama-server.exe','llama_vulkan/llama-server.exe')) {
        if ($required -notin @($manifest.files.path)) { throw "Incomplete package: $required" }
    }
    $info = Get-Content -Encoding UTF8 -Raw -LiteralPath (Join-Path $payload 'build-info.json') | ConvertFrom-Json
    if (($info | ConvertTo-Json -Compress) -ne ($manifest.build | ConvertTo-Json -Compress)) {
        throw 'Payload build identity does not match manifest.'
    }
    [pscustomobject]@{ Directory = $directoryPath; Payload = $payload; Manifest = $manifest; Record = $record; Build = $info }
}

function Get-LatestPackageDirectory([string]$RepoRoot) {
    $pointer = Get-Content -Encoding UTF8 -Raw -LiteralPath (Join-Path $RepoRoot 'dist/packages/latest.json') | ConvertFrom-Json
    Get-ArtifactPath (Join-Path $RepoRoot 'dist/packages') $pointer.directory
}

function Assert-DefaultTrialPackageIsCurrent([string]$TrialDirectory, $Package) {
    $receiptPath = Join-Path $TrialDirectory 'trial-deployment.json'
    if (-not (Test-Path -LiteralPath $receiptPath)) { return }
    $receipt = Get-Content -Encoding UTF8 -Raw -LiteralPath $receiptPath | ConvertFrom-Json
    if (-not $receipt.PSObject.Properties['mode'] -or $receipt.mode -ne 'incremental') { return }
    $packageCreated = [DateTimeOffset]::Parse($Package.Manifest.createdAt)
    $appliedAt = [DateTimeOffset]::Parse($receipt.appliedAt)
    if ($packageCreated -le $appliedAt) {
        throw "The default complete package ($packageCreated) predates the applied incremental update ($appliedAt). Build-Trial.bat already builds AND applies local changes; run it alone. To deliberately apply a complete package, specify -PackageDirectory explicitly. No files or processes were changed."
    }
}

Export-ModuleMember -Function *-Artifact*, Read-PreparedPackage, Get-LatestPackageDirectory, Assert-DefaultTrialPackageIsCurrent
