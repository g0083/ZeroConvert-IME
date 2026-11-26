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

if (-not ('ZeroConArtifactCrypto' -as [type])) {
    Add-Type -TypeDefinition @'
using System;
using System.IO;
using System.Security.Cryptography;
using System.Threading.Tasks;

public static class ZeroConArtifactCrypto {
    private const int BufferSize = 1048576;

    public static string HashFile(string path) {
        using (var stream = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.Read, BufferSize, FileOptions.SequentialScan))
        using (var sha = SHA256.Create()) {
            return BitConverter.ToString(sha.ComputeHash(stream)).Replace("-", "").ToLowerInvariant();
        }
    }

    public static string[] HashFilesParallel(string[] paths) {
        if (paths == null || paths.Length == 0) return new string[0];
        var results = new string[paths.Length];
        if (paths.Length == 1) {
            results[0] = HashFile(paths[0]);
            return results;
        }
        Parallel.For(0, paths.Length, i => {
            results[i] = HashFile(paths[i]);
        });
        return results;
    }

    public static string CopyAndHash(string source, string destination, out long totalBytes) {
        var buffer = new byte[BufferSize];
        long total = 0;
        using (var src = new FileStream(source, FileMode.Open, FileAccess.Read, FileShare.Read, BufferSize, FileOptions.SequentialScan))
        using (var dst = new FileStream(destination, FileMode.Create, FileAccess.Write, FileShare.None, BufferSize))
        using (var sha = SHA256.Create()) {
            int read;
            while ((read = src.Read(buffer, 0, buffer.Length)) > 0) {
                sha.TransformBlock(buffer, 0, read, null, 0);
                dst.Write(buffer, 0, read);
                total += read;
            }
            sha.TransformFinalBlock(new byte[0], 0, 0);
            totalBytes = total;
            return BitConverter.ToString(sha.Hash).Replace("-", "").ToLowerInvariant();
        }
    }
}
'@
}

function Get-ArtifactHash([string]$Path) {
    [ZeroConArtifactCrypto]::HashFile($Path)
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
        if (([IO.File]::Exists($cursor) -or [IO.Directory]::Exists($cursor)) -and
            ([IO.File]::GetAttributes($cursor) -band [IO.FileAttributes]::ReparsePoint)) {
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
    $collected = New-Object 'Collections.Generic.List[object]'
    while ($pending.Count) {
        foreach ($item in Get-ChildItem -LiteralPath $pending.Dequeue() -Force) {
            if ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) { throw "Reparse point: $($item.FullName)" }
            if ($item.PSIsContainer) { $pending.Enqueue($item.FullName); continue }
            $relative = $item.FullName.Substring($base.Length + 1).Replace('\', '/')
            if ($relative -in $Exclude) { continue }
            if ($NamesOnly) { $relative; continue }
            $collected.Add([pscustomobject]@{ path = $relative; fullName = $item.FullName; bytes = $item.Length })
        }
    }
    if (-not $NamesOnly -and $collected.Count -gt 0) {
        [string[]]$paths = @($collected | ForEach-Object { $_.fullName })
        $hashes = [ZeroConArtifactCrypto]::HashFilesParallel($paths)
        for ($i = 0; $i -lt $collected.Count; $i++) {
            $entry = $collected[$i]
            [pscustomobject]@{ path = $entry.path; bytes = $entry.bytes; sha256 = $hashes[$i] }
        }
    }
}

function Test-ArtifactEntries([string]$Root, $Entries) {
    # PowerShell hashtables use culture comparisons, which can conflate distinct kana filenames.
    $seen = [Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
    $toHash = New-Object 'Collections.Generic.List[object]'
    foreach ($entry in $Entries) {
        $path = Get-ArtifactPath $Root $entry.path
        $key = $entry.path.Replace('\', '/')
        if (-not $seen.Add($key)) { throw "Duplicate artifact: $key" }
        $info = [IO.FileInfo]::new($path)
        if (-not $info.Exists -or $info.Length -ne $entry.bytes) { throw "Artifact mismatch: $($entry.path)" }
        $toHash.Add([pscustomobject]@{ path = $entry.path; fullName = $path; sha256 = $entry.sha256 })
    }
    if ($toHash.Count -gt 0) {
        [string[]]$paths = @($toHash | ForEach-Object { $_.fullName })
        $hashes = [ZeroConArtifactCrypto]::HashFilesParallel($paths)
        for ($i = 0; $i -lt $toHash.Count; $i++) {
            if ($hashes[$i] -ne $toHash[$i].sha256) { throw "Artifact mismatch: $($toHash[$i].path)" }
        }
    }
}

function Copy-ArtifactFileVerified([string]$Source, [string]$Destination, [string]$ExpectedSha256 = $null) {
    $parent = Split-Path -Parent $Destination
    if ($parent) { $null = [IO.Directory]::CreateDirectory($parent) }
    $beforeInfo = [IO.FileInfo]::new($Source)
    if (-not $beforeInfo.Exists) { throw "Source artifact missing: $Source" }
    $beforeLength = $beforeInfo.Length
    $beforeTicks = $beforeInfo.LastWriteTimeUtc.Ticks
    [long]$bytes = 0
    $hash = [ZeroConArtifactCrypto]::CopyAndHash($Source, $Destination, [ref]$bytes)
    if ($ExpectedSha256) {
        if ($hash -ne $ExpectedSha256) {
            if ([IO.File]::Exists($Destination)) { [IO.File]::Delete($Destination) }
            throw "Runtime changed during snapshot; retry after deployment finishes: $Source"
        }
    } else {
        $afterInfo = [IO.FileInfo]::new($Source)
        if (-not $afterInfo.Exists -or $afterInfo.Length -ne $beforeLength -or $afterInfo.LastWriteTimeUtc.Ticks -ne $beforeTicks) {
            if (-not $afterInfo.Exists -or [ZeroConArtifactCrypto]::HashFile($Source) -ne $hash) {
                if ([IO.File]::Exists($Destination)) { [IO.File]::Delete($Destination) }
                throw "Runtime changed during snapshot; retry after deployment finishes: $Source"
            }
        }
    }
    [pscustomobject]@{ bytes = $bytes; sha256 = $hash }
}

function Copy-ArtifactIfChanged([string]$Source, [string]$Destination) {
    $srcInfo = [IO.FileInfo]::new($Source)
    $dstInfo = [IO.FileInfo]::new($Destination)
    if ($dstInfo.Exists -and $srcInfo.Exists -and $dstInfo.Length -eq $srcInfo.Length) {
        if ([ZeroConArtifactCrypto]::HashFile($Source) -eq [ZeroConArtifactCrypto]::HashFile($Destination)) {
            return $false
        }
    }
    $parent = Split-Path -Parent $Destination
    if ($parent) { $null = [IO.Directory]::CreateDirectory($parent) }
    Copy-Item -LiteralPath $Source -Destination $Destination -Force
    return $true
}

function Enable-FastRustLinker {
    if (Get-Command 'lld-link.exe' -ErrorAction SilentlyContinue) {
        if (-not $env:CARGO_TARGET_X86_64_PC_WINDOWS_MSVC_LINKER) {
            $env:CARGO_TARGET_X86_64_PC_WINDOWS_MSVC_LINKER = 'lld-link'
        }
        if (-not $env:CARGO_TARGET_I686_PC_WINDOWS_MSVC_LINKER) {
            $env:CARGO_TARGET_I686_PC_WINDOWS_MSVC_LINKER = 'lld-link'
        }
    }
}

function Format-ProcessArgument([string]$Arg) {
    if ($null -eq $Arg) { return '""' }
    if ($Arg.Length -eq 0) { return '""' }
    if ($Arg -notmatch '[\s"]') { return $Arg }
    $sb = New-Object Text.StringBuilder
    $null = $sb.Append('"')
    $backslashes = 0
    for ($i = 0; $i -lt $Arg.Length; $i++) {
        $ch = $Arg[$i]
        if ($ch -eq '\') {
            $backslashes++
        } elseif ($ch -eq '"') {
            if ($backslashes -gt 0) { $null = $sb.Append('\', $backslashes * 2) }
            $backslashes = 0
            $null = $sb.Append('\"')
        } else {
            if ($backslashes -gt 0) { $null = $sb.Append('\', $backslashes) }
            $backslashes = 0
            $null = $sb.Append($ch)
        }
    }
    if ($backslashes -gt 0) { $null = $sb.Append('\', $backslashes * 2) }
    $null = $sb.Append('"')
    return $sb.ToString()
}

function Invoke-ParallelBuildSteps([object[]]$Steps) {
    if (-not $Steps -or $Steps.Count -eq 0) { return }
    $running = New-Object 'Collections.Generic.List[object]'
    try {
        foreach ($step in $Steps) {
            $resolved = (Get-Command $step.Command -ErrorAction Stop).Source
            $argList = @($step.Arguments)
            $escapedArgs = ($argList | ForEach-Object { Format-ProcessArgument ([string]$_) }) -join ' '
            Write-Host ("Build [parallel:$($step.Name)]: $($step.Command) " + ($argList -join ' '))
            $psi = New-Object Diagnostics.ProcessStartInfo
            if ($resolved -match '\.(cmd|bat)$') {
                $psi.FileName = 'cmd.exe'
                $cmdLine = if ($escapedArgs) { "`"$resolved`" $escapedArgs" } else { "`"$resolved`"" }
                $psi.Arguments = "/d /c `"$cmdLine`""
            } else {
                $psi.FileName = $resolved
                $psi.Arguments = $escapedArgs
            }
            $psi.WorkingDirectory = $step.Directory
            $psi.UseShellExecute = $false
            $psi.CreateNoWindow = $true
            $psi.RedirectStandardOutput = $true
            $psi.RedirectStandardError = $true
            $psi.StandardOutputEncoding = [Text.Encoding]::UTF8
            $psi.StandardErrorEncoding = [Text.Encoding]::UTF8
            $proc = New-Object Diagnostics.Process
            $proc.StartInfo = $psi
            $null = $proc.Start()
            $stdoutTask = $proc.StandardOutput.ReadToEndAsync()
            $stderrTask = $proc.StandardError.ReadToEndAsync()
            $running.Add([pscustomobject]@{
                Step = $step
                Process = $proc
                Stdout = $stdoutTask
                Stderr = $stderrTask
            })
        }
        $failed = $null
        foreach ($item in $running) {
            $item.Process.WaitForExit()
            $outText = $item.Stdout.GetAwaiter().GetResult()
            $errText = $item.Stderr.GetAwaiter().GetResult()
            Write-Host "=== [$($item.Step.Name)] ==="
            if ($outText) { Write-Host $outText.TrimEnd("`r", "`n") }
            if ($errText) { Write-Host $errText.TrimEnd("`r", "`n") }
            if ($item.Process.ExitCode -ne 0 -and -not $failed) {
                $failed = "Build failed ($($item.Process.ExitCode)): $($item.Step.Command) $($item.Step.Arguments -join ' ')"
            }
        }
        if ($failed) { throw $failed }
    } finally {
        foreach ($item in $running) {
            if ($item.Process -and -not $item.Process.HasExited) {
                try { $item.Process.WaitForExit() } catch {}
            }
            if ($item.Process) { $item.Process.Dispose() }
        }
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
    # 変換モデルは同梱しない（%LOCALAPPDATA%\ZeroConIME\models へ設定画面から取得）。
    foreach ($required in @('frontend.exe','launcher.exe','ui.exe','azookey-server.exe','azookey-server.dll',
        'azookey.dll','azookey32.dll','settings.json','build-info.json',
        'llama/llama-server.exe','llama/ggml-vulkan.dll')) {
        if ($required -notin @($manifest.files.path)) { throw "Incomplete package: $required" }
    }
    # 日本語辞書（azooKey標準辞書＋追加辞書）は初回起動時に取得するため同梱しない。
    # github release の dictionary-catalog.json と対応する zip が配布元。
    foreach ($bundled in @($manifest.files.path | Where-Object { $_ -cmatch '^Dictionary/' })) {
        throw "Dictionary must not be bundled: $bundled"
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

Export-ModuleMember -Function *-Artifact*, Read-PreparedPackage, Get-LatestPackageDirectory, Assert-DefaultTrialPackageIsCurrent, Enable-FastRustLinker, Invoke-ParallelBuildSteps
