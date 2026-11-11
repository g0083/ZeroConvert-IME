param([string]$Out = 'artifacts/logs/windows-ime-input-policy.txt')
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing
Add-Type @'
using System;
using System.Runtime.InteropServices;
using System.Text;
public static class ZeroConKeys {
  [StructLayout(LayoutKind.Sequential)] public struct KEYBDINPUT { public ushort wVk; public ushort wScan; public uint dwFlags; public uint time; public IntPtr dwExtraInfo; }
  [StructLayout(LayoutKind.Explicit, Size=40)] public struct INPUT { [FieldOffset(0)] public uint type; [FieldOffset(8)] public KEYBDINPUT ki; }
  [DllImport("user32.dll", SetLastError=true)] public static extern uint SendInput(uint n, INPUT[] inputs, int size);
  [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
  [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr h);
  [DllImport("user32.dll")] public static extern bool BringWindowToTop(IntPtr h);
  [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr h, int cmd);
  [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr h, out uint pid);
  [DllImport("kernel32.dll")] public static extern uint GetCurrentThreadId();
  [DllImport("user32.dll")] public static extern bool AttachThreadInput(uint a, uint b, bool attach);
  [DllImport("user32.dll")] static extern int GetWindowText(IntPtr h, StringBuilder s, int n);
  const uint UP = 0x0002;
  static void One(ushort vk, bool up) {
    var a = new INPUT[1]; a[0].type = 1; a[0].ki.wVk = vk;
    if (up) a[0].ki.dwFlags = UP;
    SendInput(1, a, Marshal.SizeOf(typeof(INPUT)));
  }
  public static void Key(ushort vk) {
    One(vk, false); System.Threading.Thread.Sleep(28);
    One(vk, true);  System.Threading.Thread.Sleep(28);
  }
  public static void Shifted(ushort vk) {
    One(0x10, false); System.Threading.Thread.Sleep(20);
    Key(vk);
    One(0x10, true); System.Threading.Thread.Sleep(20);
  }
  public static IntPtr ForegroundPid(out uint pid) {
    IntPtr h = GetForegroundWindow();
    GetWindowThreadProcessId(h, out pid);
    return h;
  }
  public static string Title(IntPtr h) { var s = new StringBuilder(512); GetWindowText(h, s, 512); return s.ToString(); }
  public static bool Activate(IntPtr hwnd) {
    uint foregroundPid;
    IntPtr foreground = ForegroundPid(out foregroundPid);
    uint currentThread = GetCurrentThreadId();
    uint foregroundThread = GetWindowThreadProcessId(foreground, out foregroundPid);
    ShowWindow(hwnd, 9);
    bool attached = foregroundThread != 0 && foregroundThread != currentThread &&
        AttachThreadInput(currentThread, foregroundThread, true);
    try { BringWindowToTop(hwnd); SetForegroundWindow(hwnd); }
    finally { if (attached) AttachThreadInput(currentThread, foregroundThread, false); }
    return GetForegroundWindow() == hwnd;
  }
}
'@

$log = New-Object System.Collections.Generic.List[string]
function Note([string]$text) { $script:log.Add($text); Write-Host $text }
function Save-Log {
    $target = Join-Path (Get-Location) $Out
    New-Item -ItemType Directory -Force -Path (Split-Path $target) | Out-Null
    $log | Set-Content -Path $target -Encoding UTF8
    Write-Host "Saved: $target"
}

$note = $null
$deadline = (Get-Date).AddSeconds(15)
while (-not $note -and (Get-Date) -lt $deadline) {
    $candidate = Get-Process notepad -ErrorAction SilentlyContinue |
        Where-Object { $_.MainWindowHandle -ne 0 -and $_.MainWindowTitle -match 'メモ帳|Notepad' } |
        Select-Object -First 1
    if ($candidate) { $note = $candidate; break }
    if (-not (Get-Process notepad -ErrorAction SilentlyContinue)) { Start-Process notepad.exe | Out-Null }
    Start-Sleep -Milliseconds 400
}
if (-not $note) { Note 'STOP: no Notepad window with a handle was found.'; Save-Log; exit 4 }
$target = $note.MainWindowHandle
[void][ZeroConKeys]::Activate($target)
Start-Sleep -Milliseconds 500
$targetProcess = $note.Id
Note "Target window: pid=$($note.Id) title=[$($note.MainWindowTitle)]"
$deadline = (Get-Date).AddSeconds(15)
while ((Get-Date) -lt $deadline) {
    $foregroundPid = 0
    [void][ZeroConKeys]::ForegroundPid([ref]$foregroundPid)
    if ($foregroundPid -eq $targetProcess) { break }
    [void][ZeroConKeys]::Activate($target)
    Start-Sleep -Milliseconds 400
}
$foregroundPid = 0
[void][ZeroConKeys]::ForegroundPid([ref]$foregroundPid)
if ($foregroundPid -ne $targetProcess) {
    Note "STOP: Notepad (pid $targetProcess) could not hold the foreground; nothing was typed."
    $note | Stop-Process -Force -ErrorAction SilentlyContinue
    Save-Log
    exit 3
}

$VK = @{ A = 0x41; N = 0x4E; D5 = 0x35; D1 = 0x31; D2 = 0x32; COMMA = 0xBC; PERIOD = 0xBE; QUESTION = 0xBF; ENTER = 0x0D; KANJI = 0x19; ESC = 0x1B }

function Assert-Target([string]$phase) {
    $foregroundPid = 0
    $handle = [ZeroConKeys]::ForegroundPid([ref]$foregroundPid)
    if ($foregroundPid -ne $targetProcess -or $handle -ne $target) {
        throw "Aborted during ${phase}: foreground moved to [$([ZeroConKeys]::Title($handle))]."
    }
}
function Send-Char([uint16]$vk) {
    Assert-Target 'typing'
    [ZeroConKeys]::Key($vk)
    Start-Sleep -Milliseconds 35
    Assert-Target 'typing'
}
function Send-Shifted([uint16]$vk) {
    Assert-Target 'typing'
    [ZeroConKeys]::Shifted($vk)
    Start-Sleep -Milliseconds 35
    Assert-Target 'typing'
}
Add-Type -AssemblyName UIAutomationClient
Add-Type -AssemblyName UIAutomationTypes
function Read-Doc {
    try {
        $element = [System.Windows.Automation.AutomationElement]::FromHandle($target)
        $condition = New-Object System.Windows.Automation.PropertyCondition([System.Windows.Automation.AutomationElement]::ControlTypeProperty, [System.Windows.Automation.ControlType]::Document)
        $document = $element.FindFirst([System.Windows.Automation.TreeScope]::Descendants, $condition)
        if (-not $document) { $document = $element }
        $pattern = $document.GetCurrentPattern([System.Windows.Automation.ValuePattern]::Pattern)
        return $pattern.Current.Value
    } catch {
        return "<unreadable: $($_.Exception.Message)>"
    }
}
function Clear-Doc {
    Assert-Target 'clearing'
    [ZeroConKeys]::Key($VK.ESC)
    [ZeroConKeys]::Key($VK.ESC)
    Start-Sleep -Milliseconds 150
    [System.Windows.Forms.SendKeys]::SendWait('^a')
    Start-Sleep -Milliseconds 80
    [ZeroConKeys]::Key(0x2E)
    Start-Sleep -Milliseconds 200
}

Send-Char $VK.KANJI
Start-Sleep -Milliseconds 600
Clear-Doc
Send-Char $VK.A
Start-Sleep -Milliseconds 250
$probe = Read-Doc
[ZeroConKeys]::Key($VK.ESC); Start-Sleep -Milliseconds 200
Note "IME activation probe: [$probe]"
if ($probe -notmatch 'あ') {
    Note 'STOP: ZeroCon IME did not produce あ in Notepad; no input cases were run.'
    $note | Stop-Process -Force -ErrorAction SilentlyContinue
    Save-Log
    exit 2
}

$cases = @(
    @{ Name = 'comma_after_kana';    First = 'a'; Second = @('vk', $VK.COMMA);      Expect = 'あ、' },
    @{ Name = 'period_after_kana';   First = 'a'; Second = @('vk', $VK.PERIOD);     Expect = 'あ。' },
    @{ Name = 'question_after_kana'; First = 'a'; Second = @('shift', $VK.QUESTION); Expect = 'あ？' },
    @{ Name = 'digit_after_kana';    First = 'a'; Second = @('vk', $VK.D5);         Expect = 'あ5' },
    @{ Name = 'exclaim_after_kana';  First = 'a'; Second = @('shift', $VK.D1);      Expect = 'あ！' },
    @{ Name = 'pending_n_comma';     First = 'n'; Second = @('vk', $VK.COMMA);      Expect = 'ん、' },
    @{ Name = 'pending_n_digit';     First = 'n'; Second = @('vk', $VK.D5);         Expect = 'ん5' },
    @{ Name = 'lparen_after_kana';   First = 'a'; Second = @('shift', 0x38);       Expect = 'あ（' }
)

foreach ($case in $cases) {
    Clear-Doc
    $result = ''
    try {
        Send-Char ([uint16][char]$case.First.ToUpper())
        Start-Sleep -Milliseconds 180
        $composing = Read-Doc
        if ($case.Second[0] -eq 'shift') { Send-Shifted ([uint16]$case.Second[1]) } else { Send-Char ([uint16]$case.Second[1]) }
        Start-Sleep -Milliseconds 220
        $composingAfter = Read-Doc
        Send-Char $VK.ENTER
        Start-Sleep -Milliseconds 320
        $committed = Read-Doc
        $status = if ($committed -eq $case.Expect) { 'PASS' } else { 'CHECK' }
        Note ("{0,-20} composing1=[{1}] composing2=[{2}] committed=[{3}] expected=[{4}] {5}" -f $case.Name, $composing, $composingAfter, $committed, $case.Expect, $status)
    } catch {
        Note ("{0,-20} ABORTED: {1}" -f $case.Name, $_.Exception.Message)
        break
    }
}

$note | Stop-Process -Force -ErrorAction SilentlyContinue
Save-Log




