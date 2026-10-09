<div align="center">
  <img src="https://raw.githubusercontent.com/Trusler258/FrameSpike/main/logo.png" width="140" alt="FrameSpike 帧刺"/>

  # FrameSpike 帧刺

  **Minecraft 1.8.9 帧时间尖峰与卡顿分析 mod**

  当主线程冻住的那一刻，自动抓取调用栈与帧内时间线，结算真实停顿时长并归因——让你知道每一次卡顿到底卡在哪、为什么卡

  ![Forge](https://img.shields.io/badge/Forge-1.8.9_–_1.12.2-1e6bb8?style=flat-square&logo=minecraft&logoColor=white)
  ![Fabric](https://img.shields.io/badge/Fabric-1.20.1_–_1.20.6-8a7f4d?style=flat-square&logo=minecraft&logoColor=white)
  ![NeoForge](https://img.shields.io/badge/NeoForge-1.20.1-c96e2b?style=flat-square&logo=minecraft&logoColor=white)
  ![版本](https://img.shields.io/badge/版本-0.8.0-2ea44f?style=flat-square&logo=modrinth&logoColor=white)
  ![许可](https://img.shields.io/badge/License-GPL--3.0-c4a227?style=flat-square&logo=gnu&logoColor=white)
</div>

---

## 它解决什么问题

常规手段定位不了单次卡顿：GC 日志看不出异常，服务端 spark 只有时间窗内的累计耗时，缺少单帧时间戳；而手动抓栈永远慢一步

FrameSpike 在 JVM 内部直接采样：每帧必经的方法入口都有检查点，看门狗监视主线程推进——超过阈值的那一刻抓栈并记录帧内时间线，下一个检查点到达时结算真实时长、归因、写日志，达到门槛的再推一行聊天

## 实际效果

TNT 引爆时的卡顿取证（自动捕获 + 归因 + 聊天提示）：

![TNT 卡顿测试](https://raw.githubusercontent.com/Trusler258/FrameSpike/main/assets/screenshots/tnt-stall.png)

Fabric 1.20.1 的 `/fs help`（分组帮助全表）：

![Fabric 1.20.1 help](https://raw.githubusercontent.com/Trusler258/FrameSpike/main/assets/screenshots/fabric-help.png)

## 功能

- **真实停顿时长**：结算出来的值（不是检测时刻），能区分 60ms 和 1.5s
- **归因证据模型**：按栈顶前 6 帧归类为一句简写（24 类），并给出 **Attribution + Confidence（High/Medium/Low）+ Evidence（栈顶帧）**——置信度按停顿期间多次栈采样的多数派占比给出，报告里直接读出 Most likely blocking path
- **多次栈采样**：停顿期间每约 40ms 补充采样（最多 5 次），统计多数派栈顶——单次快照只能证明"卡顿时线程在哪"，多次采样才能给出最可能的阻塞路径
- **帧内时间线**：卡住那一刻已推进到哪一步（runTick → 渲染 → HUD）
- **GC 关联**：停顿结算时按 nanoTime 精确时间区间重叠标注是否 GC、哪个收集器、重叠了多少（`gc=名字 重叠/总时长ms`）
- **Mod ownership**：栈顶类自动反查来源 jar（CodeSource），报告直接读出"最可能是哪个 mod 的代码"
- **崩溃感知**：游戏崩溃时在崩溃报告里补一段死前时间线
- **CPU 与帧率**：全线程 CPU 表（判断是不是别的进程在抢）、fps 骤降检测
- **micro-stutter**：低于停顿阈值的连续轻微抖动（21/23/25ms 这类）由滚动窗口统计兜底，报告直接读出次数与窗口 max/avg
- **HTML 分析报告**：时长分档 / 归因证据 / 火焰图（可点击聚焦）/ CPU 与帧率趋势 / 停顿明细（可展开栈），自包含单文件，双击用浏览器打开
- **实时报告页**：`/fs serve` 起一个只绑 127.0.0.1 的本地报告页，随游戏运行实时刷新
- **聊天摘要**：卡顿时推送一行 `[FS] 3157ms 类加载/remap ... (4/5 High)`，有门槛与冷却，不刷屏
- **配置热改**：`framespike.ini` 改完约 2.5 秒生效，不用重启游戏

## 支持版本

| 加载器 | 版本 |
|---|---|
| Forge（coremod） | 1.8.9 – 1.12.2（1.9.x / 1.10.x / 1.11.x / 1.12.x 已实测，Lunar Client 也可用） |
| Fabric | 1.20.1 – 1.20.6（已实测；1.16.5+ 理论兼容） |
| NeoForge | 1.20.1 |

## 使用

放进对应版本的 `mods` 目录，游戏内用 `/fs` 命令控制：`/fs start` 开始记录 → 打一局 → `/fs stop` 输出汇总并生成报告。1.8.9 – 1.12.2 各版本共用同一份 Forge jar

## 命令一览

| 命令 | 作用 |
|---|---|
| `/fs start` | 开始一段记录：重置本段统计（帧数/tick/分档/最长），并写入一行 RECORDING STARTED；建议进服稳定后先打一次，这样统计里不会混进进服/换图的一次性开销 |
| `/fs stop` | 结束本段并输出汇总：时长、帧数(fps)、tick(tps)、停顿次数、真实时长分档、最长一次；时长是结算出来的真实值（不是检测时刻），能区分 60ms 和 1.5s；正在进行的停顿也会在这一刻被兜底结算；stop 只停记录，聊天播报一直开着，卡顿照样提示；`/fs start` 恢复完整记录 |
| `/fs status` | 一行行看当前状态：是否在记录、阈值、glFinish 模式与调用点数、注册详情、chat 设置、日志路径；Tab 没反应时看这行里的 tabCalls：为 0 = 请求没到 mod，不为 0 = 到了但回填有问题 |
| `/fs dump` | 立刻抓一次主线程调用栈并写入日志（不依赖停顿阈值）；你觉得「就是这一下卡了」的时候手动打点，顺便看它归到哪一类 |
| `/fs mark <文本>` | 往日志插一条带时间戳的标记，把日志时间线和实际事件对齐；例：`/fs mark 开局`、`/fs mark 被打了`、`/fs mark 换图` |
| `/fs tail [n]` | 读日志尾部，把最近 n 条停顿的首行（时刻/时长/原因）回显到聊天；默认 3，上限 20；刚卡完想立刻确认是哪一类，不用切出去看文件 |
| `/fs threshold <ms>` | 停顿判定阈值，当前 60ms，范围 20-5000；调低能抓到更小的抖动（但日志会变多），调高只看大卡顿 |
| `/fs chat on\|off` | 卡顿时在聊天里提示一行，当前 开；格式 `[FS] 3157ms  写文件  FileOutputStream.writeBytes:0`；有三道闸防刷屏：时长门槛、冷却、总开关；日志不受影响，永远全量记录 |
| `/fs chatmin <ms>` | 聊天提示的门槛，当前 150ms（范围 50-5000）；低于它的停顿只进日志不刷屏；冷却 1000ms 内最多一条 |
| `/fs glfinish on\|off` | on = 跳过每帧的 glFinish（干预生效），off = 恢复原生透传；需要 ini 里 `glFinishMode=proxy` 并在启动时解析到调用点，否则会直接告诉你不可用；调用点数量见 `/fs status` 里的 sites |
| `/fs cpu` | 立刻输出全线程 CPU 表（前几行进聊天，完整表进日志）；判断「是不是别的线程/别的进程在抢」，以及「机器还有没有余量」 |
| `/fs report [all]` | 把当前日志变成一份自包含的 HTML 分析报告（类似 spark 的查看器）：时长分档 / 归因证据 / 火焰图（宽度=累计停留时长，可点击聚焦）/ CPU 与帧率趋势 / 停顿明细（可展开栈）；生成在日志同目录（或 ini 里 reportDir 指定的目录），文件名 report-<时间>.html，双击用浏览器打开；后台线程生成，不会在游戏里留下停顿；默认只统计本段记录，`all` = 整份日志 |
| `/fs serve [端口\|off]` | 起一个本地 HTTP 报告服务（只绑 127.0.0.1），默认端口 8731，浏览器打开 `http://127.0.0.1:8731/`；页面每次刷新都会重新读日志，等于活的；`/log` 路径给原始日志；关闭用 `/fs serve off`；只给本机看，不对外暴露 |
| `/fs version` | 报版本、作者与运行环境：java 版本、ini 路径、日志路径、记录开关、阈值、栈深、命令名、报告服务；遇到问题截这一屏就够定位，不用再问「你装的哪个版本」 |
| `/fs help [子命令]` | 不带参数：`/fs` 给快速上手，`/fs help` 给分组全表；带参数：给单个子命令的详情，例 `/fs help tail` |

## 已知问题与代办

这个 mod 最初是我**个人排查卡顿用的工具**，有一些小问题，后来才整理发布：

- **NeoForge**：聊天里的可点链接暂不可用（Style 链需要反射，后补）
- **1.12.2**：frames 计数可能不准（runGameLoop 命中的混淆名 `av` 在 1.12.2 里可能是另一个方法）
- **阈值设太低**（如 20ms）会快速烧光抓栈配额（maxDumps），之后不再检测——建议 60-150ms
- **NeoForge 1.20.1** 的 mod 代码不被 remap，mixin 不可用——检查点走 NeoForge 自己的 TickEvent

## 定位边界

FrameSpike 是**主线程停顿黑匣子**（main-thread stall black box）：它捕获的是主线程检查点之间的停顿现场，不是任意的帧时间问题。GPU 侧卡顿（主线程 16ms 正常但 GPU 帧时间 300ms）不在本模型的覆盖范围内；连续轻微抖动由 micro-stutter 滚动窗口统计兜底，但那是补充说明而非完整解法

## 数据说明

归因与报告数据**仅供参考**：分类基于栈顶 6 帧的启发式判断（stack-based attribution），只能证明"主线程被采样时正在该路径上"，不能单独证明该路径是全部时长的唯一原因——置信度（High/Medium/Low）按多次栈采样的多数派占比给出。时长由 `System.nanoTime`（JVM 高分辨率单调时钟）结算，精度不受系统定时器影响；小停顿的**发现延迟**最大约 2ms（看门狗轮询间隔，受系统调度影响非精确硬实时）。结论请结合实际情况自行判断

## 隐私

纯客户端，零外部依赖，不向外部发送任何数据。所有记录只写本地文件

## 仓库

https://github.com/Trusler258/FrameSpike

## 制作

**Trusler** & Deepseek v4.1Flash / Claude Opus 5.5 / GLM5.3 Flash

---

<div align="center">
  <img src="https://raw.githubusercontent.com/Trusler258/FrameSpike/main/logo.png" width="140" alt="FrameSpike"/>

  # FrameSpike

  **A client-side frame-time spike forensics mod for Minecraft**

  The exact moment the main thread freezes, it captures the call stack and in-frame timeline, measures the real stall duration and attributes the cause — so you know where every lag spike happened and why

  ![Forge](https://img.shields.io/badge/Forge-1.8.9_–_1.12.2-1e6bb8?style=flat-square&logo=minecraft&logoColor=white)
  ![Fabric](https://img.shields.io/badge/Fabric-1.20.1_–_1.20.6-8a7f4d?style=flat-square&logo=minecraft&logoColor=white)
  ![NeoForge](https://img.shields.io/badge/NeoForge-1.20.1-c96e2b?style=flat-square&logo=minecraft&logoColor=white)
  ![Version](https://img.shields.io/badge/Version-0.8.0-2ea44f?style=flat-square&logo=modrinth&logoColor=white)
  ![License](https://img.shields.io/badge/License-GPL--3.0-c4a227?style=flat-square&logo=gnu&logoColor=white)
</div>

---

**FrameSpike** is a client-side Minecraft frame-time spike forensics mod — the exact moment the main thread freezes, it captures the call stack and in-frame timeline, measures the real stall duration and attributes the cause, so you know where every lag spike happened and why

## What it solves

Regular tools can't pin down a single stall: GC logs show nothing unusual, server-side spark only gives cumulative time per window without per-frame timestamps, and manual stack dumps are always too late

FrameSpike samples inside the JVM itself, in three steps:

1. Inject checkpoints at the entry of every per-frame method (designed for negligible per-checkpoint overhead)
2. A watchdog thread watches the main thread (~2ms polling interval); when it stalls past the threshold it grabs a call stack, keeps sampling during the stall (up to 5 samples), and records the in-frame timeline
3. When the next checkpoint arrives it settles the real duration, attributes the cause, writes the log — and pushes a one-line chat summary if it's past the threshold

## Features

- **Real stall duration**: settled values (not the detection moment), can tell 60ms from 1.5s apart
- **Attribution + Confidence + Evidence**: the top 6 stack frames are classified into one of 24 short labels, with a confidence level (High/Medium/Low) derived from majority-vote over multiple stack samples — the report reads out "Most likely blocking path (4/5)"
- **In-frame timeline**: how far the frame got (runTick → render → HUD)
- **GC correlation**: exact nanoTime interval overlap — which collector, and how much of the stall it covered
- **Mod ownership**: the top stack frame is traced back to its source jar via CodeSource
- **Crash awareness**: on a crash, the crash report gets a pre-death timeline appended
- **CPU & fps**: full thread CPU table, fps drop detection
- **micro-stutter**: continuous small hitches below the stall threshold are covered by a rolling-window statistic
- **HTML analysis report**: duration buckets / attribution evidence / flame graph / CPU & fps trends / expandable stall details — single self-contained file
- **Live report page**: `/fs serve`, localhost-only, re-reads the log on every refresh
- **Chat summary**: `[FS] 3157ms class-load remap ... (4/5 High)` with threshold & cooldown
- **Hot config**: edit `framespike.ini`, takes effect in ~2.5s

## Supported versions

| Loader | Version |
|---|---|
| Forge (coremod) | 1.8.9 – 1.12.2 (1.9.x / 1.10.x / 1.11.x / 1.12.x tested; works on Lunar Client too) |
| Fabric | 1.20.1 – 1.20.6 (tested; 1.16.5+ expected to work) |
| NeoForge | 1.20.1 |

## Usage

Drop the jar into the version's `mods` folder and control it in-game with `/fs`: `/fs start` to record → play → `/fs stop` for the summary and a report. All versions 1.8.9 – 1.12.2 share the same Forge jar

## About the data

Attribution and report data are **for reference only**: classification is a heuristic over the top 6 stack frames — it proves "the main thread was sampled on that path", not that the path was the sole cause. Confidence (High/Medium/Low) is derived from majority-vote over multiple stack samples. Durations are settled with `System.nanoTime` (JVM high-resolution monotonic clock), unaffected by the system timer; small stalls have a **detection latency** of at most ~2ms (the watchdog poll interval, subject to OS scheduling). Please judge the conclusions in context

## Scope

FrameSpike is a **main-thread stall black box**: it captures stalls between main-thread checkpoints, not arbitrary frame-time problems. GPU-side stalls (main thread at 16ms but GPU frame at 300ms) are outside this model; continuous micro-stutter is covered by a rolling-window statistic as a complement

## Privacy

Pure client-side, zero external dependencies, sends no data anywhere. Everything stays in local files

## Repository

https://github.com/Trusler258/FrameSpike

## Credits

**Trusler** & Deepseek v4.1Flash / Claude Opus 5.5 / GLM5.3 Flash
