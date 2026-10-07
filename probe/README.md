# 整机探测（与 FrameSpike 联动）

游戏里的卡顿有一部分根因不在 JVM 里：杀软过滤驱动拦 I/O、DWM 合成、别的进程抢核、
驱动级 DPC/ISR。这些只能从整机侧采。这里三件工具，**都不需要改 mod**：
FrameSpike 写在 `frame-spikes.log` 里的时间戳就是现成的接口。

```
停顿日志（mod）  ─┐
Lunar 看门狗     ─┼─→  join_stalls.py  ─→  每条停顿：栈 + 真实时长 + 停顿前后整机状态 + 判定
sysprobe.jsonl   ─┘
```

## 1. sysprobe.ps1 —— 整机计数器采样（1 Hz）

```powershell
# 普通窗口即可（管理员能读到更多计数器）
powershell -ExecutionPolicy Bypass -File probe\sysprobe.ps1
```

- 采：总 CPU、**% DPC Time / % Interrupt Time**、磁盘队列长度、磁盘读写延迟、
  写吞吐、可用内存，以及**逐进程 CPU 与 IO 读写速率**
- 两级采样：`sys` 级（快）与 `proc` 级（慢，逐进程那个 WMI 类很贵）
- **自动联动**：一旦发现 `frame-spikes.log` 里出现新的 `STALL-DETECT`，
  立刻两级都采一次并进入急采窗口（默认 150ms / 6s）
- 输出：`~\.lunarclient\profiles\1.8\framespike\sysprobe.jsonl`

**⚠ 硬限制（实测）**：`Get-Counter` 一次调用最快也要 **~1007 ms** ——
速率型计数器内部要等 1 秒才能算。所以这套东西的**时间分辨率就是 1 秒**，
对 ≥1 秒的停顿够用，300 ms 级的采不到。要亚秒级请用第 3 件（ETW）。

**本机实测基线**（游戏在跑、空闲场景）：

| 指标 | 中位 | 最大 |
|---|---|---|
| 总 CPU | 22% | 24.8% |
| % DPC Time | 0.39 | 0.78 |
| % Interrupt Time | 0.39 | 0.78 |
| 磁盘队列 | 0 | 0 |
| 磁盘写延迟 | 1.79 ms | 5.88 ms |
| 可用内存 | 6913 MB | — |

**这几个数字就是"正常"的尺子**：如果某次停顿期间 DPC 涨到 >2%、或磁盘写延迟涨到几十 ms、
或磁盘队列 >0，那基本可以判定是驱动/磁盘/过滤驱动的问题，而不是游戏代码。
另外实测发现 `cloudmusic` 会持续往磁盘写 ~1.4 MB/s（游戏和它同在 C: 盘），
这是个值得在停顿期间重点看的候选。

## 2. join_stalls.py —— 对齐分析

```powershell
python probe\join_stalls.py                # 默认只看 >=200ms
python probe\join_stalls.py --min-ms 100
```

做三件事：

1. **算真实时长**：优先用 mod 新格式的 `STALL-END total=NNNms`；老记录则按时间戳
   去 Lunar 的 `Pause - Unknown - NNNms` 匹配（±1 秒）
2. **归组**：一次长冻结里 mod 可能打好几条（每个阶段间隔都超过阈值），
   按"匹配到同一条 Lunar 停顿"合并成一次冻结，**所有阶段的栈都保留**
   （例：一次 1589 ms 冻结含 4 条记录、4 个阶段的栈）
3. **判定外部因素**：对每条 ≥min-ms 的冻结，看窗口内整机状态，四项任一命中就报出来

## 3. etw_capture.ps1 —— ETW 内核级追踪（**需要管理员**）

```powershell
# 一次性采 3 分钟
powershell -ExecutionPolicy Bypass -File probe\etw_capture.ps1 -Seconds 180
# 或手动：先 -Start，打完一局再 -Stop
```

启动的 profile：`CPU`（含调用栈）、`DiskIO`、`FileIO`、**`Minifilter`**（杀软过滤驱动 I/O）、
`DesktopComposition`（DWM 合成）。结束后自动用 `tracerpt` 转成 CSV + 摘要
（不需要装 Windows Performance Toolkit，`wpr`/`tracerpt` 是系统自带）。

输出在 `...\framespike\etw\`。**这是唯一能回答下面这些问题的工具**：

- 那 6 次 200~372 ms 的写文件，**到底是哪个文件、谁在中间插手**（Minifilter 事件）
- 750 ms 卡在 `defWindowProc` 时，**哪个模块在占 CPU**（CPU 采样栈含内核）
- 620 ms 卡在 `nglDrawArrays` 是 GPU 侧还是驱动侧（DPC/ISR + GPU 事件）

注意：ETW 的 `ClockTime` 是本地时间、精度到微秒，可以直接和
`frame-spikes.log` 的 `HH:mm:ss.SSS` 对齐。

## 已有的结论（不需要重跑）

`join_stalls.py` 在 2026-10-05 那份数据上的输出（当时 sysprobe 还没跑，所以只有 mod 侧）：

- 12 次 ≥200ms 的冻结，含 16 条 mod 记录
- 最大 1589 ms（**Xaero 小地图进服加载全部路径点**）、750 ms（窗口消息泵）、
  620 ms（OptiFine 自定义天空）、472 ms（音效队列）、464 ms（反射创建 WorldProvider）、
  371 ms（建区块 VBO）、6×219~372 ms（**Lunar 自己丢到主线程的写文件任务**）、
  309 ms（Lunar 的 ASM remap 管线）
- 详见 skill `mc-lunar-stutter-diagnosis` 的「重大结论」一节
