# Lunar 1.8.9 卡顿诊断报告

样本：`https://spark.lucko.me/9EcAyxjHvj` + `C:\Users\Huang\.lunarclient\profiles\1.8\logs\latest.log`

## 一、样本信息（全部来自数据，非推测）

| 项 | 值 |
|---|---|
| 采样起止 | 2026-10-05 03:02:01.383 → 03:04:03.473（122.1 s） |
| 采样引擎 | spark-forge（Lunar 内置），间隔 4000 µs = 4 ms |
| 采样对象 | **只有 Client thread**（thread_dumper = SPECIFIC, id=1） |
| 平台 | Forge 11.15.1.2318+ichor / MC 1.8.9 |
| ticks | 2436 → 与 122 s @20TPS 吻合 |
| TPS | 19.98 / 19.68 / 19.89（三个时间窗） |
| 客户端堆 | used 1.79 GB / committed 3.07 GB |
| 会话 | 日志里 `Setting user:` 只出现一次（02:58:51）→ **全程同一次启动，没有重启** |

## 二、结论（按证据强度排序）

1. **卡顿不是 GC、不是网络、不是某个 mod 的 Java 代码造成的。** 三条都有关键反证。
2. **主线程一路 91%–98% 满载，没有任何余量。** 这是"时不时卡一下"的结构性原因：突发负载无处吸收。
3. **主线程 53.4% 的时间耗在 OpenGL 驱动调用里**（其中显式 `glFinish` 一项就 6.75%），客户端是 **GPU / 渲染提交路径受限**，不是逻辑受限。
4. `nglFinish` 每帧一次、调用点固定在 `EntityRenderer.renderWorldPass` 末尾 → 与本机 OptiFine 的 **Smooth FPS（`ofSmoothFps:true`，"flushes graphics driver buffers"）** 行为一致。它把"GPU 侧一次抖动"直接放大成"CPU 整个冻住"，正是看门狗记录的 `Pause - Unknown`。
5. 日志里 5 次 `GL ERROR 1282: Invalid operation @ Pre render`，其中 3 次与停顿出现在同一秒或 ±1 s。**相关性存在，因果方向未定**（两者也可能是同一个渲染/驱动毛病的两种表现）。
6. 3 处停顿聚集（进服倒计时、对局中、03:13 附近）都伴随 GL 报错或高负载窗口，不像周期性资源清理。

## 三、这 121.8 s 花在哪（self 时间，占主线程总时间）

自校验：所有节点 self 时间之和 = 121832 ms ≈ 采样时长 122.1 s（覆盖 99.8%），方法可靠。

| 归属 | 时间 | 占比 | 说明 |
|---|---|---|---|
| `org.lwjgl.*`（GL 驱动） | 65 012 ms | **53.4%** | `GL11` 45.7 s、`WindowsDisplay.nUpdate` 8.3 s、`nSwapBuffers` 5.9 s |
| 原版 Minecraft | 19 260 ms | 15.8% | 最大单项 `RenderGlobal.renderEntities` self 2.0 s + HashSet 0.5 s |
| JDK / Java 库 | 17 252 ms | 14.2% | 含限帧器 `Thread.sleep` 5.7 s + `yield` 0.3 s |
| Lunar 本体（混淆类） | 14 800 ms | 12.2% | 最大单项 `HICICCICOIIIOIHICHROHHICRIRCCO...get` 2.7 s |
| 第三方 mod / 库合计 | ≈ 5 500 ms | 4.5% | 见下表 |

主线程最贵的 6 个叶子方法：

| 方法 | self | 占比 | 调用点 |
|---|---|---|---|
| `GL11.nglDrawArrays` | 9 932 ms | 8.15% | Tessellator / RenderItem / 小地图 |
| `GL11.nglCallList` | 8 264 ms | 6.78% | Lunar HUD/nametag 显示列表 |
| `GL11.nglFinish` | 8 224 ms | **6.75%** | `EntityRenderer.renderWorldPass`（每帧一次） |
| `WindowsContextImplementation.nSwapBuffers` | 5 876 ms | 4.82% | `Display.swapBuffers` |
| `Thread.sleep` | 5 716 ms | 4.69% | `Display.sync` 限帧器（正常） |
| `WindowsDisplay.nUpdate` | 5 160 ms | 4.24% | Windows 消息泵 |

第三方 mod 实测开销（self，inclusive 会虚高）：

| mod | self | 占比 | 备注 |
|---|---|---|---|
| Xaero's Minimap | 1 484 ms | 1.22% | 在 Client thread 上做 FBO 渲染 + `MinimapWriter.writeChunk` 写地图数据 |
| Forge 框架 | 1 256 ms | 1.03% | 事件总线 |
| fastutil | 1 076 ms | 0.88% | 库 |
| Essential | 528 ms | 0.43% | |
| ReplayMod | 308 ms | 0.25% | 本场未在录制 |
| OptiFine | 160 ms | 0.13% | |
| netty | 44 ms | **0.036%** | 见第四节 |
| JEI / HitRangeMod / spark / 其他 | < 50 ms | < 0.05% | |

时间窗对比（窗口边界 = 60 s / 60 s / 2.1 s，末段被截断，勿当异常）：

| 窗口 | 区间 | 主线程忙 | 限帧器等待 |
|---|---|---|---|
| 1 | 03:02:01–03:03:01（进服 + 倒计时 + 开打 20 s） | 976 ms/s | 1.3 ms/s（几乎没睡） |
| 2 | 03:03:01–03:04:01（对局后半 + 结算） | 914 ms/s | 85.9 ms/s |

窗口 1 限帧器**一秒都没睡**，说明帧时间始终没低于帧预算 → 主线程在当时是实打实满载。

## 四、被数据排除的假设

| 假设 | 反证 |
|---|---|
| GC | GC 停顿时长合计 366 ms，只占全部停顿时间的 **8.7%**；`Pause - GC` 最大 64 ms，`Pause - Unknown` 最大 564 ms |
| netty / 网络处理 | netty 的 self 时间 44 ms（0.036%）；**03:05–03:12 连续 8 分钟、26+ 次超时，期间客户端停顿 0 次** |
| 网络不通导致卡 | 超时事件在 14 个分钟里出现，其中 **9 个分钟没有任何停顿** → 不是充分条件 |
| 某个 mod 吃 CPU | 全场 mod 合计 4.5%，最大单项 1.22%（Xaero）。没有任何 mod 达到能解释 200 ms+ 冻结的量级 |
| GC/内存不足 | 堆 committed 3.07 GB、物理内存 34.2 GB 用 22.9 GB，未见压力 |
| 磁盘 I/O | 游戏与 `.lunarclient` 都在 C: = `faspeed K5-256G`（SSD）上，不是 HDD |
| 游戏重启/mixin 重新绑定 | `Setting user:` 仅一次；03:04:18 那条 `Running Essential Loader v1.8.0` 的线程名是 `spark-forge-async-worker`，是 spark 异步线程触发的类初始化，**不是重启** |

## 五、仍未被解释的部分（不要编故事）

- **564 ms 那一次无法定位到代码行。** spark 只给"每个时间窗的总量"，没有单帧时间戳，4 ms 采样不足以指认某一次冻结的具体方法。
- **GL 1282 的成因未定位。** 原版 1.8.9 不做这种 GL 自检，是 Lunar 的检查点（`@ Pre render` 阶段）报出来的。可能是 Lunar 自身 post-effect 通路、也可能是某个 mod 的 GL 状态用法。建议到 Lunar 反馈时直接贴这 5 条。
- **spark 存在盲区。** 本场只采了 Client thread。后台线程（Xaero 世界地图写盘、ReplayMod、Essential 网络、火绒/远程工具）的 CPU 占用**完全看不见**。要覆盖必须 `/sparkc profiler start --thread *`。
- 停顿分布：`Pause - Unknown` 共 29 次 / 3844 ms，分档为 `<100ms` 15 次、`100–199ms` 10 次、`200–499ms` 3 次、`≥500ms` 1 次。**<100 ms 的部分玩家通常感知不到**，真正要处理的是 4 次 200 ms+ 的。

## 六、机器与本机配置（供交叉验证）

| 项 | 值 |
|---|---|
| CPU / 内存 | i7-4790（4C8T, 2014）/ 32 GB，已用 22.9 GB |
| 显卡 / 驱动 | GTX 960，驱动 32.0.15.7270（2025-03），1920×1080 @ 74 Hz |
| 另有显示适配器 | Intel HD Graphics 4600（2016 驱动）、**OrayIddDriver**、**ToDesk Virtual Display Adapter** |
| 系统盘 | C: = faspeed K5-256G（SSD）；另有 4 块机械盘 |
| 游戏设置 | renderDistance **16**、maxFps **260**、vsync off、useVbo on、ao 2、mipmap 4、entityShadows on、fancyGraphics off、`particles:0`(all) |
| OptiFine 设置 | **ofSmoothFps true**、ofSmoothWorld true、ofFastRender true、ofLazyChunkLoading true、ofDynamicLights 3、ofChunkUpdates 1(+dynamic)、ofClouds 3、ofTrees 2、ofRain 3、ofAaLevel 0 |
| 光影 | `profiles\1.8\shaders` 目录为空 → 未装光影包 |
| 资源包 | 2 个（16x PvP 包 + 一个自定义包） |
| 已装 mod（`mods\forge-1.8.9\`） | CrashAssistant 1.11.12、HitRangeMod 1.2、SimpleToggleSprint 2.3.0、Xaero's Minimap 21.10.41、JEI 2.28.18.187、spark；RawInput 为 `.disabled` |
| ichor 注入 | ReplayMod 2.6.24、Essential 1.8.0；OptiFine 由 Lunar 内置 |
| 常驻软件（采集时） | 向日葵 AweSun、ToDesk、火绒 HipsTray、**Steam++**、HyperX NGenuity2Helper（累计 CPU 2523 s）、2× 网易云、2× Spotify、2× msedge、4× WorkBuddy、QQ、OneCommander |

## 七、行动清单（按预期收益排序）

### A. 改完必须重启游戏才生效

1. **渲染距离 16 → 8（或 10）**。GL 相关调用占主线程 53.4%，绘制量减半是最直接的减法。收益预期最大。
2. **maxFps 260 → 120 或 144**（或开 vsync，显示器 75 Hz）。现在等于不锁帧，主线程 91–98% 满载、没有余量。限帧是把余量还给突发负载。
3. **OptiFine Smooth FPS 关掉做 A/B（`ofSmoothFps:false`）**。它每帧一次 `glFinish`，实测 6.75%（8224 ms），是"GPU 一抖 → CPU 全冻"的直接传导路径。
   风险提示（必须量化验证）：OptiFine 加这个选项本来就是为了压驱动三级缓冲造成的尖峰，关掉**可能反而更抖**。所以这条是"做实验"，不是"照做"。
4. 顺带确认 Lunar 自己的 FPS/垂直同步设置没把上面的改动覆盖掉（Lunar 会接管部分原版设置）。

### B. 环境层（多数不需要重启游戏）

5. **火绒（HipsTray）给游戏目录加白名单**：`C:\Users\Huang\.lunarclient`、`C:\Users\Huang\AppData\Local\Programs\Lunar Client`、实例目录、ReplayMod 录制目录。杀软实时扫描是持续写日志/写区块类游戏的典型抖动源。
6. **停掉 Steam++**。它是本地代理/劫持工具，最可能解释那串 `io.netty.handler.timeout.ReadTimeoutException`（每次都是新的 `Netty Client IO #N` 线程 = 反复新建连接后超时）。停掉后再看日志里还有没有时间戳。
   注意：netty 超时**不是**卡顿原因（8 分钟连续超时 0 停顿），但它是真的坏了，值得单独修。
7. **不远程时退出向日葵 AweSun / ToDesk**。这两个虚拟显示驱动常驻，会周期性抓屏/参与合成，是 GPU 同步与帧抖动的常见来源。
8. 游戏时关掉网易云/Spotify/msedge/多余的 WorkBuddy；HyperX NGenuity2Helper 累计 CPU 2523 s，能退就退。
9. Xaero's Minimap 是唯一有实测开销的第三方 mod（1.22%，且在 Client thread 上做 FBO 渲染 + 地图写盘）。1v1 场景可以降刷新频率或关世界地图。JEI 在 PvP 里没用（CPU 仅 0.02%），可留可删。

### C. 先测不要改

10. 补一次**观战对照**：同样按键动鼠标、但只观战不参战。若观战也卡 → 与命中逻辑无关，指向渲染/环境；若观战不卡 → 与本地独有命中逻辑绑定。

## 八、验证方法（必须量化）

改动前后各跑一次同样条件的会话（进服 → 打一局 1v1 → `/sparkc profiler stop`），比较：

```
Pause - Unknown 次数 / 累计 ms / 分档分布
Pause - GC 次数 / 累计 ms
GL ERROR 次数
```

判定口径：只看 **≥200 ms** 的次数与累计时长；`<100 ms` 的部分是正常掉帧噪声。
参考基线（本次）：Unknown 29 次 / 3844 ms / ≥200ms 共 4 次；GC 10 次 / 366 ms；GL ERROR 5 次。

## 九、方法附录：spark 链接怎么变成数据

```
curl -sL -o spark_raw.json https://bytebin.lucko.me/9EcAyxjHvj
```

返回的不是 JSON 也不是 gzip，是 spark 自定义 protobuf。字段定义：
`lucko/spark` → `spark-common/src/main/proto/spark/spark_sampler.proto`。

要点：调用树是**扁平数组 + 下标引用**（`StackTraceNode.children_refs` 存下标）；
`times` 是**每个时间窗的毫秒值**；`self = times − Σchildren.times`；
自校验 = Σself 应约等于采样时长（本次 121832 ms vs 122.1 s）。
