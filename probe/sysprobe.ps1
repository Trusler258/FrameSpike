# sysprobe.ps1 - 整机采样器，与 FrameSpike 的停顿日志联动（两级采样）
#
# 为什么要两级：一次带「逐进程」计数器的 Get-Counter 要 1.2~2.3 秒，
# 300ms 级的停顿根本采不到。所以拆成：
#   sys  级（快，~200ms）：DPC/ISR、磁盘队列与延迟、总 CPU、内存 —— 能落进停顿内部
#   proc 级（慢，~2s）  ：逐进程 CPU / IO 读写速率 —— 用来找"谁在抢"
# 一旦发现 frame-spikes.log 里出现新的 STALL-DETECT，立刻两级都采一次并进入急采窗口。
#
# 用法（普通 PowerShell 窗口，建议管理员权限）：
#   powershell -ExecutionPolicy Bypass -File sysprobe.ps1
#   powershell -ExecutionPolicy Bypass -File sysprobe.ps1 -FastMs 150 -WideMs 1500 -BurstSec 10
#
# 输出：~\.lunarclient\profiles\1.8\framespike\sysprobe.jsonl（每行一个 JSON，UTF8 无 BOM）
# 分析：python join_stalls.py

param(
    [string]$StallLog = "$env:USERPROFILE\.lunarclient\profiles\1.8\framespike\frame-spikes.log",
    [string]$Out      = "$env:USERPROFILE\.lunarclient\profiles\1.8\framespike\sysprobe.jsonl",
    [int]$FastMs      = 200,
    [int]$WideMs      = 2000,
    [int]$BurstSec    = 6,
    [int]$TopN        = 8,
    [double]$MaxMinutes = 0
)

$ErrorActionPreference = 'SilentlyContinue'

$narrowPaths = @(
    '\Processor Information(_Total)\% Processor Time',
    '\Processor Information(_Total)\% DPC Time',
    '\Processor Information(_Total)\% Interrupt Time',
    '\PhysicalDisk(_Total)\Current Disk Queue Length',
    '\PhysicalDisk(_Total)\Avg. Disk sec/Write',
    '\PhysicalDisk(_Total)\Avg. Disk sec/Read',
    '\PhysicalDisk(_Total)\Disk Write Bytes/sec',
    '\Memory\Available MBytes'
)
$widePaths = @(
    '\Process(*)\% Processor Time',
    '\Process(*)\IO Write Bytes/sec',
    '\Process(*)\IO Read Bytes/sec'
)

$utf8 = New-Object System.Text.UTF8Encoding($false)

function Write-Line($rec) {
    $json = $rec | ConvertTo-Json -Compress -Depth 6
    [System.IO.File]::AppendAllText($script:Out, $json + "`n", $script:utf8)
}

function ProcMap($samples, [string]$counterName) {
    $m = @{}
    foreach ($s in $samples) {
        if ($s.Path -notlike "*\$counterName") { continue }
        $name = $s.InstanceName
        if ($null -eq $name -or $name -eq '_Total' -or $name -eq 'Idle') { continue }
        $v = [double]$s.CookedValue
        if ($m.ContainsKey($name)) { $m[$name] = $m[$name] + $v } else { $m[$name] = $v }
    }
    return $m
}

function Sum-Value($samples, [string]$suffix) {
    foreach ($s in $samples) {
        if ($s.Path -like "*$suffix") { return [double]$s.CookedValue }
    }
    return 0.0
}

function Sample-Narrow([string]$mode, [string]$stall) {
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    $s = Get-Counter -Counter $narrowPaths -MaxSamples 1 -ErrorAction SilentlyContinue
    $sw.Stop()
    if (-not $s -or $s.Count -eq 0) { return 0 }
    $c = $s[0].CounterSamples
    $rec = [ordered]@{
        t     = (Get-Date).ToString('HH:mm:ss.fff')
        kind  = 'sys'
        ms    = [int]$sw.ElapsedMilliseconds
        mode  = $mode
        stall = $stall
        cpu   = [math]::Round((Sum-Value $c '\% Processor Time'), 1)
        dpc   = [math]::Round((Sum-Value $c '% DPC Time'), 2)
        isr   = [math]::Round((Sum-Value $c '% Interrupt Time'), 2)
        memMB = [math]::Round((Sum-Value $c '\Available MBytes'), 0)
        diskQ = [math]::Round((Sum-Value $c '\Current Disk Queue Length'), 2)
        wMs   = [math]::Round((Sum-Value $c 'Avg. Disk sec/Write') * 1000.0, 2)
        rMs   = [math]::Round((Sum-Value $c 'Avg. Disk sec/Read') * 1000.0, 2)
        wKBps = [math]::Round((Sum-Value $c 'Disk Write Bytes/sec') / 1024.0, 0)
    }
    Write-Line $rec
    return 1
}

function Sample-Wide([string]$mode, [string]$stall) {
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    $s = Get-Counter -Counter $widePaths -MaxSamples 1 -ErrorAction SilentlyContinue
    $sw.Stop()
    if (-not $s -or $s.Count -eq 0) { return 0 }
    $c = $s[0].CounterSamples
    $cpuMap = ProcMap $c '% Processor Time'
    $ioW = ProcMap $c 'IO Write Bytes/sec'
    $ioR = ProcMap $c 'IO Read Bytes/sec'
    $topCpu = @()
    $cpuMap.GetEnumerator() | Sort-Object Value -Descending | Select-Object -First $TopN | ForEach-Object {
        $topCpu += @{ n = $_.Key; v = [math]::Round($_.Value, 1) }
    }
    $ioAll = @{}
    foreach ($k in $ioW.Keys) { $ioAll[$k] = $ioW[$k] }
    foreach ($k in $ioR.Keys) {
        if ($ioAll.ContainsKey($k)) { $ioAll[$k] = $ioAll[$k] + $ioR[$k] } else { $ioAll[$k] = $ioR[$k] }
    }
    $topIO = @()
    $ioAll.GetEnumerator() | Sort-Object Value -Descending | Select-Object -First $TopN | ForEach-Object {
        if ($_.Value -gt 0) { $topIO += @{ n = $_.Key; kb = [math]::Round($_.Value / 1024.0, 1) } }
    }
    $rec = [ordered]@{
        t      = (Get-Date).ToString('HH:mm:ss.fff')
        kind   = 'proc'
        ms     = [int]$sw.ElapsedMilliseconds
        mode   = $mode
        stall  = $stall
        topCpu = $topCpu
        topIO  = $topIO
    }
    Write-Line $rec
    return 1
}

if (Test-Path $StallLog) { $script:pos = (Get-Item $StallLog).Length } else { $script:pos = 0 }
$burstUntil = [DateTime]::MinValue
$stallTag = $null
$startedAt = Get-Date
$nextFast = Get-Date
$nextWide = Get-Date
$nSys = 0
$nProc = 0

Write-Host "sysprobe 启动（两级采样）"
Write-Host ("  停顿日志 : " + $StallLog)
Write-Host ("  输出     : " + $Out)
Write-Host ("  快(sys)  : " + $FastMs + "ms     慢(proc): " + $WideMs + "ms     急采: " + $BurstSec + "s")
Write-Host "  按 Ctrl+C 结束"

while ($true) {
    if ($MaxMinutes -gt 0 -and ((Get-Date) - $startedAt).TotalMinutes -ge $MaxMinutes) { break }
    $now = Get-Date
    $bursting = ($now -lt $burstUntil)
    $mode = 'idle'
    if ($bursting) { $mode = 'burst' }

    # --- 检查日志新增 ---
    if (Test-Path $StallLog) {
        $len = (Get-Item $StallLog).Length
        if ($len -gt $script:pos) {
            try {
                $fs = [System.IO.File]::Open($StallLog, 'Open', 'Read', 'ReadWrite')
                $fs.Seek($script:pos, 'Begin') | Out-Null
                $buf = New-Object byte[] ($len - $script:pos)
                $fs.Read($buf, 0, $buf.Length) | Out-Null
                $fs.Close()
                $txt = [System.Text.Encoding]::UTF8.GetString($buf)
                if ($txt -match 'STALL-DETECT') {
                    $burstUntil = (Get-Date).AddSeconds($BurstSec)
                    $m = [regex]::Match($txt, 'STALL-DETECT (\d+)ms\s+since=''([^'']*)''')
                    if ($m.Success) { $stallTag = $m.Groups[1].Value + 'ms@' + $m.Groups[2].Value }
                    Write-Host ("  [" + (Get-Date).ToString('HH:mm:ss') + "] 检测到停顿 " + $stallTag + " -> 急采 " + $BurstSec + "s")
                    $mode = 'burst'
                    $nextFast = Get-Date
                    $nextWide = Get-Date
                }
            } catch { }
            $script:pos = $len
        }
    }

    if ((Get-Date) -ge $nextFast) {
        $nSys += Sample-Narrow $mode $stallTag
        if ($mode -eq 'burst') { $nextFast = (Get-Date).AddMilliseconds($FastMs) }
        else { $nextFast = (Get-Date).AddMilliseconds([Math]::Max($FastMs, 500)) }
    }
    if ((Get-Date) -ge $nextWide) {
        $nProc += Sample-Wide $mode $stallTag
        $nextWide = (Get-Date).AddMilliseconds($WideMs)
    }
    if (($nSys + $nProc) % 40 -lt 2) {
        $last = Get-Content $Out -Tail 1
        Write-Host ("  [" + (Get-Date).ToString('HH:mm:ss') + "] sys=" + $nSys + " proc=" + $nProc + "  " + $last.Substring(0, [Math]::Min(120, $last.Length)))
    }
    Start-Sleep -Milliseconds 20
}

Write-Host ("sysprobe 结束：sys=" + $nSys + " proc=" + $nProc + " -> " + $Out)
