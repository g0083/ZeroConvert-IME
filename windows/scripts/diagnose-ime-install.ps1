# Read-only install diagnosis. Does not stop processes, execute uninstallers,
# modify registry/files in installation folders, or read user settings/learning.
param([string]$OutputDirectory = $PSScriptRoot)
$ErrorActionPreference = 'Stop'
$report = New-Object Text.StringBuilder
$folders = New-Object 'System.Collections.Generic.HashSet[string]' ([StringComparer]::OrdinalIgnoreCase)
$null = $report.AppendLine('ZeroCon IME / azooKey installation diagnosis')
$null = $report.AppendLine('Created: ' + [DateTimeOffset]::Now.ToString('o'))
$null = $report.AppendLine('OS: ' + [Environment]::OSVersion.VersionString)
$null = $report.AppendLine('No system changes are performed. Settings and learning contents are not read.')
$null = $folders.Add((Join-Path $env:APPDATA 'Azookey'))
foreach ($programRoot in @($env:ProgramW6432, $env:ProgramFiles)) {
    if ($programRoot) { $null = $folders.Add((Join-Path $programRoot 'Azookey')) }
}
foreach ($hive in @([Microsoft.Win32.RegistryHive]::LocalMachine, [Microsoft.Win32.RegistryHive]::CurrentUser)) {
    foreach ($view in @([Microsoft.Win32.RegistryView]::Registry64, [Microsoft.Win32.RegistryView]::Registry32)) {
        $root = [Microsoft.Win32.RegistryKey]::OpenBaseKey($hive, $view)
        try {
            foreach ($name in @('{80B746D4-D74D-4345-8F81-47E06BCAB515}_is1', 'Azookey')) {
                $subkey = 'Software\Microsoft\Windows\CurrentVersion\Uninstall\' + $name
                $null = $report.AppendLine("`r`nRegistry: $hive / $view / $subkey")
                try { $entry = $root.OpenSubKey($subkey) }
                catch { $null = $report.AppendLine('Registry inspection denied: ' + $_.Exception.Message); continue }
                if (-not $entry) { $null = $report.AppendLine('Not present'); continue }
                try {
                    foreach ($field in @('DisplayName','DisplayVersion','InstallLocation','UninstallString','SystemComponent','NoRemove')) {
                        $value = [string]$entry.GetValue($field, '')
                        $null = $report.AppendLine($field + ': ' + $value)
                        if ($field -eq 'InstallLocation' -and $value -match '^[A-Za-z]:\\') {
                            $null = $folders.Add($value.Trim('"'))
                        }
                    }
                } finally { $entry.Dispose() }
            }
            try { $tip = $root.OpenSubKey('Software\Classes\CLSID\{ffdefe79-2fc2-11ef-b16b-94e70b2c378c}\InProcServer32') }
            catch { $tip = $null; $null = $report.AppendLine('TSF registry inspection denied: ' + $_.Exception.Message) }
            if ($tip) {
                try {
                    $dllPath = ([string]$tip.GetValue('')).Trim('"')
                    $null = $report.AppendLine("TSF DLL ($hive / $view): " + $dllPath)
                    if ($dllPath -match '^[A-Za-z]:\\') {
                        $null = $folders.Add([IO.Path]::GetDirectoryName($dllPath))
                    }
                }
                finally { $tip.Dispose() }
            }
        } finally { $root.Dispose() }
    }
}
foreach ($folder in $folders) {
    $null = $report.AppendLine("`r`nFolder: $folder")
    try {
        if (-not [IO.Directory]::Exists($folder)) { $null = $report.AppendLine('Not present or inaccessible'); continue }
        if (([IO.File]::GetAttributes($folder) -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
            $null = $report.AppendLine('Reparse point: skipped'); continue
        }
        foreach ($uninstallFile in @('unins000.exe', 'unins000.dat')) {
            $null = $report.AppendLine($uninstallFile + ' exists: ' + [IO.File]::Exists((Join-Path $folder $uninstallFile)))
        }
        foreach ($path in [IO.Directory]::EnumerateFileSystemEntries($folder)) {
            $null = $report.AppendLine('  ' + [IO.Path]::GetFileName($path))
        }
    } catch { $null = $report.AppendLine('Inspection failed: ' + $_.Exception.Message) }
}
$outputPath = Join-Path (Resolve-Path -LiteralPath $OutputDirectory).Path 'IME-install-diagnostic.txt'
[IO.File]::WriteAllText($outputPath, $report.ToString(), (New-Object Text.UTF8Encoding($true)))
Write-Host "Diagnostic report saved: $outputPath"
Write-Host 'The report contains installation paths and versions, but no settings contents or input history.'
