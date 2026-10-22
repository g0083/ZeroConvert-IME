<#
.SYNOPSIS
    Register ZeroCon IME as the input method Windows restores at logon.

.DESCRIPTION
    The per-user CTF (Text Services Framework) state decides which input method
    Windows activates for a language when the user signs in.  Windows writes that
    state when the user picks an IME in the language bar, but an IME that was only
    installed (and never selected once) has no such entry, so every logon falls
    back to MS-IME and ZeroCon IME appears to "not start automatically".

    This script writes the same per-user keys Windows writes for a selected IME:

        HKCU\Software\Microsoft\CTF\TIP\{TIP}\LanguageProfile\0x00000411\{PROFILE}
            Enable = 1                     (the profile is enabled for Japanese)
        HKCU\Software\Microsoft\CTF\InputSession\00000411\{TIP}\0x00000411\{PROFILE}
            (session state for the current language)

    -SetSystemDefault additionally registers the ZeroCon IME as the Windows-wide
    default input method override.  That makes it the IME for every application
    right away, but it also overrides the language choice of other languages.
#>
param(
    [switch]$SetSystemDefault
)

$ErrorActionPreference = "Stop"

$tipClsId    = "{FFDEFE79-2FC2-11EF-B16B-94E70B2C378C}"
$profileGuid = "{FFDEFE7A-2FC2-11EF-B16B-94E70B2C378C}"
# CTF uses two different spellings of the language id: the TIP language profile tree
# uses the "0x" prefixed form, the input session tree uses the bare form.
$languageId       = "0x00000411"
$sessionLanguageId = "00000411"

$ok = $true

try {
    $profileKey = "HKCU:\Software\Microsoft\CTF\TIP\$tipClsId\LanguageProfile\$languageId\$profileGuid"
    if (-not (Test-Path -LiteralPath $profileKey)) {
        New-Item -Path $profileKey -Force | Out-Null
    }
    New-ItemProperty -Path $profileKey -Name "Enable" -Value 1 -PropertyType DWord -Force | Out-Null
    Write-Host "  -> CTF language profile : enabled ($profileGuid)" -ForegroundColor Green
} catch {
    $ok = $false
    Write-Warning "  -> CTF language profile registration FAILED: $($_.Exception.Message)"
}

try {
    $sessionKey = "HKCU:\Software\Microsoft\CTF\InputSession\$sessionLanguageId\$tipClsId\$languageId\$profileGuid"
    if (-not (Test-Path -LiteralPath $sessionKey)) {
        New-Item -Path $sessionKey -Force | Out-Null
    }
    Write-Host "  -> CTF input session    : registered" -ForegroundColor Green
} catch {
    $ok = $false
    Write-Warning "  -> CTF input session registration FAILED: $($_.Exception.Message)"
}

if ($SetSystemDefault) {
    try {
        $inputTip = "0411:$tipClsId$profileGuid"
        Set-WinDefaultInputMethodOverride -InputTip $inputTip -ErrorAction Stop
        Write-Host "  -> Windows default IME  : set to ZeroCon IME" -ForegroundColor Green
    } catch {
        $ok = $false
        Write-Warning "  -> Windows default IME override FAILED: $($_.Exception.Message)"
    }
}

if (-not $ok) { exit 1 }
