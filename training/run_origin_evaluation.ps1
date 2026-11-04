# SPDX-License-Identifier: Apache-2.0
# Sequential research evaluation only. Never applies models to the IME.
param(
    [Parameter(Mandatory=$true)][string]$Run,
    [Parameter(Mandatory=$true)][string]$Dataset,
    [Parameter(Mandatory=$true)][string]$Prefix,
    [int]$WaitTrainingPid = 0
)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
Set-Location -LiteralPath $root
if ($Prefix -notmatch '^origin-[a-z0-9-]+$') { throw 'Use a plain Origin artifact prefix' }
$python = Join-Path $root '.venv_origin/Scripts/python.exe'
$statePath = "artifacts/results/$Prefix-pipeline.json"
if (Test-Path -LiteralPath $statePath) { throw 'Pipeline already exists; inspect its state before continuing' }
$manifestPath = Join-Path $Run 'run-manifest.json'
$manifest = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
if ($manifest.schema -ne 'zerocon-origin-run-v1') { throw 'Owned Origin run required' }
$manifestHash = (Get-FileHash -LiteralPath $manifestPath -Algorithm SHA256).Hash
$state = [ordered]@{run=$Run;dataset=$Dataset;prefix=$Prefix;manifest_sha256=$manifestHash;pipeline_pid=$PID;phase='waiting';stages=@();status='running';automatic_adoption_approved=$false}
function Save-State { $state | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $statePath -Encoding utf8 }
function Invoke-Stage([string]$Name, [string[]]$Arguments) {
    $state.phase = $Name
    Save-State
    $log = "artifacts/logs/$Prefix-$Name.log"
    & $python scripts/run.py @Arguments *> $log
    $code = $LASTEXITCODE
    $state.stages += [ordered]@{name=$Name;arguments=$Arguments;exit_code=$code;log=$log}
    Save-State
    if ($code -ne 0) { throw "Stage $Name exited $code; see $log" }
}
Save-State
try {
    if ($WaitTrainingPid -gt 0) {
        if ($manifest.pid -ne $WaitTrainingPid) { throw 'Training PID differs from run manifest' }
        $process = Get-CimInstance Win32_Process -Filter "ProcessId = $WaitTrainingPid"
        if ($process) {
            $leaf = Split-Path $Run -Leaf
            if ($process.CommandLine -notlike '*train_origin_cached*.py*' -or $process.CommandLine -notlike "*$leaf*") {
                throw 'Live process identity differs from this training run'
            }
            $ownedProcess = Get-Process -Id $WaitTrainingPid
            $null = $ownedProcess.Handle
            $ownedProcess.WaitForExit()
            $state.training_exit_code = $ownedProcess.ExitCode
            if ($null -eq $ownedProcess.ExitCode -or $ownedProcess.ExitCode -ne 0) { throw 'Owned training process did not exit successfully' }
        }
    }
    $progress = Get-Content -LiteralPath (Join-Path $Run 'progress.json') -Raw | ConvertFrom-Json
    $metrics = Get-Content -LiteralPath (Join-Path $Run 'metrics.json') -Raw | ConvertFrom-Json
    if ($progress.step -ne $manifest.arguments.steps -or $metrics.processed_tokens -lt $manifest.arguments.token_budget) {
        throw 'Training completion not established'
    }
    if ((Get-FileHash -LiteralPath $manifestPath -Algorithm SHA256).Hash -ne $manifestHash) { throw 'Run manifest changed' }
    $scores = "artifacts/results/$Prefix-calibration-scores.json"
    $cohort = 'artifacts/results/origin-repair-operating-cohort-20261029.json'
    Invoke-Stage 'calibration' @('benchmarks/research/evaluate_origin_tasks.py','--run',$Run,'--dataset',$Dataset,'--out',$scores,'--max-cases','0','--device','cuda','--batch-size','32','--record-scores','--operating-cohort',$cohort,'--cohort-partition','calibration')
    Invoke-Stage 'operating-points' @('benchmarks/research/analyze_origin_operating_points.py','--run',$Run,'--diagnostic',$scores,'--cohort',$cohort,'--out',"artifacts/results/$Prefix-operating-points.json")
    Invoke-Stage 'tasks1024' @('benchmarks/research/evaluate_origin_tasks.py','--run',$Run,'--dataset',$Dataset,'--out',"artifacts/results/$Prefix-tasks1024.json",'--max-cases','1024','--device','cuda','--batch-size','32')
    $export = "artifacts/models/$Prefix-export"
    $quant = "artifacts/models/$Prefix-q8"
    Invoke-Stage 'export' @('training/export_origin.py','--run',$Run,'--out',$export,'--outtype','f16')
    Invoke-Stage 'quantize' @('training/quantize_origin_q8.py','--export',$export,'--out',$quant)
    Invoke-Stage 'regression' @('benchmarks/research/benchmark_origin_regression.py','--run',$Run,'--gguf',"$quant/origin-q8_0.gguf",'--out',"artifacts/results/$Prefix-regression.json",'--base-port','8540','--concurrent-training')
    $state.phase = 'finished'
    $state.status = 'evaluated; all quality/adoption gates still require review'
    Save-State
} catch {
    $state.status = 'failed'
    $state.error = $_.Exception.Message
    Save-State
    throw
}
