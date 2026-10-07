# etw_capture.ps1 - 用 ETW 做亚秒级、含内核模块的整机追踪
#
# 为什么还需要它：sysprobe.ps1 用的是 Get-Counter，实测最快 ~1 秒一次
# （速率型计数器必须等 1 秒才能算出来）。所以它只能看出"停顿前后"的状态，
# 300ms 级的停顿采不到。ETW 是按事件的，能给出：
#   - CPU 采样栈（含内核模块、含别的进程）→ 某段卡住时到底是哪个模块在跑
#   - 每个文件 I/O 的耗时与文件名
#   - 微过滤器（杀软）在 I/O 路径上的活动  ← 查"写文件为什么要 300ms"就靠这个
#   - DPC/ISR 的耗时与责任驱动
#   - DWM 桌面合成活动
#
# 必须**以管理员身份**运行（wpr 起内核会话）。
#
# 用法：
#   一次性采集 3 分钟（最常用）：
#     powershell -ExecutionPolicy Bypass -File etw_capture.ps1 -Seconds 180
#   手动控制（进服后再关）：
#     ... -Start       然后打一局    ... -Stop
#
# 输出（默认在 %USERPROFILE%\.lunarclient\profiles\1.8\framespike\etw\）：
#   spike.etl      原始追踪（可能几百 MB，分析完可删）
#   spike.csv      tracerpt 转出来的事件表
#   spike.sum.txt  tracerpt 摘要
#
# 分析：把 spike.csv 的时钟列和 frame-spikes.log 的 STALL 时间戳对齐即可
#       （ETW 的 ClockTime 是本地时间，精度到微秒）

param(
    [switch]$Start,
    [switch]$Stop,
    [int]$Seconds = 0,
    [string]$OutDir = "$env:USERPROFILE\.lunarclient\profiles\1.8\framespike\etw",
    [string]$Name = "spike"
)

$ErrorActionPreference = 'Continue'
$wpr = "$env:WINDIR\System32\wpr.exe"
$tracerpt = "$env:WINDIR\System32\tracerpt.exe"

# --- 权限检查 ---
$id = [Security.Principal.WindowsIdentity]::GetCurrent()
$pr = New-Object Security.Principal.WindowsPrincipal($id)
$elev = $pr.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
if (-not $elev) {
    Write-Host "[错误] 需要管理员权限。请右键 PowerShell -> 以管理员身份运行，然后重试："
    Write-Host "       powershell -ExecutionPolicy Bypass -File `"$PSCommandPath`" -Seconds 180"
    exit 1
}

if (-not (Test-Path $OutDir)) { New-Item -ItemType Directory -Path $OutDir -Force | Out-Null }
$etl = Join-Path $OutDir "$Name.etl"
$csv = Join-Path $OutDir "$Name.csv"
$sum = Join-Path $OutDir "$Name.sum.txt"

function Do-Stop {
    Write-Host "[*] 停止追踪并保存..."
    & $wpr -stop $etl | Out-Host
    if (-not (Test-Path $etl)) { Write-Host "[错误] 没生成 $etl"; exit 1 }
    $mb = [math]::Round((Get-Item $etl).Length / 1MB, 1)
    Write-Host ("[*] etl 大小 " + $mb + " MB，转 CSV（大文件要几分钟，别急）...")
    & $tracerpt $etl -o $csv -of CSV -y -summary $sum | Out-Host
    Write-Host ""
    Write-Host "完成："
    Write-Host ("  原始追踪 : " + $etl + "  (" + $mb + " MB)")
    Write-Host ("  事件 CSV : " + $csv + "  (" + [math]::Round((Get-Item $csv).Length / 1MB, 1) + " MB)")
    Write-Host ("  摘要     : " + $sum)
    Write-Host ""
    Write-Host "接下来看什么："
    Write-Host "  1) 在 frame-spikes.log 里找 STALL-END 的 total= 和时刻"
    Write-Host "  2) 在 CSV 里筛那个时刻前后 2 秒的事件："
    Write-Host "     - File I/O 事件 -> 哪个文件、耗时多少（对应写文件那类停顿）"
    Write-Host "     - Minifilter 事件 -> 杀软过滤驱动有没有插进 I/O 路径"
    Write-Host "     - DPC/ISR 事件 -> 耗时长的那个，责任驱动是谁"
    Write-Host "     - CSwitch/Stack 采样 -> 那一瞬间哪个模块在占 CPU"
}

if ($Stop) { Do-Stop; exit 0 }

# --- 启动 ---
Write-Host "[*] 启动 ETW 追踪（CPU栈 + 磁盘 + 文件 + 微过滤器 + 桌面合成）..."
$profiles = @('CPU', 'DiskIO', 'FileIO', 'Minifilter', 'DesktopComposition')
$args = @()
foreach ($p in $profiles) { $args += '-start'; $args += $p }
& $wpr @args | Out-Host
if ($LASTEXITCODE -ne 0) {
    Write-Host "[!] 多 profile 启动失败，退回 GeneralProfile..."
    & $wpr -start GeneralProfile | Out-Host
    if ($LASTEXITCODE -ne 0) { Write-Host "[错误] wpr 启动失败，看上面输出"; exit 1 }
}
Write-Host "[*] 追踪中。现在启动/切到游戏去打一局。"

if ($Seconds -gt 0) {
    Write-Host ("[*] " + $Seconds + " 秒后自动停止（期间请让游戏跑起来）...")
    Start-Sleep -Seconds $Seconds
    Do-Stop
} else {
    Write-Host "[*] 手动模式：打完一局后回来按 Enter 停止..."
    Read-Host | Out-Null
    Do-Stop
}
