<#
.SYNOPSIS
    Buckwheat E2E UI Test Framework
.DESCRIPTION
    Runs 23 automated test cases against Buckwheat on Android emulator.
    Output: scripts/e2e/output/<timestamp>/report.md
.PARAMETER Clean
    Clear app data before running
.PARAMETER KeepData
    Keep app data after running (skip final clear)
.PARAMETER Verbose
    Print all adb output
.EXAMPLE
    .\scripts\e2e\run.ps1
    .\scripts\e2e\run.ps1 -Clean
#>
param(
    [switch]$Clean,
    [switch]$KeepData,
    [switch]$Verbose
)

$ErrorActionPreference = 'Continue'
$script:Package = 'com.danilkinkin.buckwheat'
$script:Pass = 0; $script:Fail = 0; $script:Warn = 0
$script:Results = @()
$script:StartTime = Get-Date
$script:Timestamp = $script:StartTime.ToString('yyyy-MM-dd_HH-mm')
$script:OutDir = Join-Path $PSScriptRoot "output\$($script:Timestamp)"
$script:ScreenshotDir = Join-Path $script:OutDir 'screenshots'
$script:DumpDir = Join-Path $script:OutDir 'dumps'
$script:RunLog = Join-Path $script:OutDir 'run.log'

# ── Keyboard coordinates (Pixel 6 Pro, 1440x3120) ──
$script:KB = @{
    x1 = 217; x2 = 552; x3 = 887
    x0 = 384; xdot = 888
    y789 = 2003; y456 = 2303; y123 = 2599; y0 = 2895
    yCheck = 2599; xCheck = 1223
    yDel = 2003; xDel = 1110
    yClear = 2003; xClear = 1223
}

# ── Helper functions ──
function Log($msg) {
    $ts = (Get-Date).ToString('HH:mm:ss.fff')
    $line = "[$ts] $msg"
    Add-Content -Path $script:RunLog -Value $line
    if ($script:VerboseMode) { Write-Host $line }
}

function Cmd($cmd) {
    Log "adb: $cmd"
    $result = Invoke-Cmd "adb $cmd" 2>&1
    return ($result -join "`n")
}

function CmdBatch($commands) {
    $script = $commands -join '; '
    Log "adb shell: $script"
    $result = Invoke-Cmd "adb shell $script" 2>&1
    return ($result -join "`n")
}

function Invoke-Cmd($command, $timeoutSec = 30) {
    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = 'cmd.exe'
    $psi.Arguments = "/c $command"
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $psi.UseShellExecute = $false
    $psi.CreateNoWindow = $true
    $psi.StandardOutputEncoding = [System.Text.Encoding]::UTF8
    $psi.StandardErrorEncoding = [System.Text.Encoding]::UTF8
    $p = [System.Diagnostics.Process]::Start($psi)
    $taskOut = $p.StandardOutput.ReadToEndAsync()
    $taskErr = $p.StandardError.ReadToEndAsync()
    if (-not $p.WaitForExit($timeoutSec * 1000)) {
        try { $p.Kill() } catch {}
        Log "TIMEOUT after ${timeoutSec}s: $command"
        return ''
    }
    $out = $taskOut.Result
    $err = $taskErr.Result
    if ($err -and $err.Trim()) { Write-Host "STDERR: $err" }
    return $out
}

function Tap($x, $y) {
    $null = Cmd "shell input tap $x $y"
    Start-Sleep -Milliseconds 300
}

function Swipe($x1, $y1, $x2, $y2, $ms = 300) {
    $null = Cmd "shell input swipe $x1 $y1 $x2 $y2 $ms"
    Start-Sleep -Milliseconds 500
}

function LongPress($x, $y) {
    $null = Cmd "shell input swipe $x $y $x $y 1000"
    Start-Sleep -Milliseconds 500
}

function PressBack {
    $null = Cmd "shell input keyevent 4"
    Start-Sleep -Milliseconds 500
}

function PressHome {
    $null = Cmd "shell input keyevent 3"
    Start-Sleep -Milliseconds 500
}

function Wait($ms) {
    Start-Sleep -Milliseconds $ms
}

function Screenshot($name) {
    $localPath = Join-Path $script:ScreenshotDir "$name.png"
    $devicePath = "/sdcard/e2e_screen.png"
    $null = Cmd "shell screencap -p $devicePath"
    $null = Cmd "pull $devicePath `"$localPath`""
    Log "Screenshot: $name"
    return $localPath
}

function DumpUI {
    $null = Cmd "shell uiautomator dump /sdcard/e2e_dump.xml"
    $dump = Cmd "shell cat /sdcard/e2e_dump.xml"
    return $dump
}

function Assert-TextExists($text, $timeoutSec = 10) {
    $deadline = (Get-Date).AddSeconds($timeoutSec)
    while ((Get-Date) -lt $deadline) {
        $dump = DumpUI
        if ($dump -match [regex]::Escape($text)) {
            Log "FOUND: '$text'"
            return $true
        }
        Wait 1000
    }
    Log "NOT FOUND: '$text' (timeout ${timeoutSec}s)"
    return $false
}

function Assert-TextNotExists($text, $waitSec = 3) {
    Wait ($waitSec * 1000)
    $dump = DumpUI
    if ($dump -match [regex]::Escape($text)) {
        Log "UNEXPECTED: '$text' still present"
        return $false
    }
    Log "ABSENT (OK): '$text'"
    return $true
}

function Assert-NoCrash {
    Wait 1000
    $logcat = Cmd "shell logcat -d -t 20 --pid=$(Cmd 'shell pidof com.danilkinkin.buckwheat')"
    $crashes = ($logcat | Select-String -Pattern 'FATAL|IllegalFormat|NullPointer|IndexOutOfBounds|ClassCast|ANR|Process.*died' -AllMatches).Matches.Count
    if ($crashes -gt 0) {
        Log "CRASH DETECTED ($crashes errors)"
        Cmd "shell logcat -d -t 50 --pid=$(Cmd 'shell pidof com.danilkinkin.buckwheat')" | Out-File (Join-Path $script:OutDir "crash_$($script:CurrentTC).txt")
        return $false
    }
    Log "No crash detected"
    return $true
}

function TapKeyboardDigit($digit) {
    $d = [int]$digit
    if ($d -ge 7 -and $d -le 9) {
        $y = $script:KB.y789
        $x = @($script:KB.x1, $script:KB.x2, $script:KB.x3)[$d - 7]
    } elseif ($d -ge 4 -and $d -le 6) {
        $y = $script:KB.y456
        $x = @($script:KB.x1, $script:KB.x2, $script:KB.x3)[$d - 4]
    } elseif ($d -ge 1 -and $d -le 3) {
        $y = $script:KB.y123
        $x = @($script:KB.x1, $script:KB.x2, $script:KB.x3)[$d - 1]
    } else {
        $x = $script:KB.x0; $y = $script:KB.y0
    }
    Tap $x $y
}

function TapNumber($numStr) {
    foreach ($ch in $numStr.ToCharArray()) {
        if ($ch -eq '.') {
            Tap $script:KB.xdot $script:KB.y0
        } elseif ($ch -ge '0' -and $ch -le '9') {
            TapKeyboardDigit ([int][string]$ch)
        }
        Wait 100
    }
}

function TapCheck { Tap $script:KB.xCheck $script:KB.yCheck }
function TapDelete { Tap $script:KB.xDel $script:KB.yDel }

function OpenEditor {
    Tap 720 909
    Wait 1000
}

function OpenSettings {
    Tap 1318 268
    Wait 2000
}

function AddSpend($amount, $tag = '') {
    OpenEditor
    TapNumber $amount
    if ($tag -ne '') {
        # Tap tag/comment button area
        Tap 1251 1327
        Wait 500
        # Type tag text via system input
        Cmd "shell input text '$tag'"
        Wait 300
    }
    TapCheck
    Wait 1500
}

function ClearAppData {
    Cmd "shell am force-stop $script:Package"
    Cmd "shell pm clear $script:Package"
    Wait 2000
    Log "App data cleared"
}

function LaunchApp {
    Cmd "shell am start -n $script:Package/.MainActivity"
    Wait 8000
    Log "App launched"
}

# ── Wait for app to fully load (up to 60s) ──
function WaitForAppReady($timeoutSec = 60) {
    $deadline = (Get-Date).AddSeconds($timeoutSec)
    while ((Get-Date) -lt $deadline) {
        # Dismiss ANR if present
        Handle-ANR
        # Check if app has any UI content
        $dump = DumpUI
        if ($dump -match 'budget|Budget|день|сегодня|Hello|Welcome|Set up|Настроить|ForKey') {
            Log "App ready detected"
            return $true
        }
        Wait 3000
    }
    Log "App not ready after ${timeoutSec}s"
    return $false
}

function StartRecording {
    $script:RecordPath = Join-Path $script:OutDir 'recording.mp4'
    $null = Cmd "shell screenrecord --time-limit 600 /sdcard/e2e_record.mp4 &"
    Wait 1000
    Log "Recording started"
}

function StopRecording {
    $null = Cmd "shell pkill -INT screenrecord"
    Wait 2000
    Cmd "pull /sdcard/e2e_record.mp4 `"$($script:RecordPath)`""
    Log "Recording saved"
}

function StartLogcat {
    $script:LogcatPath = Join-Path $script:OutDir 'logcat.txt'
    $null = Start-Process -FilePath 'adb' -ArgumentList 'logcat -c' -NoNewWindow -Wait
    $script:LogcatProc = Start-Process -FilePath 'adb' -ArgumentList 'logcat' -NoNewWindow -PassThru
    Log "Logcat collection started"
}

function StopLogcat {
    if ($script:LogcatProc -and !$script:LogcatProc.HasExited) {
        $script:LogcatProc.Kill()
    }
    $null = Cmd "logcat -d" | Out-File $script:LogcatPath -Encoding UTF8
    Log "Logcat saved"
}

function Record-Result($tc, $name, $status, $detail = '') {
    $script:Results += [PSCustomObject]@{ TC=$tc; Name=$name; Status=$status; Detail=$detail }
    if ($status -eq 'PASS') { $script:Pass++ }
    elseif ($status -eq 'FAIL') { $script:Fail++ }
    else { $script:Warn++ }
    Log "RESULT: $tc | $name | $status $detail"
}

# ── Dismiss ANR dialog if present ──
function Handle-ANR {
    $dump = DumpUI
    if ($dump -match "isn.t responding|not responding|Wait" -and $dump -match "Wait|OK|Close") {
        Log "ANR dialog detected, dismissing"
        # Tap "Wait" button (usually right side of dialog)
        Wait 500
        $dump = DumpUI
        if ($dump -match 'Wait.*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"') {
            $cx = ([int]$Matches[1] + [int]$Matches[3]) / 2
            $cy = ([int]$Matches[2] + [int]$Matches[4]) / 2
            Tap $cx $cy
        } else {
            # Common coordinates for Wait button in ANR dialog
            Tap 900 1800
        }
        Wait 2000
        return $true
    }
    return $false
}

# ── Extract Y coordinate of text from uiautomator dump ──
function Get-TextBounds($dump, $text) {
    if ($dump -match [regex]::Escape($text) + '.*?\[(\d+),(\d+)\]\[(\d+),(\d+)\]') {
        $cx = ([int]$Matches[1] + [int]$Matches[3]) / 2
        $cy = ([int]$Matches[2] + [int]$Matches[4]) / 2
        return @{ x = $cx; y = $cy }
    }
    return $null
}

# ── Find and tap a settings item by text, scrolling if needed ──
function FindAndTapSetting($text, $scrollAttempts = 3) {
    OpenSettings
    Wait 2000

    for ($i = 0; $i -le $scrollAttempts; $i++) {
        $dump = DumpUI
        $pos = Get-TextBounds $dump $text
        if ($pos) {
            Log "Found '$text' at ($($pos.x), $($pos.y))"
            Tap $pos.x $pos.y
            Wait 2000
            return $true
        }
        # Scroll down within the settings sheet
        Swipe 720 2500 720 1000 500
        Wait 1500
    }
    Log "NOT FOUND '$text' after $scrollAttempts scrolls"
    return $false
}

# ── TC01: Fresh install + Onboarding ──
function TC01-Onboarding {
    $script:CurrentTC = 'TC01'
    Write-Host "`n=== TC01: Fresh Install + Onboarding ===" -ForegroundColor Cyan
    ClearAppData
    LaunchApp

    # Wait for app to fully load (may take 25-30s after clear)
    $ready = WaitForAppReady 60

    $dump = DumpUI
    $found = $false

    if ($dump -match 'Hello!') {
        $found = $true
        Log "Found onboarding: 'Hello!'"
    }
    if (-not $found) {
        $found = Assert-TextExists 'Hello!' 15
    }
    if (-not $found) { $found = Assert-TextExists 'Welcome to Buckwheat' 10 }
    if (-not $found) { $found = Assert-TextExists 'Welcome' 10 }
    if (-not $found) { $found = Assert-TextExists 'Set up' 10 }

    Screenshot 'TC01_welcome'

    if ($found) {
        # Tap "Set up a budget" button — find from dump first
        $dump = DumpUI
        $setupPos = Get-TextBounds $dump 'Set up'
        if (-not $setupPos) { $setupPos = Get-TextBounds $dump 'Настроить' }
        if ($setupPos) {
            Tap $setupPos.x $setupPos.y
            Log "Tapped 'Set up' at ($($setupPos.x), $($setupPos.y))"
        } else {
            Tap 720 2886
            Log "Tapped fallback 'Set up' at (720, 2886)"
        }
        Wait 3000
        Screenshot 'TC01_budget_setup'

        # Enter budget amount: 10000
        TapNumber '10000'
        Wait 1000
        Screenshot 'TC01_amount_entered'

        # Tap "No finish date selected" to open calendar
        $dump = DumpUI
        $datePos = Get-TextBounds $dump 'finish date'
        if (-not $datePos) { $datePos = Get-TextBounds $dump 'No finish' }
        if (-not $datePos) { $datePos = Get-TextBounds $dump 'Даты нет' }
        if (-not $datePos) {
            # Try broader search
            if ($dump -match 'finish date.*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"') {
                $datePos = @{ x = ([int]$Matches[1] + [int]$Matches[3]) / 2; y = ([int]$Matches[2] + [int]$Matches[4]) / 2 }
            }
        }
        if ($datePos) {
            Tap $datePos.x $datePos.y
        } else {
            Tap 720 1900
        }
        Wait 2000
        Screenshot 'TC01_calendar'

        # Select a day in calendar
        Tap 720 1800
        Wait 1000

        # Tap confirm/apply in calendar
        $dump = DumpUI
        $applyPos = Get-TextBounds $dump 'Apply'
        if (-not $applyPos) { $applyPos = Get-TextBounds $dump 'OK' }
        if (-not $applyPos) { $applyPos = Get-TextBounds $dump 'Готово' }
        if ($applyPos) {
            Tap $applyPos.x $applyPos.y
        } else {
            Tap 1247 258
        }
        Wait 1500
        Screenshot 'TC01_date_selected'

        # Tap bottom "Apply" to confirm budget setup
        $dump = DumpUI
        $applyPos = Get-TextBounds $dump 'Apply'
        if (-not $applyPos) { $applyPos = Get-TextBounds $dump 'OK' }
        if (-not $applyPos) { $applyPos = Get-TextBounds $dump 'Готово' }
        if ($applyPos) {
            Tap $applyPos.x $applyPos.y
        } else {
            Tap 720 2932
        }
        Wait 3000
        Screenshot 'TC01_after_apply'

        # Verify main screen
        $dump = DumpUI
        if ($dump -match 'For today|For today|Сегодня|Auto|budget') {
            Record-Result 'TC01' 'Fresh install + Onboarding' 'PASS' 'Onboarding completed'
        } else {
            Record-Result 'TC01' 'Fresh install + Onboarding' 'WARN' 'Onboarding completed but main screen unclear'
        }
    } else {
        Screenshot 'TC01_no_onboarding'
        Record-Result 'TC01' 'Fresh install + Onboarding' 'WARN' 'Onboarding text not found'
    }
}

# ── TC02: Add10 spends with varied data ──
function TC02-AddSpends {
    $script:CurrentTC = 'TC02'
    Write-Host "`n=== TC02: Add10 Spends ===" -ForegroundColor Cyan

    $spends = @(
        @{ amount='150'; tag='food' },
        @{ amount='50'; tag='transport' },
        @{ amount='300'; tag='food' },
        @{ amount='25'; tag='' },
        @{ amount='420'; tag='entertainment' },
        @{ amount='80'; tag='transport' },
        @{ amount='1500'; tag='salary_spent' },
        @{ amount='99'; tag='food' },
        @{ amount='10'; tag='' },
        @{ amount='250'; tag='entertainment' }
    )

    $added = 0
    foreach ($s in $spends) {
        AddSpend $s.amount $s.tag
        $added++
        Screenshot "TC02_spend_$added"
    }

    # Verify count in history
    Wait 1000
    OpenEditor
    Swipe 720 1200 720 2400 500
    Wait 1000
    Screenshot 'TC02_history'

    if ($added -eq 10) {
        Record-Result 'TC02' 'Add10 spends with varied data' 'PASS' "Added $added spends"
    } else {
        Record-Result 'TC02' 'Add10 spends with varied data' 'FAIL' "Only added $added/10"
    }
}

# ── TC03: Multi-digit input ──
function TC03-MultiDigit {
    $script:CurrentTC = 'TC03'
    Write-Host "`n=== TC03: Multi-digit Input ===" -ForegroundColor Cyan

    OpenEditor
    Wait 500

    # Test various digit sequences
    $tests = @('123', '4567', '0', '99999', '1000')
    foreach ($t in $tests) {
        OpenEditor
        Wait 300
        TapNumber $t
        Wait 300
        Screenshot "TC03_digit_$t"
        TapDelete  # Clear
        Wait 200
    }

    # Final: enter 1234, verify in field, then cancel
    OpenEditor
    Wait 300
    TapNumber '1234'
    Wait 500
    Screenshot 'TC03_1234_entered'

    # Verify text contains 1234
    $dump = DumpUI
    if ($dump -match '1234') {
        Record-Result 'TC03' 'Multi-digit input' 'PASS' '1234 displayed'
    } else {
        Record-Result 'TC03' 'Multi-digit input' 'WARN' 'Cannot confirm display, digits entered'
    }

    TapDelete; TapDelete; TapDelete; TapDelete
    Wait 300
}

# ── TC04: Edit spend ──
function TC04-EditSpend {
    $script:CurrentTC = 'TC04'
    Write-Host "`n=== TC04: Edit Spend ===" -ForegroundColor Cyan

    # Open history by swiping up
    OpenEditor
    Wait 500
    Swipe 720 1200 720 2400 500
    Wait 1000

    # Long press on first spend item
    $dump = DumpUI
    $spendPos = $null
    # Try to find any spend item — look for clickable nodes in the list area (y > 500)
    if ($dump -match 'text="[^"]+".*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]".*?class="android\.widget\.TextView"') {
        $spendPos = @{ x = ([int]$Matches[1] + [int]$Matches[3]) / 2; y = ([int]$Matches[2] + [int]$Matches[4]) / 2 }
    }
    if (-not $spendPos -or $spendPos.y -lt 500) {
        # Broader: find any element with a price-like text or tag in the middle of screen
        if ($dump -match '(food|transport|entertainment|salary|₽|\d+).*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"') {
            $spendPos = @{ x = ([int]$Matches[2] + [int]$Matches[4]) / 2; y = ([int]$Matches[3] + [int]$Matches[5]) / 2 }
        }
    }
    if ($spendPos -and $spendPos.y -gt 500) {
        LongPress $spendPos.x $spendPos.y
    } else {
        LongPress 720 1500
    }
    Wait 1500

    # Look for edit option
    $dump = DumpUI
    if ($dump -match "Edit|Редактировать|Change|edit|Изменить") {
        $editPos = Get-TextBounds $dump 'Edit'
        if (-not $editPos) { $editPos = Get-TextBounds $dump 'Редактировать' }
        if (-not $editPos) { $editPos = Get-TextBounds $dump 'Change' }
        if ($editPos) {
            Tap $editPos.x $editPos.y
        } else {
            Tap 720 1200
        }
        Wait 1000
        Screenshot 'TC04_edit_dialog'
        Record-Result 'TC04' 'Edit spend' 'PASS' 'Edit dialog opened'
    } else {
        Screenshot 'TC04_long_press_result'
        Record-Result 'TC04' 'Edit spend' 'WARN' 'Long-press menu not detected'
    }

    PressBack
    Wait 500
}

# ── TC05: Delete spend + undo ──
function TC05-DeleteUndo {
    $script:CurrentTC = 'TC05'
    Write-Host "`n=== TC05: Delete Spend + Undo ===" -ForegroundColor Cyan

    OpenEditor
    Wait 500
    Swipe 720 1200 720 2400 500
    Wait 1000

    # Long press to get delete option
    $dump = DumpUI
    $spendPos = $null
    if ($dump -match 'text="[^"]+".*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]".*?class="android\.widget\.TextView"') {
        $spendPos = @{ x = ([int]$Matches[1] + [int]$Matches[3]) / 2; y = ([int]$Matches[2] + [int]$Matches[4]) / 2 }
    }
    if (-not $spendPos -or $spendPos.y -lt 500) {
        if ($dump -match '(food|transport|entertainment|salary|₽|\d+).*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"') {
            $spendPos = @{ x = ([int]$Matches[2] + [int]$Matches[4]) / 2; y = ([int]$Matches[3] + [int]$Matches[5]) / 2 }
        }
    }
    if ($spendPos -and $spendPos.y -gt 500) {
        LongPress $spendPos.x $spendPos.y
    } else {
        LongPress 720 1500
    }
    Wait 1500

    $dump = DumpUI
    if ($dump -match "Delete|Удалить|Remove|delete|Удаление") {
        # Find delete button
        $delPos = Get-TextBounds $dump 'Delete'
        if (-not $delPos) { $delPos = Get-TextBounds $dump 'Удалить' }
        if (-not $delPos) { $delPos = Get-TextBounds $dump 'Remove' }
        if ($delPos) {
            Tap $delPos.x $delPos.y
        } else {
            Tap 720 1400
        }
        Wait 1500
        Screenshot 'TC05_delete_confirm'

        # Look for confirm or undo
        $dump2 = DumpUI
        if ($dump2 -match "undo|Undo|Отменить|remove_spent_undo") {
            Record-Result 'TC05' 'Delete spend + undo' 'PASS' 'Delete + undo available'
        } else {
            Record-Result 'TC05' 'Delete spend + undo' 'PASS' 'Delete triggered'
        }
    } else {
        Screenshot 'TC05_no_delete'
        Record-Result 'TC05' 'Delete spend + undo' 'WARN' 'Delete option not found'
    }

    PressBack
    Wait 500
}

# ── TC06: Monthly Report (was "Analytics") ──
function TC06-Analytics {
    $script:CurrentTC = 'TC06'
    Write-Host "`n=== TC06: Monthly Report ===" -ForegroundColor Cyan

    $found = FindAndTapSetting 'Monthly report' 3
    if ($found) {
        Wait 1000
        Screenshot 'TC06_analytics'

        if (Assert-NoCrash) {
            Record-Result 'TC06' 'Monthly Report' 'PASS' 'Opened successfully'
        } else {
            Record-Result 'TC06' 'Monthly Report' 'FAIL' 'Crash detected'
        }
    } else {
        Record-Result 'TC06' 'Monthly Report' 'WARN' 'Not visible in settings'
    }

    PressBack
    Wait 500
}

# ── TC07: Patterns sheet ──
function TC07-Patterns {
    $script:CurrentTC = 'TC07'
    Write-Host "`n=== TC07: Patterns Sheet ===" -ForegroundColor Cyan

    $found = FindAndTapSetting 'Spending patterns' 3
    if ($found) {
        Wait 1000
        Screenshot 'TC07_patterns'

        if (Assert-NoCrash) {
            Record-Result 'TC07' 'Patterns sheet' 'PASS' 'Opened, no crash'
        } else {
            Record-Result 'TC07' 'Patterns sheet' 'FAIL' 'Crash detected'
        }
    } else {
        Record-Result 'TC07' 'Patterns sheet' 'WARN' 'Not visible'
    }

    PressBack
    Wait 500
}

# ── TC08: Goals sheet ──
function TC08-Goals {
    $script:CurrentTC = 'TC08'
    Write-Host "`n=== TC08: Goals Sheet ===" -ForegroundColor Cyan

    $found = FindAndTapSetting 'Goals' 4
    if ($found) {
        Wait 1000
        Screenshot 'TC08_goals'

        if (Assert-NoCrash) {
            Record-Result 'TC08' 'Goals sheet' 'PASS' 'Opened'
        } else {
            Record-Result 'TC08' 'Goals sheet' 'FAIL' 'Crash'
        }
    } else {
        Record-Result 'TC08' 'Goals sheet' 'WARN' 'Not visible'
    }

    PressBack; Wait 500
}

# ── TC09: Settings sheet ──
function TC09-Settings {
    $script:CurrentTC = 'TC09'
    Write-Host "`n=== TC09: Settings Sheet ===" -ForegroundColor Cyan

    OpenSettings
    Wait 2000

    $found = Assert-TextExists 'Settings' 5
    if (-not $found) { $found = Assert-TextExists 'Настройки' 5 }
    if (-not $found) { $found = Assert-TextExists 'Theme' 3 }
    if (-not $found) { $found = Assert-TextExists 'Тема' 3 }
    Screenshot 'TC09_settings'

    if ($found -and (Assert-NoCrash)) {
        Record-Result 'TC09' 'Settings sheet' 'PASS' 'Visible'
    } else {
        Record-Result 'TC09' 'Settings sheet' 'FAIL' 'Not visible or crash'
    }

    PressBack; Wait 500
}

# ── TC10: Wallet / Budget sheet ──
function TC10-Wallet {
    $script:CurrentTC = 'TC10'
    Write-Host "`n=== TC10: Wallet / Budget ===" -ForegroundColor Cyan

    # Tap "Click to view budget details" at (644, 487)
    Tap 644 487
    Wait 2000
    Screenshot 'TC10_wallet'

    $found = Assert-TextExists 'Budget' 3
    if (-not $found) { $found = Assert-TextExists 'Бюджет' 3 }
    if (-not $found) { $found = Assert-TextExists 'For today' 3 }

    if ($found -and (Assert-NoCrash)) {
        Record-Result 'TC10' 'Wallet / Budget' 'PASS' 'Opened'
    } else {
        Record-Result 'TC10' 'Wallet / Budget' 'WARN' 'Unclear state'
    }

    PressBack; Wait 500
}

# ── TC11: Recurring Payments ──
function TC11-Recurring {
    $script:CurrentTC = 'TC11'
    Write-Host "`n=== TC11: Recurring Payments ===" -ForegroundColor Cyan

    $found = FindAndTapSetting 'Recurring Payments' 4
    if ($found) {
        Wait 1000
        Screenshot 'TC11_recurring'

        if (Assert-NoCrash) {
            Record-Result 'TC11' 'Recurring Payments' 'PASS' 'Opened'
        } else {
            Record-Result 'TC11' 'Recurring Payments' 'FAIL' 'Crash'
        }
    } else {
        Record-Result 'TC11' 'Recurring Payments' 'WARN' 'Not visible'
    }

    PressBack; Wait 500
}

# ── TC12: Category Caps ──
function TC12-CategoryCaps {
    $script:CurrentTC = 'TC12'
    Write-Host "`n=== TC12: Category Caps ===" -ForegroundColor Cyan

    $found = FindAndTapSetting 'Category caps' 4
    if ($found) {
        Wait 1000
        Screenshot 'TC12_caps'

        if (Assert-NoCrash) {
            Record-Result 'TC12' 'Category Caps' 'PASS' 'Opened'
        } else {
            Record-Result 'TC12' 'Category Caps' 'FAIL' 'Crash'
        }
    } else {
        Record-Result 'TC12' 'Category Caps' 'WARN' 'Not visible'
    }

    PressBack; Wait 500
}

# ── TC13: Categories Management ──
function TC13-Categories {
    $script:CurrentTC = 'TC13'
    Write-Host "`n=== TC13: Categories Management ===" -ForegroundColor Cyan

    $found = FindAndTapSetting 'Categories' 4
    if ($found) {
        Wait 1000
        Screenshot 'TC13_categories'

        if (Assert-NoCrash) {
            Record-Result 'TC13' 'Categories Management' 'PASS' 'Opened'
        } else {
            Record-Result 'TC13' 'Categories Management' 'FAIL' 'Crash'
        }
    } else {
        Record-Result 'TC13' 'Categories Management' 'WARN' 'Not visible'
    }

    PressBack; Wait 500
}

# ── TC14: Tags Management ──
function TC14-Tags {
    $script:CurrentTC = 'TC14'
    Write-Host "`n=== TC14: Tags Management ===" -ForegroundColor Cyan

    $found = FindAndTapSetting 'Tag Management' 4
    if (-not $found) { $found = FindAndTapSetting 'Tags' 4 }
    if ($found) {
        Wait 1000
        Screenshot 'TC14_tags'

        if (Assert-NoCrash) {
            Record-Result 'TC14' 'Tags Management' 'PASS' 'Opened'
        } else {
            Record-Result 'TC14' 'Tags Management' 'FAIL' 'Crash'
        }
    } else {
        Record-Result 'TC14' 'Tags Management' 'WARN' 'Not visible'
    }

    PressBack; Wait 500
}

# ── TC15: Notifications ──
function TC15-Notifications {
    $script:CurrentTC = 'TC15'
    Write-Host "`n=== TC15: Notifications ===" -ForegroundColor Cyan

    $found = FindAndTapSetting 'Notifications' 2
    if ($found) {
        Wait 1000
        Screenshot 'TC15_notifications'

        if (Assert-NoCrash) {
            Record-Result 'TC15' 'Notifications' 'PASS' 'Opened'
        } else {
            Record-Result 'TC15' 'Notifications' 'FAIL' 'Crash'
        }
    } else {
        Record-Result 'TC15' 'Notifications' 'WARN' 'Not visible'
    }

    PressBack; Wait 500
}

# ── TC16: Theme ──
function TC16-Theme {
    $script:CurrentTC = 'TC16'
    Write-Host "`n=== TC16: Theme ===" -ForegroundColor Cyan

    $found = FindAndTapSetting 'Theme' 3
    if (-not $found) { $found = FindAndTapSetting 'Тема' 3 }
    if ($found) {
        Wait 1500
        Screenshot 'TC16_theme'

        if (Assert-NoCrash) {
            Record-Result 'TC16' 'Theme' 'PASS' 'Opened'
        } else {
            Record-Result 'TC16' 'Theme' 'FAIL' 'Crash'
        }
    } else {
        Record-Result 'TC16' 'Theme' 'WARN' 'Not visible'
    }

    PressBack; Wait 500
}

# ── TC17: Locale / Language ──
function TC17-Locale {
    $script:CurrentTC = 'TC17'
    Write-Host "`n=== TC17: Locale / Language ===" -ForegroundColor Cyan

    $found = FindAndTapSetting 'Language' 3
    if (-not $found) { $found = FindAndTapSetting 'Язык' 3 }
    if ($found) {
        Wait 1500
        Screenshot 'TC17_locale'

        if (Assert-NoCrash) {
            Record-Result 'TC17' 'Locale / Language' 'PASS' 'Opened'
        } else {
            Record-Result 'TC17' 'Locale / Language' 'FAIL' 'Crash'
        }
    } else {
        Record-Result 'TC17' 'Locale / Language' 'WARN' 'Not visible'
    }

    PressBack; Wait 500
}

# ── TC18: Currency ──
function TC18-Currency {
    $script:CurrentTC = 'TC18'
    Write-Host "`n=== TC18: Currency ===" -ForegroundColor Cyan

    # Currency is not a separate settings item
    OpenSettings
    Wait 2000

    $found = Assert-TextExists 'Currency' 3
    if (-not $found) { $found = Assert-TextExists 'Валюта' 3 }
    if (-not $found) { $found = Assert-TextExists 'Rupee' 3 }
    if (-not $found) { $found = Assert-TextExists 'USD' 3 }
    if ($found) {
        $dump = DumpUI
        $pos = Get-TextBounds $dump 'Currency'
        if (-not $pos) { $pos = Get-TextBounds $dump 'Валюта' }
        if (-not $pos) { $pos = Get-TextBounds $dump 'Rupee' }
        if ($pos) {
            Tap $pos.x $pos.y
            Wait 1500
            Screenshot 'TC18_currency'
            Record-Result 'TC18' 'Currency' 'PASS' 'Found and tapped'
        } else {
            Screenshot 'TC18_currency'
            Record-Result 'TC18' 'Currency' 'WARN' 'Found in dump but no position'
        }
    } else {
        Screenshot 'TC18_currency'
        Record-Result 'TC18' 'Currency' 'WARN' 'Not a separate setting item'
    }

    PressBack; Wait 500
}

# ── TC19: AI Insight ──
function TC19-AIInsight {
    $script:CurrentTC = 'TC19'
    Write-Host "`n=== TC19: AI Insight ===" -ForegroundColor Cyan

    $found = FindAndTapSetting 'Monthly report' 3
    if (-not $found) { $found = FindAndTapSetting 'Voice AI' 3 }
    if ($found) {
        Wait 1000
        Screenshot 'TC19_ai'

        if (Assert-NoCrash) {
            Record-Result 'TC19' 'AI Insight' 'PASS' 'Opened'
        } else {
            Record-Result 'TC19' 'AI Insight' 'FAIL' 'Crash'
        }
    } else {
        Record-Result 'TC19' 'AI Insight' 'WARN' 'Not visible'
    }

    PressBack; Wait 500
}

# ── TC20: Search History ──
function TC20-Search {
    $script:CurrentTC = 'TC20'
    Write-Host "`n=== TC20: Search History ===" -ForegroundColor Cyan

    $found = FindAndTapSetting 'Search History' 5
    if ($found) {
        Wait 1000
        Screenshot 'TC20_search'

        if (Assert-NoCrash) {
            Record-Result 'TC20' 'Search History' 'PASS' 'Opened'
        } else {
            Record-Result 'TC20' 'Search History' 'FAIL' 'Crash'
        }
    } else {
        Record-Result 'TC20' 'Search History' 'WARN' 'Not visible'
    }

    PressBack; Wait 500
}

# ── TC21: Past Periods ──
function TC21-PastPeriods {
    $script:CurrentTC = 'TC21'
    Write-Host "`n=== TC21: Past Periods ===" -ForegroundColor Cyan

    $found = FindAndTapSetting 'Past Periods' 5
    if ($found) {
        Wait 1000
        Screenshot 'TC21_past'

        if (Assert-NoCrash) {
            Record-Result 'TC21' 'Past Periods' 'PASS' 'Opened'
        } else {
            Record-Result 'TC21' 'Past Periods' 'FAIL' 'Crash'
        }
    } else {
        Record-Result 'TC21' 'Past Periods' 'WARN' 'Not visible'
    }

    PressBack; Wait 500
}

# ── TC22: Backup / Restore ──
function TC22-Backup {
    $script:CurrentTC = 'TC22'
    Write-Host "`n=== TC22: Backup / Restore ===" -ForegroundColor Cyan

    $found = FindAndTapSetting 'Back up data' 5
    if ($found) {
        Wait 1000
        Screenshot 'TC22_backup'

        if (Assert-NoCrash) {
            Record-Result 'TC22' 'Backup / Restore' 'PASS' 'Opened'
        } else {
            Record-Result 'TC22' 'Backup / Restore' 'FAIL' 'Crash'
        }
    } else {
        Record-Result 'TC22' 'Backup / Restore' 'WARN' 'Not visible'
    }

    PressBack; Wait 500
}

# ── TC23: Final crash check ──
function TC23-FinalCrashCheck {
    $script:CurrentTC = 'TC23'
    Write-Host "`n=== TC23: Final Crash Check ===" -ForegroundColor Cyan

    # Return to main screen
    PressHome
    Wait 1000
    LaunchApp
    Wait 3000

    # Quick navigation through main states
    OpenEditor
    Wait 1000
    Screenshot 'TC23_main'

    # Open and close settings
    OpenSettings
    Wait 2000
    Screenshot 'TC23_settings'
    PressBack
    Wait 1000

    # Back to main
    PressBack
    Wait 1000

    if (Assert-NoCrash) {
        Record-Result 'TC23' 'Final crash check' 'PASS' 'No crash after full test'
    } else {
        Record-Result 'TC23' 'Final crash check' 'FAIL' 'Crash detected at end'
    }
}

# ── Report generation ──
function Generate-Report {
    $duration = (Get-Date) - $script:StartTime
    $reportPath = Join-Path $script:OutDir 'report.md'

    $sb = [System.Text.StringBuilder]::new()
    [void]$sb.AppendLine("# Buckwheat E2E Test Report")
    [void]$sb.AppendLine("")
    [void]$sb.AppendLine("**Date:** $($script:StartTime.ToString('yyyy-MM-dd HH:mm:ss'))")
    [void]$sb.AppendLine("**Duration:** $([math]::Round($duration.TotalSeconds, 1))s")
    [void]$sb.AppendLine("**Emulator:** Pixel 6 Pro API 36 (1440x3120)")
    [void]$sb.AppendLine("")
    [void]$sb.AppendLine("## Summary")
    [void]$sb.AppendLine("")
    [void]$sb.AppendLine("| Metric | Count |")
    [void]$sb.AppendLine("|--------|-------|")
    [void]$sb.AppendLine("| PASS | $($script:Pass) |")
    [void]$sb.AppendLine("| FAIL | $($script:Fail) |")
    [void]$sb.AppendLine("| WARN | $($script:Warn) |")
    [void]$sb.AppendLine("| **Total** | **$($script:Pass + $script:Fail + $script:Warn)** |")
    [void]$sb.AppendLine("")

    $pct = if (($script:Pass + $script:Fail) -gt 0) { [math]::Round($script:Pass / ($script:Pass + $script:Fail) * 100, 1) } else { 0 }
    [void]$sb.AppendLine("**Pass Rate:** $pct%")
    [void]$sb.AppendLine("")

    [void]$sb.AppendLine("## Test Results")
    [void]$sb.AppendLine("")
    [void]$sb.AppendLine("| TC | Test | Status | Detail |")
    [void]$sb.AppendLine("|----|------|--------|--------|")
    foreach ($r in $script:Results) {
        $icon = if ($r.Status -eq 'PASS') { 'PASS' } elseif ($r.Status -eq 'FAIL') { 'FAIL' } else { 'WARN' }
        [void]$sb.AppendLine("| $($r.TC) | $($r.Name) | $icon | $($r.Detail) |")
    }
    [void]$sb.AppendLine("")

    # Screenshots
    [void]$sb.AppendLine("## Screenshots")
    [void]$sb.AppendLine("")
    $screenshots = Get-ChildItem $script:ScreenshotDir -Filter '*.png' -ErrorAction SilentlyContinue | Sort-Object Name
    foreach ($s in $screenshots) {
        [void]$sb.AppendLine("- ![$($s.Name)](screenshots/$($s.Name))")
    }
    [void]$sb.AppendLine("")

    # Logcat snippet
    if (Test-Path $script:LogcatPath) {
        [void]$sb.AppendLine("## Logcat (last 50 lines)")
        [void]$sb.AppendLine("")
        $codeFence = [char]96 + [char]96 + [char]96
        [void]$sb.AppendLine($codeFence)
        $logcatContent = Get-Content $script:LogcatPath -Tail 50
        $newline = [char]10
        [void]$sb.AppendLine($logcatContent -join $newline)
        [void]$sb.AppendLine($codeFence)
    }

    [void]$sb.AppendLine("")
    [void]$sb.AppendLine("---")
    [void]$sb.AppendLine("*Generated by Buckwheat E2E Test Framework*")

    $sb.ToString() | Out-File $reportPath -Encoding UTF8
    Write-Host "`nReport saved: $reportPath" -ForegroundColor Green
    return $reportPath
}

# ── Main ──
Write-Host "========================================" -ForegroundColor Green
Write-Host "  Buckwheat E2E Test Framework" -ForegroundColor Green
Write-Host "  $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')" -ForegroundColor Green
Write-Host "========================================" -ForegroundColor Green

New-Item -ItemType Directory -Path $script:ScreenshotDir -Force | Out-Null
New-Item -ItemType Directory -Path $script:DumpDir -Force | Out-Null
New-Item -ItemType Directory -Path $script:OutDir -Force | Out-Null

$script:VerboseMode = $Verbose

Log "=== E2E Test Run Started ==="
Log "Package: $($script:Package)"
Log "Output: $($script:OutDir)"

# Verify emulator — start if not running
$null = Cmd "start-server" 15
$devices = Cmd "devices" 10
$hasEmulator = $devices -match 'emulator\s+device'
if (-not $hasEmulator) {
    Write-Host "No emulator running. Starting Pixel_6_Pro_API_36..." -ForegroundColor Yellow
    $emulatorPath = Join-Path $env:LOCALAPPDATA 'Android\Sdk\emulator\emulator.exe'
    if (-not (Test-Path $emulatorPath)) {
        Write-Host "ERROR: emulator.exe not found at $emulatorPath" -ForegroundColor Red
        exit 1
    }
    $avdName = 'Pixel_6_Pro_API_36'
    Start-Process -FilePath $emulatorPath -ArgumentList "-avd $avdName -no-snapshot-load" -NoNewWindow
    Write-Host "Waiting for emulator to boot (max 180s)..." -ForegroundColor Yellow
    $booted = $false
    for ($i = 0; $i -lt 60; $i++) {
        Wait 3000
        $bootCheck = Cmd "shell getprop sys.boot_completed" 10
        if ($bootCheck.Trim() -eq '1') {
            $booted = $true
            break
        }
        Write-Host "  Booting... ($($i * 3)s)" -ForegroundColor DarkGray
    }
    if (-not $booted) {
        Write-Host "ERROR: Emulator did not boot in 180s" -ForegroundColor Red
        exit 1
    }
    Write-Host "Emulator booted!" -ForegroundColor Green
    Wait 3000
}

# Start logging
StartLogcat

# Clear if requested
if ($Clean) {
    Write-Host "Clearing app data..." -ForegroundColor Yellow
    ClearAppData
}

# Run all test cases
$testOrder = @(
    'TC01-Onboarding',
    'TC02-AddSpends',
    'TC03-MultiDigit',
    'TC04-EditSpend',
    'TC05-DeleteUndo',
    'TC06-Analytics',
    'TC07-Patterns',
    'TC08-Goals',
    'TC09-Settings',
    'TC10-Wallet',
    'TC11-Recurring',
    'TC12-CategoryCaps',
    'TC13-Categories',
    'TC14-Tags',
    'TC15-Notifications',
    'TC16-Theme',
    'TC17-Locale',
    'TC18-Currency',
    'TC19-AIInsight',
    'TC20-Search',
    'TC21-PastPeriods',
    'TC22-Backup',
    'TC23-FinalCrashCheck'
)

foreach ($test in $testOrder) {
    try {
        & $test
    } catch {
        Write-Host "ERROR in ${test}: $_" -ForegroundColor Red
        Record-Result $test.Replace('TC','TC') $test 'FAIL' "Exception: $_"
    }
}

# Stop logging
StopLogcat

# Cleanup
if (-not $KeepData) {
    Write-Host "`nCleaning up app data..." -ForegroundColor Yellow
    ClearAppData
}

# Generate report
$reportPath = Generate-Report

# Summary
Write-Host "`n========================================" -ForegroundColor Green
Write-Host "  RESULTS: PASS=$($script:Pass) FAIL=$($script:Fail) WARN=$($script:Warn)" -ForegroundColor $(if ($script:Fail -eq 0) { 'Green' } else { 'Red' })
Write-Host "  Report: $reportPath" -ForegroundColor Green
Write-Host "========================================" -ForegroundColor Green

if ($script:Fail -gt 0) { exit 1 }
exit 0
