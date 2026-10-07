![FrameSpike 帧刺](assets/logo.svg)

# FrameSpike 帧刺

Minecraft 帧时间尖峰与卡顿分析 mod（1.8.9 / 1.12.2 · Fabric · NeoForge）

v0.7.0 · Trusler · Forge coremod（1.8.9 / 1.12.2）· Fabric（1.16.5+）· NeoForge（1.20.1+）· 纯客户端，不含任何数据外发

---

## 简介

开发动机很具体：在 Lunar Client 上存在间歇性帧停顿，需要确定每一次停顿的位置与成因。

常规手段均无法满足要求。GC 日志显示无异常，网络侧正常；服务端 spark 只能提供时间窗内的累计耗时，缺少单帧时间戳，无法定位到具体的某一次停顿。Lunar 默认启用 `-XX:+DisableAttachMechanism`，jstack、jcmd、JFR 均不可用，也无法从外部附加进程读取调用栈。

因此改为在 JVM 内部自行采样，链路只有三步：

1. 在 7 个每帧必经的方法入口注入检查点，单次开销约 20ns
2. 看门狗线程监视主线程推进；超过阈值未推进时抓取一次调用栈，并记录帧内时间线
3. 下一个检查点到达时结算真实时长，完成归因、写入日志，达到门槛的再推送一行聊天

## 功能

单次停顿的记录包含调用栈、帧内时间线（已推进到哪一步）与真实时长分档。原因按栈顶前 6 帧归类为一句简写，共 24 类：写文件 / 读文件 / 类加载 remap / Mixin 注入 / 窗口合成 / GL 驱动 / 音效 / 字体缓存 / 小地图 / OptiFine / 网络 / 区块渲染 / 实体与模型渲染 / GUI / 直接内存 / 等待限速 / 压缩解压 / 序列化解析 / 图像 / 内嵌浏览器 / Essential / JDK 内部 / 游戏自身 / 混淆类，可直接定位到对应子系统；混淆类统一成「Lunar 混淆类」而不是一串乱码，报告里点某一行可以展开看它的成员（栈顶帧）。

发生停顿时会向聊天栏推送一行摘要，形如 `[FS] 312ms 写文件 FileOutputStream.writeBytes:0`。设有门槛与冷却，避免刷屏。

0.6.2 补充了以下能力，均来自实际排查中的缺口。

### 崩溃感知

此前的空白是游戏直接崩溃时日志中没有任何记录。现在会扫描 crash-reports 目录中的新增报告，将崩溃栈交给同一套分类器归因，写入 `=== CRASH ===` 块。同时注册 JVM 退出钩子，保存崩溃前最后一段检查点时间线。崩溃时不存在"下一个检查点"可供结算，该时间线是唯一的现场数据。

### GC 关联

注册 GC 通知监听。停顿结算时若窗口内有 GC 事件，则在 `STALL-END` 行标注 `gc=`，聊天提示附带 `[GC]`。此前最耗时的判断是"这次停顿是否由 GC 引起"，现在可直接读出结论。

### fps 骤降检测

仅检测单帧尖峰会遗漏渐进式掉帧。增加了滑动窗口：最近数个采样的均值降至一半以下时给出提示。

### 世界上下文

停顿行附带维度与坐标。该数据由反射获取，失败时静默跳过，不影响其余字段。

### ini 热重载

修改 `framespike.ini` 后约 2.5 秒生效，无需重启游戏。

此外还提供一项可选的字节码干预：`glFinishMode=proxy` 会把每帧一次的 `GL11.glFinish()` 替换为代理，之后可用 `/fs glfinish on|off` 在游戏内即时切换，便于做 A/B 对照。该配置需重启一次才生效，原因是 `DisableAttachMechanism` 同时禁用了 retransform。

## 安装

| 平台 | 产物 | 放进 |
|---|---|---|
| 1.8.9 / 1.12.2（Forge coremod） | `FrameSpike-Forge-1.8.9-1.12.2-0.7.0.jar` | 1.8.9 = Lunar 的 `mods\forge-1.8.9\`；1.12.2 = 对应 Forge 版本的 `mods\` |
| 1.16.5+ / 1.20.1（Fabric） | `FrameSpike-Fabric-1.20.1-0.7.0.jar` | 该版本 profile 的 `mods\`（需 Fabric Loader；`/fs` 另需 Fabric API） |
| 1.20.1+（NeoForge） | `FrameSpike-NeoForge-1.20.1-0.7.0.jar` | `.minecraft\mods\`（需 NeoForge） |

安装步骤（所有平台同理）：

```bash
# 1) 完全退出游戏 —— 运行中的 jar 被 JVM 锁定，此时替换会装不进去

# 2) 把对应平台的 jar 放进该版本的 mods 目录
#    启动器用隔离 gameDir 的（PCL / HMCL 等），放进版本目录下的 mods\

# 3) 启动游戏
```

**配置与日志**都在**游戏目录**下的 `framespike\`：
`framespike.ini`（首次启动自动生成）/ `frame-spikes.log`，生成的报告也在同目录。
`/fs version` 会显示当前日志路径，找不到日志时先看它。

各平台的差异：

| | 1.8.9 / 1.12.2 coremod | Fabric | NeoForge |
|---|---|---|---|
| `/fs` 命令 | ✅ 进入世界后 | ✅ 需 Fabric API | ✅ 进入世界后（1.20.1 没有客户端命令 API） |
| 报告路径可点开 | ✅ | ✅ OPEN_FILE | ✅ OPEN_FILE |
| `glFinishMode=proxy` | ✅ | ✗ | ✗ |
| Fabric API | 不需要 | `/fs` 需要（软依赖，缺了走 ctrl 文件通道） | 不需要 |

卸载时删除该 jar 即可，无其他残留。

## 指令

| 指令 | 说明 |
|---|---|
| `/fs` | 快速上手 4 步（start → 打一局 → stop → tail） |
| `/fs start` | 开始记录，重置本段统计。建议服务器连接稳定后再执行 |
| `/fs stop` | 输出汇总并停止记录，同时生成报告（聊天里的路径可点开）。聊天播报继续，卡顿仍会提示；`/fs start` 恢复完整记录 |
| `/fs status` | 逐行输出状态：记录开关、阈值、glFinish 模式与调用点数、注册详情、chat 设置、日志路径 |
| `/fs dump` | 立即抓取一次主线程栈，附原因简写 |
| `/fs mark <文本>` | 向日志插入标记，用于将时间线与实际事件对齐 |
| `/fs tail [n]` | 回显最近 n 条 STALL 的首行，默认 3，上限 20 |
| `/fs threshold <ms>` | 停顿判定阈值，默认 60，范围 20-5000 |
| `/fs chat on\|off` | 聊天提示开关，默认开启 |
| `/fs chatmin <ms>` | 聊天提示门槛，默认 150，冷却 1000ms |
| `/fs glfinish on\|off` | on 跳过每帧 glFinish，off 恢复原生调用，需要 `glFinishMode=proxy` |
| `/fs report [all]` | 生成自包含 HTML 报告；默认只统计本段记录，`all` = 整份日志；聊天里的路径可点开 |
| `/fs serve [端口\|off]` | 启动本地报告服务，默认 8731，仅绑定 127.0.0.1，页面每次刷新重新读取日志 |
| `/fs cpu` | 立即输出全线程 CPU 表 |
| `/fs config` | 列出全部可配置项与当前值；`/fs config <键> <值>` 热改一项（threshold chatmin chatcool maxdumps stackdepth cpusec cputop autoreport）；`/fs config save` 写回 ini |
| `/fs reload` | 重新读 ini（平时 ini 有热改监控，一般用不到） |
| `/fs version` | 报版本、作者与运行环境（java / ini / 日志路径 / 栈深 / 服务状态） |
| `/fs help [子命令]` | 分组全表（行尾带当前值）或单条详情 |

别名 `framespike`、`fspike`。命令名冲突时会自动换名，`/fs status` 中会显示最终生效的名称。

## 分析报告

`/fs report` 生成单文件、数据内嵌的 HTML，双击即可查看，不需要联网或本地服务。`/fs stop` 会自动生成一份（`autoReport=true`）。

| 区块 | 内容 |
|---|---|
| 概览 | KPI、时长分档、原因归因（可点开看成员）、最长的几次、会话信息（记录区间 / 退出信息 / 崩溃数）；**默认只统计最后一段记录** |
| 时间轴 | 多轨道：并发负载、按原因分道、时长、栈深、帧率（可框选缩放，起点终点取自模组原生 `t0`/`t1`） |
| 火焰图 | 层数为调用深度，宽度为累计停留时长。点击聚焦某一支，Esc 回退 |
| 调用树 | spark 式，逐层展开或一键展开全部，带累计时长 / 占比 / 次数 |
| CPU / 帧率 | 每 60 秒一次采样的趋势线（fps / tps / 整机 CPU%） |
| 崩溃 | 从 crash-reports 解析的时间、异常、归因与报告文件名（没有崩溃时入口隐藏） |
| 对比 | 载入第二份日志，逐项对比指标、分档、归因与崩溃次数，变差标红、变好标绿 |
| 全部日志 | 时刻、真实时长、原因（含 GC 标记）、卡在哪一步、帧内时间线；双击一行打开该次停顿 |

调用栈不截断，日志里有多少层就展示多少层（想控制体积就调小 `stackDepth`）；
原因归因按行竖排，长尾的几十条一次性归因也不会挤成一片。

左侧是资源树，右侧是多标签页；点树里的节点即打开对应视图，看某一条停顿时用同一个预览标签替换内容。

### 载入自定义文件

报告为单文件结构，因此解析器一并内嵌在页面中（`/*==PARSE-BEGIN==*/` 区块，与 Java 侧同口径，有测试逐字段比对）。这使得无需重新启动游戏即可查看其他日志：

| 拖入的文件 | 行为 |
|---|---|
| 任意 `frame-spikes.log` | 页面切换为该份数据，KPI、分档、归因、火焰图与明细全部重算 |
| `.ttf` `.otf` `.woff` `.woff2` | 整个页面使用该字体（通过 `FontFace` 内嵌，不联网）。刷新或重置后还原 |
| 其他文件 | 明确提示"不含 STALL 记录"，不会出现空白页 |

页面右上角提供导入日志、导入字体、对比日志、重置四个按钮，与拖放等效。载入第二份文件即进入对比模式，取消对比或重置可退出。

默认字体按圆体优先：圆体拉丁（Nunito / Quicksand / Varela Round）优先，其次圆体中文（MiSans / HarmonyOS Sans / OPPO Sans / 阿里巴巴普惠体 / 思源黑体），最后回退到系统字体。调用栈与代码块保持等宽，以保证缩进可读。

不启动游戏也可将历史日志转换为报告：

```bash
java -cp out/main;resources;out/test ReportGen "<日志路径>" ["<输出目录>"]
```

报告仅包含最长的 400 条记录，每条调用栈最多 24 层，否则文件体积会从数百 KB 增长到数 MB。没有真实时长的记录（旧格式、缺少 `STALL-END`）会标记为"旧格式"并加 `~` 前缀，不参与统计，因为其时长只能等于阈值本身，混入后会同时影响最长时长与分档结果。

## 配置

首次启动生成 `…\profiles\1.8\framespike\framespike.ini`：

| 键 | 默认 | 说明 |
|---|---|---|
| `enabled` | true | 总开关 |
| `stallThresholdMs` | 60 | 停顿判定阈值 |
| `maxDumps` | 2000 | 单次会话最多抓取次数 |
| `stackDepth` | 96 | 抓取的栈深度；**0 = 不截断**（日志与报告体积会明显变大） |
| `glFinishMode` | off | `off` 不修改字节码；`proxy` 接管调用点，需重启一次 |
| `chatNotify` | true | 聊天提示开关 |
| `chatMinMs` | 150 | 提示门槛 |
| `chatCooldownMs` | 1000 | 两条提示的最小间隔 |
| `cpuSampleSec` | 60 | 全线程 CPU 表输出间隔 |
| `commandName` / `commandAliases` | fs / framespike,fspike | 指令名与别名 |
| `ctrlFile` | 空 | 指令注册失败时的降级控制文件 |
| `autoReport` | true | `/fs stop` 时自动生成 HTML 报告 |
| `reportDir` | 空 | 报告输出目录，留空则与日志同目录 |

除 `glFinishMode` 外，其余配置项修改后约 2.5 秒自动热重载，日志中会记录变更的键名。

## 日志格式

```
=== FrameSpike 帧刺 v0.6.2  by Trusler ===
=== Minecraft 1.8.9 帧时间尖峰与卡顿分析 mod ===
[gc] 已挂 GC 通知监听（3 个收集器）：大 GC 记日志，停顿结算时自动标注是否 GC
patch net/minecraft/client/Minecraft: hooks=2/2
        命中: [notch av -> runGameLoop, notch s -> runTick]

03:03:52.104  STALL-DETECT 61ms  since='hudForge'  frames=8942  ticks=1204  stall#7  reason=写文件  ctx=dim=主世界 x=128 y=64 z=-256
  timeline:
    runTick              t+   0.412ms
    updateCameraAndRender t+   0.910ms
    renderWorldPass      t+   1.004ms
    hudForge             t+  33.221ms
  stack of "Client thread":
    java.io.FileOutputStream.writeBytes(Native Method)
    java.io.FileOutputStream.write(BufferedOutputStream.java:678)
    ...
  STALL-END    total=372ms  detected@61ms  since='hudForge'  next='runGameLoop'  reason=写文件  gc=G1 Old Generation 212ms

03:11:00.000  === CRASH ===  上次会话  file=crash-2026-10-06_01.02.30-client.txt
  time=2026-10-06 01:02:30
  desc=java.lang.NullPointerException: Ticking entity
  reason=类加载/remap
--- 崩溃栈前 24 帧 ---
  at ...

00:59:58.999  === JVM EXIT ===  uptime=1h52m6s  pendingStall=false  recording=true  lastCheckpoint='hudForge'  88ms 前
    runGameLoop  t+3ms
    hudForge     t+31ms
```

`STALL-DETECT` 是检测时刻，此时只能确定"至少 60ms"；`STALL-END` 才是真实时长。两行必须分离，否则一次 1.6 秒的冻结会被记录为 60ms，0.2.0 之前即为此问题，目前已有回归测试固定该数值。

## 注入点

| 类（运行时名） | 方法 | 说明 |
|---|---|---|
| `net.minecraft.client.Minecraft` | `func_71411_J` / notch `av` | runGameLoop |
| `net.minecraft.client.Minecraft` | `func_71407_l` / notch `s` | runTick |
| `net.minecraft.client.renderer.EntityRenderer` | `func_181560_a` / notch `a` | updateCameraAndRender |
| `net.minecraft.client.renderer.EntityRenderer` | `func_78471_a` / notch `b` | renderWorld |
| `net.minecraft.client.renderer.EntityRenderer` | `func_175068_a` / notch `a` | renderWorldPass |
| `net.minecraft.client.gui.GuiIngame` | `func_175180_a` / notch `a` | renderGameOverlay |
| `net.minecraftforge.client.GuiIngameForge` | `func_175180_a` / notch `a` | renderGameOverlay（Forge） |

同时列出两套名称的原因：1.8.9 运行时 MC 类的成员名为 notch 混淆名，`runGameLoop` 实际是 `av()V`。spark 栈中显示的 `func_71411_J` 是其反混淆后的名称。按该名称编写 hook 会全部 0 命中，且不会产生任何报错。此处曾耗费整日排查。

## 实测数据

24 分钟真实对局、100 次停顿，按栈顶归因：

| 归因 | 累计 | 占比 |
|---|---|---|
| 类加载 / remap / 字节码 | 2439ms | 39.3% |
| 零散一次性初始化（21 条各 1 次） | 1281ms | 20.7% |
| Windows 窗口合成 / 消息泵 | 914ms | 14.7% |
| GL 驱动调用 | 782ms | 12.6% |
| 写文件 / 音效 / 字体缓存 | 783ms | 12.7% |

≥200ms 的 18 次中，最长 1589ms，为 Xaero 小地图进服时加载全部路径点。其后依次为 750ms（窗口消息泵）、620ms（OptiFine 自定义天空）、464ms（换维度时反射创建 WorldProvider），以及 6 次 219~372ms，为 Lunar 投递到主线程的写文件任务。

同场 GC 仅 16 次、418ms，占 2.6%，可排除为本次卡顿主因。这也解释了此前查阅 GC 日志无所得的原因。

## 离线验证

最需防范的情况是字节码修改出错，但游戏既不报错也不崩溃，静默漏注入若干检查点而无任何提示。因此提供了四套无需启动游戏的自检：

```
SelfTest     16 项   注入数 / 替换数 / JVM 字节码校验器 / 停顿端到端
CmdTest     100 项   Proxy 注册 / 形态分派 / 14 子命令 / Tab / 时长结算回归 / 分类器 / 报告与服务 / stop 语义
RoundTrip    两段    40 个真实类往返无损 + 真实 notch 类注入验证
ReportCheck  parse_check 54 项（样例，含崩溃+gc）/ 52 项（真实 2MB 日志）
             page_check  75 项（假 DOM 整页跑：渲染 / 导入 / 对比 / 重置 / 火焰图交互）
打包          通过    jar cfm + MANIFEST 检查
```

RoundTrip 第二段最为关键：对真实游戏类运行 transformer，断言 `ave.class` 注入 2 个检查点、`bfk.class` 注入 3 个，方法集合保持不变，唯一差异是目标方法开头新增的 `LDC + INVOKESTATIC`。

ReportCheck 是报告页的回归防线。页面内嵌的 JS 解析器与 Java 侧口径必须逐字段相等：同一条日志分别经两侧解析，比对停顿条数、累计时长、分档、每条明细的调用栈与时间线，以及火焰树逐节点结果，真实日志的 9280 个节点全部一致。标准答案由 `JsonDump` 生成。

```bash
python build.py        # 或双击 build.bat。只需 JDK 17，不需要 Gradle / JDK 8
# 单独运行网页相关两套，需要 node
node test/parse_check.js resources/framespike/report.html
node test/page_check.js  resources/framespike/report.html
```

## 项目结构

```
src/framespike/      零 MC 依赖的核心：Cfg 配置 · FrameSpike 运行时与指令 · Cmd 指令反射桥 · Report 报告
src/forge189/        只有 1.8.9/1.12.2 需要的两个入口：FrameSpikePlugin（FML）+ FrameSpikeTransformer（ASM）
fabric/              Fabric 模块：入口 + FabricChat（聊天出口）+ FabricCommands + 2 个 mixin + Gradle/Loom
neoforge/            NeoForge 模块：入口 + NeoChat + NeoCommands + 2 个 mixin + Gradle/NeoGradle
build_all.py         一键编三个平台并收集产物到 dist/
stubsrc/             离线自检用的假类（Minecraft / EntityRenderer / ICommand / ClientCommandHandler …）
test/                SelfTest · CmdTest · RoundTrip · ReportGen · JsonDump · parse_check.js · page_check.js
lib/stubapi/         从 Lunar 自带 jar 中抽取的编译期 API（ASM、IFMLLoadingPlugin）
assets/              logo.svg + icons/，27 个手写 SVG 图标（mod 图标用 tools/GenIcon 渲染成 PNG）
resources/           report.html 报告模板 + framespike/logo.png（mod 图标）+ mcmod.info（build.py 生成）
probe/               整机探测：sysprobe.ps1 · join_stalls.py · etw_capture.ps1，无需修改 mod
dist/                成品 jar + report-示例.html（真实日志生成的样板）
README.html          浏览器版，含可展开目录树
CHANGELOG.md         更新日志
```

## 多版本

核心（配置 / 看门狗 / 归因分类器 / 报告生成 / 崩溃感知 / GC 关联 / 指令引擎）**与游戏版本无关**，
三个平台共用同一份源码；平台差异只有「注入点名字」和「加载器入口」两处。

| 目标 | 加载方式 | 注入点名字 | 产物 |
|---|---|---|---|
| 1.8.9（Lunar legacy） | Forge coremod | notch 名 `av` / `s` / `a` / `b` | `FrameSpike-Forge-1.8.9-1.12.2-0.7.0.jar` |
| 1.12.2 | Forge coremod（Forge 先 remap 成 SRG） | SRG 名 `func_71411_J` … | 同一份 jar |
| 1.16.5+ / 1.20.1 | Fabric（Lunar 现代版只支持 Fabric addon） | Yarn 名 `MinecraftClient.render` / `tick` | `FrameSpike-Fabric-1.20.1-0.7.0.jar` |
| 1.20.1+ | NeoForge | 官方映射名 `Minecraft.runTick` / `tick` | `FrameSpike-NeoForge-1.20.1-0.7.0.jar` |

三边的检查点标签是同一套（`runGameLoop` / `runTick` / `grender`），所以帧率、分档、时间线、
归因、报告页完全共用，报告页不需要按版本分叉。`/fs version` 会显示探测到的环境
（例如 `环境=Fabric / Yarn 映射（1.20.1）`），用来确认到底命中哪一套。

代码分层（两个 Gradle 模块用 `sourceSets.main.java.srcDir '../src/framespike'` 直接编译核心，不复制不改）：

```
src/framespike/           零 MC 依赖的核心：Cfg · FrameSpike · Cmd · Report
src/forge189/framespike/  FrameSpikePlugin（FML 入口）+ FrameSpikeTransformer（ASM 注入）
fabric/src/main/java/     FrameSpikeFabric + FabricChat + FabricCommands + mixin/
neoforge/src/main/java/   FrameSpikeNeoForge + NeoChat + NeoCommands + mixin/
```

### 构建

```bash
python build_all.py            # 三个平台一起编，产物统一收进 dist/
python build_all.py fabric     # 只编 Fabric
python build.py                # 只编 1.8.9/1.12.2 coremod（纯 javac，不需要 Gradle）
python out/verify_hooks.py     # 对着真实 MC jar 校验 mixin 目标方法是否存在
```

换 MC 版本只改 `fabric/gradle.properties` / `neoforge/gradle.properties` 里的版本号。

### 几条刻意的取舍

- **Mixin 只挑参数全是原始类型的方法**（`render(Z)V` / `tick()V` / `render(FJZ)V`）。
  描述符里一旦出现 MC 类型就得靠 refmap 把命名 remap 过去，换版本最容易在注入阶段出问题；
  原始类型描述符跨版本稳定。
- **注入一律 `require = 0`**：换版本后方法名对不上只会少注入，**绝不会让游戏起不来**。
  代价是失败是静默的，所以入口会在 20 秒后检查 `frames==0` 并大声报警。
- **换版本先验名字**：`out/verify_hooks.py` 用 javap 直接读映射后 jar 的方法表，
  不用开游戏就能确认「这个方法名 + 这个描述符」是否存在。Fabric Loom 生成的 refmap 是同一件事的
  第二份证据（映射不到就不会有条目）。
- **依赖版本先查元数据再写**：NeoGradle 要求的 Gradle API 版本写在 `.module` 里
  （7.0.180 → Gradle 8.10，7.0.185+ → 8.13）；它的 DSL 也分代（7.0.x 是 `minecraft { }` + 依赖坐标，
  7.1.x 才是 `neoForge { version = ... }`）。**NeoForge 1.20.1 的包名仍是 `net.minecraftforge.*`**，
  只有 Maven groupId 变成了 `net.neoforged`。

## 已知限制

仅采集客户端线程，后台线程的调用栈不可见，由 CPU 表部分兜底。如需全量采集，须修改 `thread_dumper` 配置。

采样间隔为 2ms，叠加 Windows 定时器粒度约 15.6ms，因此 50ms 级别的抖动只能给出近似值；200ms 以上的停顿检出稳定可靠。

单次冻结无法定位到"具体某一行"之外的信息，只能提供栈顶若干帧。

`glFinishMode=proxy` 需重启一次才生效，且**只在 1.8.9/1.12.2 的 Forge coremod 下可用**：
1.13+ 的原版每帧已不再调 `glFinish`，Fabric / NeoForge 侧也没有字节码改写通道，
入口会把该配置明确降级为 `off` 并写日志，而不是让 `/fs status` 假装"就绪"。

Fabric 侧的 `/fs` 依赖 Fabric API 的客户端命令 API；没装 Fabric API 时不会静默失效 ——
入口会提示改用 `framespike.ctrl` 文件通道（每行一条子命令）。

NeoForge 1.20.1 只有 `RegisterCommandsEvent`（没有客户端命令 API），因此 `/fs` 需要**进入世界之后**才可用；
主菜单阶段的控制走 `framespike.ctrl` 文件通道。

世界上下文通过反射获取，失败时跳过；若取值异常，以调用栈为准。

崩溃感知依赖文件名约定（`crash-*.txt`）与若干候选目录。若 gameDir 不在候选范围内，则无法监控。

## 署名与许可

FrameSpike 帧刺 v0.7.0，by Trusler。

签名出现在 jar 的 MANIFEST、每次启动的日志 banner、`/fs status`、`/fs help`、报告页页脚以及每个源码文件头。

许可：暂未指定。当前为自用工具，如需分发请先补充 LICENSE。
