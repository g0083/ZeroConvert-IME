$ErrorActionPreference='Stop'
Set-StrictMode -Version Latest
$scripts=Split-Path -Parent $PSScriptRoot
Import-Module (Join-Path $scripts 'distribution.psm1') -Force
Import-Module (Join-Path $scripts 'incremental-trial.psm1') -Force
$repo=[IO.Path]::GetFullPath((Join-Path $scripts '../..'))
$fixture=Join-Path $repo ('.local/incremental-tests/'+[guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $fixture -Force | Out-Null
$script:checks=0
function Assert($Value,[string]$Message) { if (-not $Value) { throw "FAIL: $Message" }; $script:checks++ }
function Reject([scriptblock]$Action,[string]$Message) { $failed=$false; try { & $Action | Out-Null } catch { $failed=$true }; Assert $failed $Message }
function Put([string]$Relative,[string]$Value) { $file=Join-Path $fixture $Relative; New-Item -ItemType Directory -Path (Split-Path -Parent $file) -Force | Out-Null; [IO.File]::WriteAllText($file,$Value) }
Put 'default-trial/trial-deployment.json' '{"mode":"incremental","appliedAt":"2026-10-23T17:51:00+09:00"}'
$defaultTrial = Join-Path $fixture 'default-trial'
$oldPackage = [pscustomobject]@{ Manifest=[pscustomobject]@{createdAt='2026-10-23T16:25:00+09:00'} }
$newPackage = [pscustomobject]@{ Manifest=[pscustomobject]@{createdAt='2026-10-23T18:00:00+09:00'} }
$receiptHash = Get-ArtifactHash (Join-Path $defaultTrial 'trial-deployment.json')
Reject { Assert-DefaultTrialPackageIsCurrent $defaultTrial $oldPackage } 'implicit old full package cannot overwrite newer incremental update'
Assert-DefaultTrialPackageIsCurrent $defaultTrial $newPackage
Assert ((Get-ArtifactHash (Join-Path $defaultTrial 'trial-deployment.json')) -eq $receiptHash) 'default-package checks do not mutate deployment state'
Assert-DefaultTrialPackageIsCurrent (Join-Path $fixture 'not-installed') $oldPackage
Put 'default-trial/trial-deployment.json' '{"mode":"full","appliedAt":"2026-10-23T17:51:00+09:00"}'
Assert-DefaultTrialPackageIsCurrent $defaultTrial $oldPackage
Put 'windows/frontend/src/pages/general.tsx' 'before'
Put 'windows/crates/shared/src/lib.rs' 'shared'
Put 'windows/server-swift/azooKey_dictionary_storage/Dictionary/test.louds' 'dictionary'
$first=Get-TrialInputSnapshot $fixture
$same=Get-TrialInputSnapshot $fixture $first
Assert ($first.frontend.hash -eq $same.frontend.hash) 'unchanged inputs stay unchanged'
$source=Join-Path $fixture 'windows/frontend/src/pages/general.tsx'
$time=(Get-Item -LiteralPath $source).LastWriteTimeUtc
[IO.File]::WriteAllText($source,'after!')
[IO.File]::SetLastWriteTimeUtc($source,$time)
$next=Get-TrialInputSnapshot $fixture $same
Assert ($next.frontend.hash -ne $same.frontend.hash) 'source content changes detected even at same size/time'
Assert ($next.shared.hash -eq $same.shared.hash) 'unrelated group unchanged'
$inputChanges = @(Get-TrialInputChanges $same $next)
Assert ($inputChanges.Count -eq 1 -and $inputChanges[0].group -eq 'frontend') 'identify changed group during build'
Assert ($inputChanges[0].paths[0] -eq 'windows/frontend/src/pages/general.tsx') 'report actual changed source path'
Put 'windows/crates/ui/src/main.rs' 'new UI while frontend builds'
$uiChanged = Get-TrialInputSnapshot $fixture $next
$pending = @(Get-TrialInputChanges $next $uiChanged)
Assert ((@(Get-TrialBuildPlan @($pending | ForEach-Object { $_.group })) -join ',') -eq 'ui') 'UI changes during frontend build only queue UI, preserving completed frontend'
Put 'windows/crates/shared/src/lib.rs' 'dependency changed while UI rebuilds'
$sharedChanged = Get-TrialInputSnapshot $fixture $uiChanged
$pending = @(Get-TrialInputChanges $uiChanged $sharedChanged)
Assert (@(Get-TrialBuildPlan @($pending | ForEach-Object { $_.group })).Count -eq 5) 'dependency drift requeues completed consumers too'
Assert (@(Get-TrialInputChanges $sharedChanged (Get-TrialInputSnapshot $fixture $sharedChanged)).Count -eq 0) 'stable additional pass can apply completed outputs'
Assert ((@(Get-TrialBuildPlan @('frontend')) -join ',') -eq 'frontend') 'frontend edit builds frontend only'
Assert ((@(Get-TrialBuildPlan @('client')) -join ',') -eq 'client') 'client edit builds client only'
Assert ((@(Get-TrialBuildPlan @('swift')) -join ',') -eq 'swift') 'Swift edit avoids Rust rebuild'
Assert ((@(Get-TrialBuildPlan @('server')) -join ',') -eq 'frontend,server,ui') 'server consumers included'
Assert (@(Get-TrialBuildPlan @('shared')).Count -eq 5) 'shared changes include all native consumers'
Assert (@(Get-TrialBuildPlan @()).Count -eq 0) 'no changes means no work'
Assert ((@(Get-TrialBuildPlan @('tooling')) -join ',') -eq 'tooling') 'workflow edits do not rebuild product binaries'
Assert ((@(Get-TrialBuildPlan @('keyboard')) -join ',') -eq 'keyboard') 'keyboard worker edits build the isolated worker only'
Put 'windows/native/keyboard-prior/main.cpp' 'native worker source'
$nativeChanged = Get-TrialInputSnapshot $fixture $next
Assert ($nativeChanged.keyboard.hash -ne $next.keyboard.hash) 'native worker source changes are tracked'
Move-Item -LiteralPath (Join-Path $fixture 'windows/server-swift/azooKey_dictionary_storage/Dictionary/test.louds') -Destination (Join-Path $fixture 'removed.louds')
$removed=Get-TrialInputSnapshot $fixture $next
Assert ($removed.dictionary.hash -ne $next.dictionary.hash) 'deleted inputs detected'
$deleted = @(Get-TrialInputChanges $next $removed | Where-Object group -eq 'dictionary')
Assert ($deleted.Count -eq 1 -and $deleted[0].paths[0].EndsWith('/test.louds')) 'deleted path appears in drift diagnostics'
$update=Join-Path $fixture 'update'
$payload=Join-Path $update 'payload'
New-Item -ItemType Directory -Path $payload -Force | Out-Null
[IO.File]::WriteAllText((Join-Path $payload 'frontend.exe'),'new frontend')
$trial=Join-Path $fixture 'trial'
New-Item -ItemType Directory -Path $trial -Force | Out-Null
[IO.File]::WriteAllText((Join-Path $trial 'azookey-server.exe'),'keep server')
[IO.File]::WriteAllText((Join-Path $trial 'package-manifest.json'),'keep full baseline')
[IO.File]::WriteAllText((Join-Path $trial 'old-dictionary.louds'),'old dictionary')
$oldEntry=[pscustomobject]@{path='old-dictionary.louds';sha256=Get-ArtifactHash (Join-Path $trial 'old-dictionary.louds')}
Write-ArtifactJson (Join-Path $update 'update-manifest.json') @{
    schemaVersion=1; kind='incremental-trial'; build=@{version='fixture'}
    files=@(Get-ArtifactEntries $payload); removals=@($oldEntry)
}
$null=Read-TrialUpdate $update
& (Join-Path $scripts 'apply-trial-package.ps1') -UpdateDirectory $update -TrialDirectory $trial -NoRestart
Assert ([IO.File]::ReadAllText((Join-Path $trial 'frontend.exe')) -eq 'new frontend') 'partial frontend applied'
Assert ([IO.File]::ReadAllText((Join-Path $trial 'azookey-server.exe')) -eq 'keep server') 'unrelated server preserved'
Assert ([IO.File]::ReadAllText((Join-Path $trial 'package-manifest.json')) -eq 'keep full baseline') 'full baseline manifest preserved'
Assert (-not (Test-Path -LiteralPath (Join-Path $trial 'old-dictionary.louds'))) 'retired managed file backed up and removed'
$receipt=Get-Content -Encoding UTF8 -Raw -LiteralPath (Join-Path $trial 'trial-deployment.json') | ConvertFrom-Json
Assert ($receipt.mode -eq 'incremental') 'incremental update cannot claim a full package identity'
[IO.File]::WriteAllText((Join-Path $payload 'frontend.exe'),'corrupted')
Reject { Read-TrialUpdate $update } 'reject damaged partial payload'
Put 'windows/frontend/node_modules/generated.js' 'ignored'
$ignored=Get-TrialInputSnapshot $fixture $removed
Assert ($ignored.frontend.hash -eq $removed.frontend.hash) 'generated dependencies do not trigger rebuild'
Write-Host "PASS: $script:checks incremental assertions. Evidence: $fixture"
