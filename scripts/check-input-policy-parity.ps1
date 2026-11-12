# Verifies that the input policy has one definition and that the Android
# fallback copy still matches it.
#
#   tests/input-policy/cases.tsv                <- behaviour specification
#   tests/input-policy/auto-commit-cases.tsv    <- live-conversion commit scenarios
#   windows/crates/shared/src/conversion_policy.rs   <- canonical rule
#   android/.../InputPolicy.kt                  <- Android fallback tables
#   android/.../AutoCommitAndPipelineTest.kt     <- the Android-side input shape
#
# Run this after editing any of those. `-SkipTests` only checks the tables; the
# default also runs the JVM and Rust scenarios that read the same files.
param(
    [switch]$SkipTests,
    [string]$Gradle = 'gradlew.bat'
)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$casesPath = Join-Path $root 'tests/input-policy/cases.tsv'
$autoCommitPath = Join-Path $root 'tests/input-policy/auto-commit-cases.tsv'
$rustPath = Join-Path $root 'windows/crates/shared/src/conversion_policy.rs'
$kotlinPath = Join-Path $root 'android/app/src/main/java/com/zeroconvert/ime/InputPolicy.kt'
$kotlinAutoCommitPath = Join-Path $root 'android/app/src/test/java/com/zeroconvert/ime/AutoCommitAndPipelineTest.kt'
$docsPath = Join-Path $root 'docs/investigations/input-policy-sharing-plan.md'

foreach ($required in @($casesPath, $autoCommitPath, $rustPath, $kotlinPath, $kotlinAutoCommitPath)) {
    if (!(Test-Path -LiteralPath $required)) { throw "Missing input policy source: $required" }
}
$failures = [System.Collections.Generic.List[string]]::new()
function Fail([string]$message) { $script:failures.Add($message) }

# ---- 1. Scenario table ------------------------------------------------------
$knownOperations = @('immediate', 'punctuation', 'compose_punctuation',
    'compose_punctuation_pending', 'digit', 'translation_literal', 'width')
$expectedValues = @{
    immediate = @('commit', 'compose')
    compose_punctuation = @('compose', 'commit')
    compose_punctuation_pending = @('compose', 'commit')
    digit = @('compose', 'commit')
    translation_literal = @('literal', 'plain')
    width = @('full', 'half', 'none')
}
$ids = [System.Collections.Generic.HashSet[string]]::new()
$operationsSeen = [System.Collections.Generic.HashSet[string]]::new()
$caseCount = 0
$lineNumber = 0
foreach ($raw in Get-Content -LiteralPath $casesPath -Encoding UTF8) {
    $lineNumber++
    $line = $raw.TrimEnd("`r")
    if ($line.Trim().Length -eq 0 -or $line.StartsWith('#')) { continue }
    $columns = $line.Split("`t")
    if ($columns.Count -ne 4) {
        Fail "cases.tsv:${lineNumber}: expected 4 tab-separated columns, found $($columns.Count)"
        continue
    }
    $caseCount++
    $id, $operation, $input, $expect = $columns
    if (!$ids.Add($id)) { Fail "cases.tsv:${lineNumber}: duplicate id '$id'" }
    if ($operation -notin $knownOperations) { Fail "cases.tsv:${lineNumber}: unknown operation '$operation'" }
    [void]$operationsSeen.Add($operation)
    if ($input.Length -ne 1) { Fail "cases.tsv:${lineNumber}: input '$input' must be one character" }
    if ($operation -eq 'punctuation') { continue }
    if ($expectedValues.ContainsKey($operation) -and $expect -notin $expectedValues[$operation]) {
        Fail "cases.tsv:${lineNumber}: '$expect' is not valid for '$operation'"
    }
}
foreach ($operation in $knownOperations) {
    if (!$operationsSeen.Contains($operation)) { Fail "cases.tsv: no cases for '$operation'" }
}
if ($caseCount -lt 40) { Fail "cases.tsv: only $caseCount cases; the policy surface is larger" }

# ---- 2. Canonical Rust definitions ------------------------------------------
$rust = Get-Content -LiteralPath $rustPath -Raw -Encoding UTF8
foreach ($name in @('is_immediate_commit_char', 'punctuation_for_reading',
        'should_compose_punctuation', 'is_composition_digit',
        'is_translation_literal_char', 'symbol_default_fullwidth')) {
    if ($rust -notmatch "pub fn $name\b") { Fail "conversion_policy.rs: missing pub fn $name" }
}
foreach ($name in @('CHARACTER_WIDTH_SYMBOL_DEFAULTS', 'IMMEDIATE_COMMIT_CHARS',
        'PUNCTUATION_FOR_READING', 'INPUT_POLICY_VERSION')) {
    if ($rust -notmatch "pub const $name\b") { Fail "conversion_policy.rs: missing pub const $name" }
}
if ($rust -notmatch 'include_str!\("\.\./\.\./\.\./\.\./tests/input-policy/cases\.tsv"\)') {
    Fail 'conversion_policy.rs: the TSV test must include tests/input-policy/cases.tsv'
}

function Get-TableBlock([string]$source, [string]$declaration, [string]$terminator) {
    $start = $source.IndexOf($declaration)
    if ($start -lt 0) { return $null }
    $end = $source.IndexOf($terminator, $start)
    if ($end -lt 0) { return $null }
    return $source.Substring($start, $end - $start)
}

$widthBlock = Get-TableBlock $rust 'pub const CHARACTER_WIDTH_SYMBOL_DEFAULTS' '];'
if ($null -eq $widthBlock) { Fail 'conversion_policy.rs: CHARACTER_WIDTH_SYMBOL_DEFAULTS table not found' }
$rustWidths = [ordered]@{}
if ($null -ne $widthBlock) {
    foreach ($match in [regex]::Matches($widthBlock, '\("((?:[^"\\]|\\.)*)",\s*(true|false)\)')) {
        $symbol = $match.Groups[1].Value -replace '\\"', '"' -replace '\\\\', '\'
        if ($rustWidths.Contains($symbol)) { Fail "conversion_policy.rs: duplicate width symbol '$symbol'" }
        $rustWidths[$symbol] = $match.Groups[2].Value -eq 'true'
    }
}
if ($rustWidths.Count -ne 42) { Fail "conversion_policy.rs: expected 42 width defaults, found $($rustWidths.Count)" }

$rustPunctuation = [ordered]@{}
$punctuationBlock = Get-TableBlock $rust 'pub const PUNCTUATION_FOR_READING' '];'
if ($null -ne $punctuationBlock) {
    foreach ($match in [regex]::Matches($punctuationBlock, "\('(.)',\s*'(.)'\)")) {
        $rustPunctuation[$match.Groups[1].Value] = $match.Groups[2].Value
    }
}
if ($rustPunctuation.Count -eq 0) { Fail 'conversion_policy.rs: PUNCTUATION_FOR_READING table not found' }

$rustImmediate = $null
if ($rust -match 'pub const IMMEDIATE_COMMIT_CHARS: &str = "([^"]*)"') {
    $rustImmediate = $Matches[1]
} else {
    Fail 'conversion_policy.rs: IMMEDIATE_COMMIT_CHARS value not found'
}

# ---- 3. Android fallback tables --------------------------------------------
$kotlin = Get-Content -LiteralPath $kotlinPath -Raw -Encoding UTF8
$kotlinWidthBlock = Get-TableBlock $kotlin 'DEFAULT_SYMBOL_FULLWIDTH' "
    )"
$kotlinWidths = [ordered]@{}
if ($null -ne $kotlinWidthBlock) {
    foreach ($match in [regex]::Matches($kotlinWidthBlock, '"((?:[^"\\]|\\.)*)"\s+to\s+(true|false)')) {
        $symbol = $match.Groups[1].Value -replace '\\"', '"' -replace '\\\\', '\'
        if ($kotlinWidths.Contains($symbol)) { Fail "InputPolicy.kt: duplicate width symbol '$symbol'" }
        $kotlinWidths[$symbol] = $match.Groups[2].Value -eq 'true'
    }
}
if ($kotlinWidths.Count -ne $rustWidths.Count) {
    Fail "InputPolicy.kt: $($kotlinWidths.Count) width defaults, Rust has $($rustWidths.Count)"
} else {
    foreach ($symbol in $rustWidths.Keys) {
        if (!$kotlinWidths.Contains($symbol)) { Fail "InputPolicy.kt: missing width symbol '$symbol'"; continue }
        if ($kotlinWidths[$symbol] -ne $rustWidths[$symbol]) {
            Fail "InputPolicy.kt: '$symbol' is $($kotlinWidths[$symbol]), Rust says $($rustWidths[$symbol])"
        }
    }
    foreach ($symbol in $kotlinWidths.Keys) {
        if (!$rustWidths.Contains($symbol)) { Fail "InputPolicy.kt: '$symbol' is not in the Rust table" }
    }
}

$kotlinPunctuationBlock = Get-TableBlock $kotlin 'DEFAULT_PUNCTUATION' "
    )"
$kotlinPunctuation = [ordered]@{}
if ($null -ne $kotlinPunctuationBlock) {
    foreach ($match in [regex]::Matches($kotlinPunctuationBlock, "'(.)'\s+to\s+'(.)'")) {
        $kotlinPunctuation[$match.Groups[1].Value] = $match.Groups[2].Value
    }
}
if ($kotlinPunctuation.Count -ne $rustPunctuation.Count) {
    Fail "InputPolicy.kt: $($kotlinPunctuation.Count) punctuation entries, Rust has $($rustPunctuation.Count)"
} else {
    foreach ($from in $rustPunctuation.Keys) {
        if (!$kotlinPunctuation.Contains($from)) { Fail "InputPolicy.kt: missing punctuation '$from'"; continue }
        if ($kotlinPunctuation[$from] -ne $rustPunctuation[$from]) {
            Fail "InputPolicy.kt: '$from' maps to '$($kotlinPunctuation[$from])', Rust says '$($rustPunctuation[$from])'"
        }
    }
}

if ($null -ne $rustImmediate) {
    if ($kotlin -notmatch 'DEFAULT_IMMEDIATE_COMMIT\s*=\s*"([^"]*)"') {
        Fail 'InputPolicy.kt: DEFAULT_IMMEDIATE_COMMIT value not found'
    } elseif ($Matches[1] -ne $rustImmediate) {
        Fail "InputPolicy.kt: immediate commit '$($Matches[1])' differs from Rust '$rustImmediate'"
    }
}

if ($kotlin -notmatch 'const val VERSION = (\d+)') {
    Fail 'InputPolicy.kt: VERSION not found'
} else {
    $kotlinVersion = $Matches[1]
    if ($rust -match 'pub const INPUT_POLICY_VERSION: u32 = (\d+);') {
        if ($Matches[1] -ne $kotlinVersion) {
            Fail "InputPolicy.kt: VERSION $kotlinVersion differs from INPUT_POLICY_VERSION $($Matches[1])"
        }
    } else {
        Fail 'conversion_policy.rs: INPUT_POLICY_VERSION value not found'
    }
}

# ---- 4. Auto-commit scenario table -----------------------------------------
$autoIds = [System.Collections.Generic.HashSet[string]]::new()
$autoCount = 0

$autoLineNumber = 0
foreach ($raw in Get-Content -LiteralPath $autoCommitPath -Encoding UTF8) {
    $autoLineNumber++
    $line = $raw.TrimEnd("`r")
    if ($line.Trim().Length -eq 0 -or $line.StartsWith('#')) { continue }
    $columns = $line.Split("`t")
    if ($columns.Count -ne 5) {
        Fail "auto-commit-cases.tsv:${autoLineNumber}: expected 5 tab-separated columns, found $($columns.Count)"
        continue
    }
    $autoCount++
    $id, $reading, $preview, $history, $expect = $columns
    if (!$autoIds.Add($id)) { Fail "auto-commit-cases.tsv:${autoLineNumber}: duplicate id '$id'" }
    if ($expect -ne 'none' -and $expect -notmatch '^\d+:.+$') {
        Fail "auto-commit-cases.tsv:${autoLineNumber}: '$expect' is neither none nor <length>:<surface>"
    }
    $entries = $history.Split(';')
    if ($entries.Count -lt 5) {
        Fail "auto-commit-cases.tsv:${autoLineNumber}: a stable history needs at least five entries"
    }
    $lastSurface = $null
    foreach ($entry in $entries) {
        $parts = $entry.Split(':', 2)
        if ($parts.Count -ne 2 -or $parts[0] -notmatch '^\d+$') {
            Fail "auto-commit-cases.tsv:${autoLineNumber}: bad history entry '$entry'"
            continue
        }
        $count = [int]$parts[0]
        if ($count -gt $reading.Length) {
            Fail "auto-commit-cases.tsv:${autoLineNumber}: reading count $count exceeds '$reading'"
        }
        if (!$preview.StartsWith($parts[1])) {
            Fail "auto-commit-cases.tsv:${autoLineNumber}: surface '$($parts[1])' is not a prefix of the preview"
        }
        $lastSurface = $parts[1]
    }
    if ($null -ne $lastSurface -and $lastSurface -ne $preview) {
        Fail "auto-commit-cases.tsv:${autoLineNumber}: the last history surface must equal the preview"
    }
}
if ($autoCount -lt 6) { Fail "auto-commit-cases.tsv: only $autoCount scenarios" }

# ---- 5. Plan document still lists the same functions -----------------------
if (Test-Path -LiteralPath $docsPath) {
    $plan = Get-Content -LiteralPath $docsPath -Raw -Encoding UTF8
    foreach ($name in @('is_immediate_commit_char', 'punctuation_for_reading',
            'should_compose_punctuation', 'is_composition_digit',
            'is_translation_literal_char', 'symbol_default_fullwidth')) {
        if ($plan -notmatch [regex]::Escape("pub fn $name")) {
            Fail "input-policy-sharing-plan.md: missing 'pub fn $name'"
        }
    }
}

if ($failures.Count -gt 0) {
    Write-Host 'Input policy parity failed:' -ForegroundColor Red
    foreach ($failure in $failures) { Write-Host "  - $failure" }
    exit 1
}
Write-Host "Input policy tables match ($caseCount scenarios, $($rustWidths.Count) symbols, $($rustPunctuation.Count) punctuation mappings)."
Write-Host "Auto-commit scenarios validated ($autoCount cases)."

# ---- 6. Behaviour scenarios -------------------------------------------------
if (!$SkipTests) {
    Push-Location (Join-Path $root 'android')
    try {
        & (Join-Path (Get-Location) $Gradle) ':rebuilt:testDebugUnitTest' '--tests' 'com.zeroconvert.ime.InputPolicyScenarioTest' '--tests' 'com.zeroconvert.ime.AutoCommitAndPipelineTest' '-x' ':rebuilt:verifySharedEngine'
        if ($LASTEXITCODE -ne 0) { throw 'Android input policy scenarios failed.' }
    } finally {
        Pop-Location
    }
    Push-Location (Join-Path $root 'windows')
    try {
        & cargo test -p shared --offline --lib conversion_policy
        if ($LASTEXITCODE -ne 0) { throw 'Shared Rust input policy scenarios failed.' }
    } finally {
        Pop-Location
    }
}
Write-Host 'Input policy parity verified.'





