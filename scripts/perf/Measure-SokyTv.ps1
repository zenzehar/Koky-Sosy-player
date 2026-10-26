# Soky IPTV Pro: reproducible, credential-safe hardware performance capture.
# Usage: .\scripts\perf\Measure-SokyTv.ps1 -PackageName tv.sokyiptv.pro.debug -Launches 5
# Run with Android platform-tools (adb) installed. Compare against gold v0.1.11.54.
param(
    [ValidateNotNullOrEmpty()][string]$PackageName = "tv.sokyiptv.pro.debug",
    [ValidateRange(1,20)][int]$Launches = 5,
    [string]$DeviceSerial = "",
    [string]$OutputDirectory = ""
)
$ErrorActionPreference = "Stop"

$adbPath = (Get-Command adb -ErrorAction Stop).Source
$adbArgs = @()
if ($DeviceSerial) { $adbArgs = @("-s", $DeviceSerial) }

function Invoke-Adb([string[]]$Arguments) {
    $output = & $adbPath @adbArgs @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) { throw "adb failed: $($Arguments -join ' ')\n$output" }
    return ($output -join "`n")
}

if (!$OutputDirectory) {
    $safe = $PackageName -replace '[^a-zA-Z0-9._-]', '_'
    $OutputDirectory = Join-Path (Get-Location) ("soky-perf-" + $safe + "-" + (Get-Date -Format 'yyyyMMdd-HHmmss'))
}
New-Item -Path $OutputDirectory -ItemType Directory -Force | Out-Null

$device = (Invoke-Adb @("shell", "getprop", "ro.product.model")).Trim()
$android = (Invoke-Adb @("shell", "getprop", "ro.build.version.release")).Trim()
$abi = (Invoke-Adb @("shell", "getprop", "ro.product.cpu.abi")).Trim()
$activity = "$PackageName/app.sokyiptv.MainActivity"
$installed = Invoke-Adb @("shell", "pm", "path", $PackageName)
if ($installed -notmatch "package:") { throw "App package not installed: $PackageName" }

$measurements = @()
for ($i=1; $i -le $Launches; $i++) {
    # Cold process start, never delete app data or clear credentials.
    Invoke-Adb @("shell", "am", "force-stop", $PackageName) | Out-Null
    Start-Sleep -Milliseconds 700
    $out = Invoke-Adb @("shell", "am", "start", "-W", "-n", $activity)
    $total = [regex]::Match($out, 'TotalTime:\s*(\d+)')
    $this = [regex]::Match($out, 'ThisTime:\s*(\d+)')
    if (!$total.Success) { throw "Could not parse launch time; launcher output: $out" }
    $measurements += [pscustomobject]@{
        Attempt = $i; TotalTimeMs = [int]$total.Groups[1].Value
        ThisTimeMs = if ($this.Success) { [int]$this.Groups[1].Value } else { $null }
    }
    Start-Sleep -Seconds 2
}
$measurements | Export-Csv (Join-Path $OutputDirectory "launches.csv") -NoTypeInformation -Encoding UTF8

# Reset frame stats, then operate the TV manually while observing this prompt.
Invoke-Adb @("shell", "dumpsys", "gfxinfo", $PackageName, "reset") | Out-Null
Write-Host "Browse the library, switch categories and open the guide now."
Read-Host "Press Enter after your test interval to capture frame and memory metrics" | Out-Null
$frames = Invoke-Adb @("shell", "dumpsys", "gfxinfo", $PackageName, "framestats")
$memory = Invoke-Adb @("shell", "dumpsys", "meminfo", $PackageName)

# These captures contain process timings, not provider URLs, account tokens or logcat.
Set-Content (Join-Path $OutputDirectory "gfxinfo-framestats.txt") $frames -Encoding UTF8
Set-Content (Join-Path $OutputDirectory "meminfo.txt") $memory -Encoding UTF8
$avg = ($measurements | Measure-Object -Property TotalTimeMs -Average).Average
[pscustomobject]@{
    Device = $device; Android = $android; ABI = $abi
    PackageName = $PackageName; Attempts = $Launches
    MeanColdStartMs = [math]::Round($avg,1)
    Date = (Get-Date).ToString("o")
    OutputDirectory = (Resolve-Path $OutputDirectory).Path
} | ConvertTo-Json | Set-Content (Join-Path $OutputDirectory "summary.json") -Encoding UTF8
Write-Host "Finished. Compare launch times, frame jank and PSS memory with gold54 on the SAME device."
Write-Host (Resolve-Path $OutputDirectory).Path
