<#
.SYNOPSIS
    Stop ZeroCon IME Background Processes
#>

$processNames = @("azookey-server", "ui", "frontend", "launcher", "llama-server")

foreach ($name in $processNames) {
    $procs = Get-Process -Name $name -ErrorAction SilentlyContinue
    if ($procs) {
        Write-Host "[ZeroCon IME] Stopping $name (PID: $($procs.Id -join ', '))..." -ForegroundColor Yellow
        $procs | Stop-Process -Force -ErrorAction SilentlyContinue
    }
}

Start-Sleep -Milliseconds 500
Write-Host "[ZeroCon IME] All background processes stopped." -ForegroundColor Green
