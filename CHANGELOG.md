# FrameSpike 更新日志

署名：**Trusler** · 目标环境：1.8.9 / 1.12.2（Forge coremod）· 1.16.5+（Fabric）· 1.20.1+（NeoForge）

## 0.7.0（多版本落地：三平台共用一份核心）

**从「1.8.9 专用」变成 Forge coremod + Fabric + NeoForge 三平台，核心代码一个字节都不复制。**

- **源码分层**：`src/framespike/` 只留零 MC 依赖的四个核心类（Cfg / FrameSpike / Cmd / Report），
  两个 Forge 入口搬到 `src/forge189/framespike/`。动手前先量过：核心对加载器**零反向依赖**，
  所以两个 Gradle 模块可以用 `srcDir '../src/framespike'` 直接编译
- **核心加三个加载器中立挂点**
  - `Cfg.setBaseDir(gameDir)`：不设时保持 Lunar 老路径，1.8.9 行为一字不变
  - `FrameSpike.setGameVersion(v)`：`MOD_TITLE` / `SIGN` 从 `final` 改成普通字段 ——
    `final` 的字符串拼接是编译期常量，会被内联进所有调用方，换游戏版本就改不动了
  - `Cmd.Sink`：聊天出口接口。现代 MC 的 `Text` / `Component` 与 1.8.9 的 `ChatComponentText`
    完全是两套，与其在核心里堆版本分支，不如让加载器交一个实现进来；没装 Sink 时走原反射路径
- **Fabric 模块（1.16.5+ / 1.20.1，Loom 1.7.4）**：ClientModInitializer 入口 +
  3 个 mixin（`MinecraftClient.render(Z)V`→runGameLoop、`tick()V`→runTick、`GameRenderer.render(FJZ)V`→grender）
  + 聊天出口（§颜色码翻成 Text、本地报告用 OPEN_FILE 直接开浏览器）+ `/fs` 注册（Fabric API 软依赖）
- **NeoForge 模块（1.20.1，NeoGradle 7.0.180）**：同一结构，mixin 换成官方映射名（`Minecraft.runTick(Z)V`）
- **两个平台都真跑过构建**；产物名统一成 `FrameSpike-<loader>-<游戏版本>-<mod版本>.jar`：
  `FrameSpike-Forge-1.8.9-1.12.2-0.7.0.jar` / `FrameSpike-Fabric-1.20.1-0.7.0.jar` / `FrameSpike-NeoForge-1.20.1-0.7.0.jar`
  命名由构建脚本产出（`build.py` 的常量、两个 Gradle 的 `archivesName`），不是事后手改文件
- **Forge coremod 放开 1.12.2**：反编译 FML 的 `CoreModManager` 确认，`@IFMLLoadingPlugin.MCVersion`
  是**严格字符串相等**、且不匹配就 `return null` 直接跳过这个 coremod（**不支持版本范围**）。
  所以要一个 jar 同时覆盖 1.8.9 与 1.12.2，只能去掉这个注解（代价是 FML 多打一行无害的 WARN）。
  `mcmod.info` 的 `mcversion` 走的是另一套匹配（支持范围），改成 `[1.8.9,1.12.2]`。
  1.12.2 的 SRG 名与描述符已对着 `mcp_config-1.12.2` 的 `joined.tsrg` 逐条核对通过
  （`func_71411_J ()V` / `func_71407_l ()V` / `func_181560_a (FJ)V` / `func_78471_a (FJ)V` /
  `func_175068_a (IFJ)V` / `func_175180_a (F)V` 全部命中）
- **新增 `out/verify_hooks.py`**：用 javap 直接读映射后 MC jar 的方法表，不开游戏就能确认
  mixin 的「方法名 + 描述符」真实存在。Fabric 1.20.1 三条全部命中
  （refmap 独立印证：`MinecraftClient.render(Z)V → class_310.method_1523(Z)V`）
- **新增 `build_all.py`**：一键编三平台、收产物到 dist/、跑 hook 校验；含代理自动探测
- **踩到并已记录的坑**
  - **Gradle 不读 `HTTP_PROXY` 环境变量**：Windows 上设了本地代理时 Python 会走、Gradle 不走，
    表现为某些仓库直连被 reset，而报错看起来像仓库地址/插件坐标写错
  - **NeoForge 1.20.1 的包名仍是 `net.minecraftforge.*`**：只有 Maven groupId 变成了 `net.neoforged`，
    `net.neoforged.*` 是 1.20.2+ 才有的；混用会报一串「程序包不存在」
  - `@Mod` 在 1.20.1 只有 `value()`，没有 `dist` 元素，客户端限定要在构造里判 `FMLEnvironment.dist`
  - **NeoGradle 的 DSL 与 Gradle 版本要求分代**：7.0.x 是 `minecraft { }` + 依赖坐标
    `implementation "net.neoforged:forge:<mc>-<build>"`，7.1.x 才是 `neoForge { version = ... }`；
    所需 Gradle 版本写在 `.module` 的 `org.gradle.plugin.api-version`（7.0.180→8.10，7.0.185+→8.13）
  - `jarJar` 任务缺 `java { toolchain { languageVersion } }` 时报的错误完全看不出跟 toolchain 有关
  - **诚实降级**：Fabric / NeoForge 下 `glFinishMode=proxy` 不成立（1.13+ 每帧不再调 glFinish，
    也没有字节码改写通道），入口强制降级为 `off` 并写日志

## 0.6.3（多版本准备）

- **注入点改成多版本候选表**：一个位置给出各环境可能的运行时名字，命中哪个用哪个 ——
  1.8.9 是 notch 名（`av` / `s` / `a` / `b`）、1.12.2 Forge 是 SRG 名（`func_71411_J`…）、
  1.16.5+ 是官方映射名（`run` / `runTick` / `GameRenderer.render` / `renderLevel` / `renderHand`）。
  描述符也按版本给候选，`""` 表示只看名字不限签名。命中后把命名风格上报，`/fs version` 里能直接看到
  （「1.8.9 型（notch 混淆名）」/「1.12.2 型（Forge SRG 名）」/「1.13+ 型（官方映射名）」）
- 1.12.2 走同一套 coremod 入口（`IFMLLoadingPlugin` + `IClassTransformer`，Forge 会先 remap 成 SRG），
  代码侧只需候选表即可覆盖
- **mod 元数据与图标**：jar 里新增 `mcmod.info`（名称 / 描述 / 版本 / 作者 / 图标）与
  `framespike/logo.png`（128×128，由 `tools/GenIcon` 用 Java2D 从 logo.svg 的几何渲染，零外部依赖）。
  ModList 里能看到「FrameSpike 帧刺」和说明；`mcmod.info` 由 `build.py` 按 VER 生成，别手改

## 0.6.2

- **修复 `/fs tail` 一直说「日志里还没有 STALL 记录」**：文件回退路径的匹配串还是 0.2.0 时代的
  `"  STALL "`，而 0.3.0 起日志头已改叫 `STALL-DETECT`，永远匹配不上（只有手动 dump 的 SNAPSHOT 能命中）。
  现在改成**内存环形表优先**（最近 60 条摘要，不看日志体积、也不受栈全量输出影响），
  文件回退同时认 `STALL-DETECT` / `STALL-END` / `SNAPSHOT`，窗口 32KB → 256KB
- **修复卡顿聊天提示送不到游戏界面**：每会话首批停顿发生在你敲第一条指令之前，
  此时没有 sender，只能走反射；而反射那条路会在 Lunar 的多层类加载器上抛
  `NoSuchMethodError: void <init>() not found`（只试了第一个候选方法）。
  现在遍历 declaredMethods/字段、逐个 try/catch、跳过 `<init>`，并补了「静态字段」这条退路；
  失败每会话只记一次日志（原来会刷满 42 行）
- **聊天提示收紧**，不再顶到聊天框右边：字段间改单空格，GC 只留时长（收集器名字在日志/报告里），
  形如 `[FS] 234ms 类加载/remap MethodNode.getLabelNodes:549 [GC 11ms]`
- 修复版本号显示：反编译恢复时 `MOD_VERSION` / `SIGN` 被编译期内联成字面量，
  `/fs` 抬头、快速上手和帮助里一直写死 v0.6.0，现已全部改回引用常量
- **`/fs help` 重写**：分 5 组（记录 / 取证 / 报告 / 调参 / 其它），两列对齐、行尾带当前值，
  未知子命令的回显也收敛成一行；用法串直接由子命令表生成，新增子命令不会再漏
- **新增 `/fs version`**：报版本、作者、副标题、java 版本、ini 与日志路径、记录开关 / 阈值 / 栈深 /
  抓取上限、命令别名与聊天设置、报告服务状态，并说明内置能力；`/fs help version` 有单条详情
- **报告默认只统计本段记录**（`RECORDING STARTED` → `STOPPED`），不再把一整天的历史都算进来；
  没手动 start 过时退回「最后一次会话（banner 起）」。`/fs report all` 出全量，
  `serve` 的页面上加 `?whole=1` 同理。崩溃块与会话起止仍从整份日志扫（崩溃可能发生在记录段之后）
- **报告路径可点**：`/fs stop`、`/fs report` 完成后的聊天提示挂了 `OPEN_URL` 点击事件，
  点一下直接唤起浏览器打开报告（`file:///` 也行）；反射挂不上就退回纯文本，不影响正事

## 0.6.1

- **归因分类器大改：从 9 条规则扩到 24 条**，报告里的类别从「一堆看不懂的类名」变成能读的东西
  - 新增：读文件、Mixin 注入、区块渲染、实体/模型渲染、GUI、直接内存、等待/限速、压缩/解压、
    序列化/解析、图像、内嵌浏览器、Essential、异常构造、JDK 内部、游戏自身
  - 混淆类不再把乱码当类别名：Lunar 的混淆类统一归成 `Lunar 混淆类`，其余归 `混淆类`
  - **报告解析时会用栈重新归类**：老日志里那些「类.方法」兜底项，打开报告就能看到新类别，不用重录
- **归因大类可展开**：点一行展开成员（按栈顶帧分组，带出现次数与累计时长），
  解决「只看到『类加载/remap 26s』却不知道里面是什么」
- 模组侧实时归类、报告 Java 解析、报告页 JS 解析**共用同一张规则表**，
  由 `parse_check` 逐字段对口径（两边一旦漂移立刻红）

## 0.6.0

- **报告页改成查看器式布局**：左侧资源树（概览 / 分析 / 停顿分组 / 崩溃）+ 多标签页 +
  多轨道时间轴（WPA 风格，可框选缩放）+ spark 式调用树（可逐层展开、一键展开全部）+
  单次停顿代码视图（栈帧着色、复制）+ 概览（KPI、分档、归因、最长的几次、会话）
- **修复：火焰图颜色发灰** —— 每个色块被 `opacity: 0.35~0.8` 的透明调制压住了，
  现在全不透明，改用内描边区分同色相邻块
- **修复：标签页越点越多** —— 原先每看一条停顿就新开一个标签，点几下堆满一排；
  现在停顿用**单个可复用预览标签**，换一条只替换内容与标题（时间 · 时长 · 归因色点）
- **时间轴改为模组原生支持**：日志新增 `t0=` / `t1=`（停顿起止，epoch 毫秒）与会话起止时间戳，
  报告页时间轴直接用原生值，不再用「时刻 − 检测值」反推；老日志自动回退到旧算法
- **崩溃页与对比页接进新查看器**：资源树出现「崩溃」组（带条数），崩溃记录单独成页；
  工具栏加「对比日志 / 取消对比」，逐项对比指标、分档、归因与崩溃次数
- 停顿视图显示 `gc=` 与 `ctx=`（世界位置）；全部日志的原因列加 GC 标记；概览加「会话」行
- **调用栈不再截断**：报告侧去掉 24 层上限，日志里有多少层就展示多少层（原先会写「...(N 层更深)」）；
  模组侧 `stackDepth` 默认 48 → 96，且 **0 = 不截断**。注意：栈越深，日志与报告体积越大
- **原因归因改竖排**：长尾几十条不再横向铺成多列，逐行「色点 + 名称 + 累计/占比」，读起来不再错行
- 自检：parse_check 61 项（t0/t1/ctx/会话与 Java 侧逐字段一致）、page_check 87 项（含查看器骨架）

## 0.5.0

- **崩溃感知**：启动时与每 30 秒扫描 crash-reports（Lunar gameDir / cwd / 官方启动器几个位置），
  新崩溃解析 Description + 栈前 24 帧并**用同一套分类器归因**，写 `=== CRASH ===` 块进日志；
  已见文件记 `framespike.state` 不重复报；下次启动聊天提示「检测到上次崩溃」
- **崩溃前时间线快照**：JVM 退出钩子把死前最后 16 个检查点的时间线 + uptime + 未结算停顿
  写成 `=== JVM EXIT ===` 块 —— 崩溃时没有"下一个检查点"来结算，这段就是死前 1 秒
- **GC 关联**：挂 GarbageCollector 通知监听；停顿窗口里有 GC 就在 `STALL-END` 行加 `gc=` 标注、
  聊天提示带 `[GC]`，报告页明细也带 —— 每条停顿都能自动排除/坐实是不是 GC；≥200ms 的大 GC 单独记日志
- **fps 骤降检测**：60s 采样的滑动窗口，均值 ≥60 且当前掉到一半以下 → `[fps-drop]` 行 + 聊天提示（5 分钟冷却）
- **世界上下文**：停顿行尽力附带维度/坐标（`ctx=dim=主世界 x=.. y=.. z=..`，反射尽力而为，失败静默，成功后缓存）
- **ini 热重载**：改 ini 约 2.5 秒后生效（threshold / chat / cpuSampleSec / maxDumps / stackDepth /
  enabled / logFile），日志写明改了哪些键；`glFinishMode` 仍需重启一次
- **报告页**：新增「崩溃」区块（时间/异常/归因/报告文件）+「对比」模式
  （选第二份日志，指标/分档/归因逐项对比，变差红、变好绿）；「取消对比」「重置」随时退出
- 纯客户端，零外部传输（无 webhook）；自检 37+54+52+75 项全绿

## 0.4.0

- **`/fs stop` 语义改了：只停记录，聊天播报一直开着**（0.4.0 内改动）
  - 旧语义：stop = 检测/记录/聊天全停 —— 单人里 stop 完再进服务器，卡了也没消息
  - 新语义：stop 之后看门狗照常检测，聊天三道闸照走；只是不写 STALL 日志、不进本段统计
  - `/fs start` 恢复完整记录；`/fs stop` 的回显、status、help 都写明新语义
  - 回归测试：stop 之后触发停顿，断言日志零新增 STALL、聊天照发

- **报告页可导入自己的文件**（不用重开游戏、不用重新生成）
  - 把任意一份 `frame-spikes.log` **拖进页面**就直接换成那份数据（也能点右上角「导入日志」选文件）
  - 把 `.ttf / .otf / .woff2` 拖进去就**换掉整个页面的字体**（`FontFace` 内嵌进页面，不联网）
  - 「重置」一键回到报告自带的那份数据 + 默认字体
  - 拖放时全屏虚线提示，按扩展名分流；一次只吃一份日志，免得两份统计混在一起
  - 状态条实时说明：导入了什么、多少条停顿、有几条没有真实时长、有没有 CPU 采样
- **字体改圆滑**
  - 字体栈：圆体拉丁（Nunito / Quicksand / Varela Round）→ 圆体中文（MiSans / HarmonyOS Sans /
    OPPO Sans / 阿里巴巴普惠体 / 思源黑体）→ 系统兜底；**装了哪个用哪个**，不用配置
  - 字号、圆角（14→18px）、按钮胶囊形、字重都跟着一起调柔和；**栈和代码仍保持等宽**，
    不能因为"圆"而看不出缩进
  - 数字单独一套圆体 + `tabular-nums`，表格里的 ms 依然对齐
- **报告页自带一份 JS 解析器**（`/*==PARSE-BEGIN==*/` 区块）
  - 页面/命令行共用，`node test/parse_check.js <log>` 能直接跑；导进来的日志走的就是这份
  - 好处是导入路径不依赖 Java 侧：报告页在哪台机器、哪个浏览器里打开都能解析
- **新增两套网页自检**（都接进 `build.py` 第 7 步）
  - `test/parse_check.js`：模板占位符 / 导入入口 / 整段脚本语法 / **JS 解析器与 Java 口径逐字段对齐**
    （停顿条数、时长、分档、每条明细的栈与时间线、火焰树逐节点，真实日志 9280 个节点全等）
  - `test/page_check.js`：**假 DOM 整页跑一遍** —— 渲染、拖入日志、拖入坏文件、拖入字体、
    字体坏文件、重置、火焰图聚焦与 Esc 回退、悬停提示，共 61 项
  - 顺带一个真实修复：JS 解析器原先把 CPU 采样返回成数组，而渲染层按 `x.fps` 取值，
    导入的日志 CPU 面板会静默画成一条直线 —— 现已统一成 `{t,cpu,fps,tps}` 对象
- 新增命令行工具 `JsonDump`（把日志转成报告用的 JSON，给上面那套对口径的测试当标准答案）
- `/fs help report`、`/fs help serve` 补上"可以拖别的日志/字体进去"的说明
- **正式标识定稿**：FrameSpike 帧刺 · Minecraft 1.8.9 帧时间尖峰与卡顿分析 mod ——
  日志 banner（两行）、`/fs` 快速上手与 help 抬头、报告页标题/页脚、logo.svg 全部统一
- **报告页重排**（排版去模板化，功能与数据口径一字未动）：
  - 左侧**可展开目录树**（概览 / 分析 / 明细），高亮跟随滚动，窄屏自动收成顶部
  - 去掉渐变 KPI 瓦片、大面积卡片那套模板味：扁平面板 + 小节标题短线 + 更松的间距
  - **动效保留**：整节滚动淡入、目录树高亮跟随、悬停过渡、渐变线（respect prefers-reduced-motion）
  - 导入日志/字体、解析口径、火焰图交互全部原样，两套自检全绿
- **文档重写**：README.md 对齐 0.4.0（新增导入说明、两套自检、14 子命令）；
  README.html 重写 —— 文档式布局 + 左侧可展开目录树（滚动跟随高亮），排版去模板化、动效保留

## 0.3.0

- **HTML 分析报告**（类似 spark 的查看器，但不需要联网）
  - `/fs report` 生成**自包含**的 `report-<时间>.html`，数据内嵌，双击即可看
  - 内容：时长分档 / 原因归因 / **火焰图**（层=调用深度，宽=累计停留时长，可点击聚焦、Esc 回退）/
    CPU 与帧率趋势 / 停顿明细（可展开完整栈与帧内时间线）
  - **体积控制**：最多带最长的 400 条，每条栈最多 24 层（否则几百 KB 变几 MB）
  - 后台线程生成，完成后把路径播报到聊天 —— 不在客户端线程上解析日志+写文件，避免自造停顿
- **本地报告服务** `/fs serve [端口|off]`：只绑 `127.0.0.1`，默认 8731。
  页面每次刷新都重新读日志（等于"活的"），`/log` 路径给原始日志。零依赖（JDK 的 ServerSocket）
- `/fs stop` 自动出报告（`autoReport`，可关）
- 新增命令行工具 `ReportGen`：**不开游戏**也能把旧日志变成报告
  （`java -cp out/main;resources;out/test ReportGen "<日志>"`）
- **统计口径修正**：只统计有真实时长（有 `STALL-END`）的记录；旧格式记录在报告里标成
  "旧格式"并加 `~` 前缀，不参与最长/分档/累计 —— 否则它们一律等于阈值，会把统计带偏
- 修复两个解析 bug：① 日志行以时间戳开头，判头行必须用 `contains` 而不是 `startsWith`；
  ② 帧内时间线的 `t+` 与数值可能被补位空格分开也可能粘在一起，两种都要吃

## 0.2.0

- **署名**：日志开头、`/fs status`、`/fs help`、jar 的 MANIFEST 都带上版本与作者
- **帮助体系重做**
  - 裸 `/fs` → **快速上手 4 步**（进服 start → 打一局 → stop → tail）
  - `/fs help` → **分组全表**（记录 / 取证 / 调参 / 其它），行尾带**当前值**（阈值、chat 开关、门槛、glFinish 状态）
  - `/fs help <子命令>` → **单条详情**（用法 + 说明 + 当前值 + 备注）
  - 未知子命令会回显收到的参数数组，只给前 3 行帮助不刷屏
- **文档**：`README.md` 重做（非传统排版：Unicode 块字符频谱条 / 箭头管线 / 折叠分组；27 个手写 SVG 图标落成 assets/icons/）；
  另附 `README.html` 浏览器版（自绘 SVG、交互式指令台、动效）
- **卡顿聊天提示**：`[FS] 312ms  写文件  FileOutputStream.writeBytes:0`
  - 原因分类：写文件 / 类加载-remap / 窗口合成 / GL驱动 / 音效 / 字体缓存 / 小地图 / OptiFine / 网络
  - 三道闸防刷屏：时长门槛 `chatMinMs`（150ms）+ 冷却 `chatCooldownMs`（1000ms）+ `chat on|off`
- **修复：停顿时长测量错误**（重要）
  - 旧逻辑记的是"刚越过阈值那一刻"，任何 > 阈值的冻结都被记成 ~60ms
  - 现在分两段：检测时抓栈（`STALL-DETECT`），**下一个检查点到来时结算真实时长**（`STALL-END total=`）
  - 回归测试：同一个停顿旧逻辑 61ms / 新逻辑 551ms
- **修复：帧数/tick 数混为一谈** —— 原来那栏其实数的是 `runTick`（20/s）。
  现在分成 `帧数(fps)` 与 `tick(tps)` 两项，CPU 表也加了 `fps≈/tps≈`
- **修复：hook 全不命中**（0.1.0 的致命问题）
  - 1.8.9 运行时 MC 类的成员名是 **notch 混淆名**（`Minecraft.runGameLoop` = `av()V`），
    不是 spark 栈里显示的 SRG 名。0.1.0 按 SRG 写 → 7 个 hook 全部 0 命中 →
    检查点永不触发 → 依赖帧计数的延迟注册永不执行 → `/fs` 未知指令且毫无报错
  - 现在同时匹配 SRG 与 notch 两列，并把命中项打进日志；0 命中时自动 dump 该类的真实方法名
- **修复：`processCommand` 参数约定** —— vanilla 会先 `dropFirstString`，
  `/fs start` 到手是 `["start"]`。现在两种约定都归一化
- **新增 Tab 补全**（`/fs <TAB>`、`/fs st<TAB>`、`/fs glfinish o<TAB>`），
  并加 `tabCalls` 计数用于判断补全请求有没有到 mod
- **新增指令**：`help`、`chat`、`chatmin`（共 12 个子命令）
- **`glFinish` 干预改为可热切**：`glFinishMode=off|proxy`，proxy 时 `/fs glfinish on|off` 游戏内秒切
- `/fs stop` 汇总改版：真实时长分档 + 平均 fps/tps + `glFinishSites`

## 0.1.0

- 首个版本：帧暂停取证（检查点 + 停顿抓栈 + 帧内时间线 + 全线程 CPU 表）
- `glFinish` 调用点替换（默认 off）
- 客户端指令系统（Proxy + 反射注册 + 形态分派）
- 离线验证：存根注入自测（含 JVM 校验器）、真实类往返无损、真实 notch 类注入验证
