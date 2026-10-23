$ErrorActionPreference = 'Stop'
$helper = Join-Path $PSScriptRoot '..\build-installer-sources.ps1'
$tokens = $null
$errors = $null
$ast = [Management.Automation.Language.Parser]::ParseFile($helper, [ref]$tokens, [ref]$errors)
if ($errors.Count) { throw 'Build helper has syntax errors.' }
$step = $ast.Find({ param($node)
    $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq 'Invoke-BuildStep'
}, $false)
if (-not $step) { throw 'Build step function missing.' }
# Exercise real native exit codes without compiling or changing build outputs.
Invoke-Expression $step.Extent.Text
$originalLocation = (Get-Location).Path
Invoke-BuildStep $PSScriptRoot 'powershell.exe' @('-NoProfile', '-Command', 'exit 0')
if ((Get-Location).Path -ne $originalLocation) { throw 'Success did not restore location.' }
$failed = $false
try { Invoke-BuildStep $PSScriptRoot 'powershell.exe' @('-NoProfile', '-Command', 'exit 23') }
catch {
    if ($_.Exception.Message -notlike 'Build failed (23):*') { throw }
    $failed = $true
}
if (-not $failed) { throw 'Native build failure was ignored.' }
if ((Get-Location).Path -ne $originalLocation) { throw 'Failure did not restore location.' }
Write-Host 'PASS: native success, native failure, location restoration.'
