<div align="center">
  <img src="https://raw.githubusercontent.com/Trusler258/FrameSpike/main/logo.png" width="140" alt="FrameSpike 帧刺"/>

  # FrameSpike 帧刺

  **Minecraft 1.8.9 帧时间尖峰与卡顿分析 mod**

  当主线程冻住的那一刻，自动抓取调用栈与帧内时间线，结算真实停顿时长并归因——让你知道每一次卡顿到底卡在哪、为什么卡

  ![Forge](https://img.shields.io/badge/Forge-1.8.9_–_1.12.2-1e6bb8?style=flat-square&logo=data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAACAAAAAgCAMAAABEpIrGAAAABGdBTUEAALGPC%2FxhBQAAACBjSFJNAAB6JgAAgIQAAPoAAACA6AAAdTAAAOpgAAA6mAAAF3CculE8AAABvFBMVEUdLUEcLEEcLEAbK0AaKj4lNUguPVAuPU8vPVAnNkkoN0o5R1g3RVc0QlQ5R1lPXGuCi5ba3ODY2t7W2d3U19vS1trR1NjQ09jQ09fP09fP0tfS1dnT1tqBipUbKz8bLEA4Rlivtbzk5uji5Ofi5OadpK3d4OOxt77%2F%2F%2F%2F8%2FPz09fbq7O7h4%2BbX2t3N0dXDyM27wMa0ucChqLB9hpJkb3xDUGE7SVqyt775%2Bfq%2Fw8nw8fLKzdL%2B%2Fv7v8PKqsLhaZnQpOUsZKj6HkJrs7u%2F4%2Bfno6evX2d1hbHohMUVXY3LFyc77%2FPz5%2Bvr9%2Ff5HVGQaKj8tO052gIy9wcfa3eDo6uz19vb29%2FhmcX4ZKT4jMkYwP1E9S1xZZXTO0dYqOUwXJzxoc4C5vsQgMERqdIIxQFIZKT01Q1VncX%2Bpr7dQXWz7%2B%2Fzb3uHz9PXb3eD3%2BPjV2NtVYXA6SFrV2Nz6%2Bvqfpq%2B8wcfP0taTm6Tx8vM2RVZYZHP3%2BPnn6Ot8hZGwtr2Kkp1yfIlweoeEjZivtLuAiZT19vdTX25HVGW6vsXBxcqrsblFUmMkM0chMESco6zBxsu4vcNEUWIeLkIiMkV8HzjGAAAAAWJLR0QovbC1sgAAAAd0SU1FB%2BAJFRIdHqqGUp8AAAE8SURBVDjLY2AYBRiAkQkNgEWZmCGAiZmFlY2dnZ0NDjg4wQq4uHnAgJePX0BQSFhEVExcQlJSSkJaRlhWDigvr6CopKyioqIsoqqmrqGhqaWto6unb2BoZGxiYgq2g8nMXFMDCCwsraw1IMBGw9bO3oHFUQ7iNDkuJ2eQqJqLqw1UhZu7BwsTwvFMDJ5e3hoaPja%2BUHlhP0c0%2F%2FkHBAYFK4WEQuTDwiMwQoDJPzJKITpGD6wgNo6JAQtgYpaLT%2FAGKUhMwhGQ8vHJYAW6KalYjWBISxfRAPlDMyMzBYu0nL2gT1Z2DjBAcvO08gvk0OWZC4s0iktKQaFUVl6hIV4pj6aApapao6a2rr6hsam5RVqjtY0F3Yb2js6u7h6WiAjH3sK%2B%2FgkTMeyYNHmyBxMjxMe9kydPompKG%2FQAACylQX%2B0Y9EnAAAAJXRFWHRkYXRlOmNyZWF0ZQAyMDE2LTA5LTIxVDE4OjI5OjMwKzAyOjAw62z2vAAAACV0RVh0ZGF0ZTptb2RpZnkAMjAxNi0wOS0yMVQxODoyOTozMCswMjowMJoxTgAAAAAZdEVYdFNvZnR3YXJlAEFkb2JlIEltYWdlUmVhZHlxyWU8AAAAV3pUWHRSYXcgcHJvZmlsZSB0eXBlIGlwdGMAAHic4%2FIMCHFWKCjKT8vMSeVSAAMjCy5jCxMjE0uTFAMTIESANMNkAyOzVCDL2NTIxMzEHMQHy4BIoEouAOoXEXTyQjWVAAAAAElFTkSuQmCC&logoColor=white)
  ![Fabric](https://img.shields.io/badge/Fabric-1.20.1_–_1.20.6-8a7f4d?style=flat-square&logo=data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAEAAAABACAMAAACdt4HsAAABN2lDQ1BBZG9iZSBSR0IgKDE5OTgpAAAokZWPv0rDUBSHvxtFxaFWCOLgcCdRUGzVwYxJW4ogWKtDkq1JQ5ViEm6uf%2FoQjm4dXNx9AidHwUHxCXwDxamDQ4QMBYvf9J3fORzOAaNi152GUYbzWKt205Gu58vZF2aYAoBOmKV2q3UAECdxxBjf7wiA10277jTG%2B38yH6ZKAyNguxtlIYgK0L%2FSqQYxBMygn2oQD4CpTto1EE9AqZf7G1AKcv8ASsr1fBBfgNlzPR%2BMOcAMcl8BTB1da4Bakg7UWe9Uy6plWdLuJkEkjweZjs4zuR%2BHiUoT1dFRF8jvA2AxH2w3HblWtay99X%2F%2BPRHX82Vun0cIQCw9F1lBeKEuf1UYO5PrYsdwGQ7vYXpUZLs3cLcBC7dFtlqF8hY8Dn8AwMZP%2FfNTP8gAAAAbUExURQAAADg0KoB6bZqSfq6mlLyynMa8pdvQtOjo6H4FWGcAAAAJdFJOUwD%2F%2F%2F%2F%2F%2F%2F%2F%2F%2FzcCm9QAAAAJcEhZcwAALiMAAC4jAXilP3YAAAXiaVRYdFhNTDpjb20uYWRvYmUueG1wAAAAAAA8P3hwYWNrZXQgYmVnaW49Iu%2B7vyIgaWQ9Ilc1TTBNcENlaGlIenJlU3pOVGN6a2M5ZCI%2FPiA8eDp4bXBtZXRhIHhtbG5zOng9ImFkb2JlOm5zOm1ldGEvIiB4OnhtcHRrPSJBZG9iZSBYTVAgQ29yZSA2LjAtYzAwMiA3OS4xNjQzNTIsIDIwMjAvMDEvMzAtMTU6NTA6MzggICAgICAgICI%2BIDxyZGY6UkRGIHhtbG5zOnJkZj0iaHR0cDovL3d3dy53My5vcmcvMTk5OS8wMi8yMi1yZGYtc3ludGF4LW5zIyI%2BIDxyZGY6RGVzY3JpcHRpb24gcmRmOmFib3V0PSIiIHhtbG5zOnhtcD0iaHR0cDovL25zLmFkb2JlLmNvbS94YXAvMS4wLyIgeG1sbnM6ZGM9Imh0dHA6Ly9wdXJsLm9yZy9kYy9lbGVtZW50cy8xLjEvIiB4bWxuczpwaG90b3Nob3A9Imh0dHA6Ly9ucy5hZG9iZS5jb20vcGhvdG9zaG9wLzEuMC8iIHhtbG5zOnhtcE1NPSJodHRwOi8vbnMuYWRvYmUuY29tL3hhcC8xLjAvbW0vIiB4bWxuczpzdEV2dD0iaHR0cDovL25zLmFkb2JlLmNvbS94YXAvMS4wL3NUeXBlL1Jlc291cmNlRXZlbnQjIiB4bXA6Q3JlYXRvclRvb2w9IkFkb2JlIFBob3Rvc2hvcCAyMS4xIChXaW5kb3dzKSIgeG1wOkNyZWF0ZURhdGU9IjIwMjAtMDYtMDFUMTc6MDY6MzkrMDM6MDAiIHhtcDpNb2RpZnlEYXRlPSIyMDIwLTA2LTAxVDE3OjE4OjQ1KzAzOjAwIiB4bXA6TWV0YWRhdGFEYXRlPSIyMDIwLTA2LTAxVDE3OjE4OjQ1KzAzOjAwIiBkYzpmb3JtYXQ9ImltYWdlL3BuZyIgcGhvdG9zaG9wOkNvbG9yTW9kZT0iMiIgcGhvdG9zaG9wOklDQ1Byb2ZpbGU9IkFkb2JlIFJHQiAoMTk5OCkiIHhtcE1NOkluc3RhbmNlSUQ9InhtcC5paWQ6ZmU5ZjBmZjctMjVlZS03MzRlLWJlMzQtY2EzYjI0MWM5OWQzIiB4bXBNTTpEb2N1bWVudElEPSJ4bXAuZGlkOmUyYmU0ZDAzLTYyYmItYTc0OC1hZjYzLWYwMDQxZDUwNmZkYiIgeG1wTU06T3JpZ2luYWxEb2N1bWVudElEPSJ4bXAuZGlkOmUyYmU0ZDAzLTYyYmItYTc0OC1hZjYzLWYwMDQxZDUwNmZkYiI%2BIDx4bXBNTTpIaXN0b3J5PiA8cmRmOlNlcT4gPHJkZjpsaSBzdEV2dDphY3Rpb249ImNyZWF0ZWQiIHN0RXZ0Omluc3RhbmNlSUQ9InhtcC5paWQ6ZTJiZTRkMDMtNjJiYi1hNzQ4LWFmNjMtZjAwNDFkNTA2ZmRiIiBzdEV2dDp3aGVuPSIyMDIwLTA2LTAxVDE3OjA2OjM5KzAzOjAwIiBzdEV2dDpzb2Z0d2FyZUFnZW50PSJBZG9iZSBQaG90b3Nob3AgMjEuMSAoV2luZG93cykiLz4gPHJkZjpsaSBzdEV2dDphY3Rpb249InNhdmVkIiBzdEV2dDppbnN0YW5jZUlEPSJ4bXAuaWlkOmZlOWYwZmY3LTI1ZWUtNzM0ZS1iZTM0LWNhM2IyNDFjOTlkMyIgc3RFdnQ6d2hlbj0iMjAyMC0wNi0wMVQxNzoxODo0NSswMzowMCIgc3RFdnQ6c29mdHdhcmVBZ2VudD0iQWRvYmUgUGhvdG9zaG9wIDIxLjEgKFdpbmRvd3MpIiBzdEV2dDpjaGFuZ2VkPSIvIi8%2BIDwvcmRmOlNlcT4gPC94bXBNTTpIaXN0b3J5PiA8L3JkZjpEZXNjcmlwdGlvbj4gPC9yZGY6UkRGPiA8L3g6eG1wbWV0YT4gPD94cGFja2V0IGVuZD0iciI%2FPh7Xp9cAAADhSURBVFiF7ZbNDoQgDIS%2Forvv%2F7yEyB7kdwXZA5smxF7AFsd2MsUKQxMA34uaMcC96QPIOPwGsNAmQr8EfYAfSHylc5Yrkfol6AP0SRQ4VWgB9vi1b0nql%2FAfDgRK%2FUBBhIlPflIGCwBcSEyXGHAylY9YgqJcjuuXMJWD1D61%2BWrn4kl%2FevRL0AfYw1rpx0dPCnFUb3lW6sY91LiVznoqylpzPL%2B2lkkgydBiM%2BvHwTNk9UyK1cRNJvLghr85GegDXK71pCiBdv9NzmABgOZ8YKBoxOVJ7A%2BawKj%2BKRksAPABKYwmfSXpKIIAAAAASUVORK5CYII%3D&logoColor=white)
  ![NeoForge](https://img.shields.io/badge/NeoForge-1.20.1-c96e2b?style=flat-square&logo=data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAIAAAACACAYAAADDPmHLAAAACXBIWXMAAAsSAAALEgHS3X78AAADgklEQVR42u2dPWhTYRSGk2qlZKia%2BBMNFuJQERehuFiogoNSJ7tIi4MKFkELInapVYOIS8SlCqKoiwji5OhStLQOlUz%2BYUFrC2li7I26hGCwEbXrOTec5OKN93nWw%2F363S8PJ7w9yU0oBAAAASWsFdPXb1Q5ouZn%2BOxp8XVu4XiCDQIgACAAIAAgAASQlVrU6%2B07aFp0bTTqq5v8WiwG%2FXWu0gEAAQABAAEAAQABYDkGWuPc5PiEWIttXCfWIpGIebP3L4%2Barjt28YpYK5VKYu3Rtaumv3f43IgnL5bzedF05nQAQABAAEAAQABAAKgxBr5781asbUl2mKKVVnOLXts6EmLt%2FXzWdADWWKrtRbuHeiKiNerRAQABAAEAAQABAAGgxhgY3xQXa%2Flc3hSt3CZ6WrwaOHlKrF0akePVhwuDttNp3WDay8NbN00RsZ6YqE0K6QCAAIAAgACAAIAAgABQy%2F8BtKyvoY18eyoF9dqJebn2aXrBtG4i9tN0Hz1OwbQXbTTtdv9WtFHx3Mc5OgAgACAAIAAgACAA1BID%2FwX97TmxVh4%2FL9a6OitibXW8zbSXeL4sR2RlL%2F3t8ppZZ4Un58Y4GBAAEAAQABAAEAB8GgPdJnPxWKUpDs6rfS7RAQABAAEAAQABAAGg%2BWJgy%2B0hc3zampoSa8XsrFj7dueIaa%2FflYmfxpoTD8RaNJGUL0x1q%2BvmlbNbGhyjAwACAAIAAgACAAJAvTGwkFts%2BKJaXNFizp8YqNT2HDoq1p70Nj7qhYx7eTX9XL5%2Fp1Vdt%2B3MXdN%2BHKWmvcZ0AN4CAAEAAQABAAEgcDHQeqH2XbQf9%2BQfatS%2Bw%2FcbbeLnfMkpV643Ry8JbXKp7UW7B7dpaEZ5rO2q46N0AEAAQABAAEAAQACok%2FDwcKoqFQ%2F07TfFQA0tIv6NieX%2F%2BsAzM%2FrjaryIei%2BnMnQAQABAAEAAQABAAFjGd08KtU7udo89E2ubk52mNRdmZ8Tai6G9dABAAEAAQABAAEAAQADg%2FwAuuI07sy7jYguvJ582fE3rL394Me6lAwACAAIAAgACAAJAg2Kg9slf7ZOmu7q7PIuJEo8H9vkqznqBF2dOB%2BAtABAAEAAQABAAiIE1smPndl%2FdiN%2BmbF7gxZnTAXgLAAQABAAEAASAoBHWitrzg6B5SKdTYToAIAAgACAAIAAgAAAAAECQ%2BQUUBtshXi5agwAAAABJRU5ErkJggg%3D%3D&logoColor=white)
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

  ![Forge](https://img.shields.io/badge/Forge-1.8.9_–_1.12.2-1e6bb8?style=flat-square&logo=data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAACAAAAAgCAMAAABEpIrGAAAABGdBTUEAALGPC%2FxhBQAAACBjSFJNAAB6JgAAgIQAAPoAAACA6AAAdTAAAOpgAAA6mAAAF3CculE8AAABvFBMVEUdLUEcLEEcLEAbK0AaKj4lNUguPVAuPU8vPVAnNkkoN0o5R1g3RVc0QlQ5R1lPXGuCi5ba3ODY2t7W2d3U19vS1trR1NjQ09jQ09fP09fP0tfS1dnT1tqBipUbKz8bLEA4Rlivtbzk5uji5Ofi5OadpK3d4OOxt77%2F%2F%2F%2F8%2FPz09fbq7O7h4%2BbX2t3N0dXDyM27wMa0ucChqLB9hpJkb3xDUGE7SVqyt775%2Bfq%2Fw8nw8fLKzdL%2B%2Fv7v8PKqsLhaZnQpOUsZKj6HkJrs7u%2F4%2Bfno6evX2d1hbHohMUVXY3LFyc77%2FPz5%2Bvr9%2Ff5HVGQaKj8tO052gIy9wcfa3eDo6uz19vb29%2FhmcX4ZKT4jMkYwP1E9S1xZZXTO0dYqOUwXJzxoc4C5vsQgMERqdIIxQFIZKT01Q1VncX%2Bpr7dQXWz7%2B%2Fzb3uHz9PXb3eD3%2BPjV2NtVYXA6SFrV2Nz6%2Bvqfpq%2B8wcfP0taTm6Tx8vM2RVZYZHP3%2BPnn6Ot8hZGwtr2Kkp1yfIlweoeEjZivtLuAiZT19vdTX25HVGW6vsXBxcqrsblFUmMkM0chMESco6zBxsu4vcNEUWIeLkIiMkV8HzjGAAAAAWJLR0QovbC1sgAAAAd0SU1FB%2BAJFRIdHqqGUp8AAAE8SURBVDjLY2AYBRiAkQkNgEWZmCGAiZmFlY2dnZ0NDjg4wQq4uHnAgJePX0BQSFhEVExcQlJSSkJaRlhWDigvr6CopKyioqIsoqqmrqGhqaWto6unb2BoZGxiYgq2g8nMXFMDCCwsraw1IMBGw9bO3oHFUQ7iNDkuJ2eQqJqLqw1UhZu7BwsTwvFMDJ5e3hoaPja%2BUHlhP0c0%2F%2FkHBAYFK4WEQuTDwiMwQoDJPzJKITpGD6wgNo6JAQtgYpaLT%2FAGKUhMwhGQ8vHJYAW6KalYjWBISxfRAPlDMyMzBYu0nL2gT1Z2DjBAcvO08gvk0OWZC4s0iktKQaFUVl6hIV4pj6aApapao6a2rr6hsam5RVqjtY0F3Yb2js6u7h6WiAjH3sK%2B%2FgkTMeyYNHmyBxMjxMe9kydPompKG%2FQAACylQX%2B0Y9EnAAAAJXRFWHRkYXRlOmNyZWF0ZQAyMDE2LTA5LTIxVDE4OjI5OjMwKzAyOjAw62z2vAAAACV0RVh0ZGF0ZTptb2RpZnkAMjAxNi0wOS0yMVQxODoyOTozMCswMjowMJoxTgAAAAAZdEVYdFNvZnR3YXJlAEFkb2JlIEltYWdlUmVhZHlxyWU8AAAAV3pUWHRSYXcgcHJvZmlsZSB0eXBlIGlwdGMAAHic4%2FIMCHFWKCjKT8vMSeVSAAMjCy5jCxMjE0uTFAMTIESANMNkAyOzVCDL2NTIxMzEHMQHy4BIoEouAOoXEXTyQjWVAAAAAElFTkSuQmCC&logoColor=white)
  ![Fabric](https://img.shields.io/badge/Fabric-1.20.1_–_1.20.6-8a7f4d?style=flat-square&logo=data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAEAAAABACAMAAACdt4HsAAABN2lDQ1BBZG9iZSBSR0IgKDE5OTgpAAAokZWPv0rDUBSHvxtFxaFWCOLgcCdRUGzVwYxJW4ogWKtDkq1JQ5ViEm6uf%2FoQjm4dXNx9AidHwUHxCXwDxamDQ4QMBYvf9J3fORzOAaNi152GUYbzWKt205Gu58vZF2aYAoBOmKV2q3UAECdxxBjf7wiA10277jTG%2B38yH6ZKAyNguxtlIYgK0L%2FSqQYxBMygn2oQD4CpTto1EE9AqZf7G1AKcv8ASsr1fBBfgNlzPR%2BMOcAMcl8BTB1da4Bakg7UWe9Uy6plWdLuJkEkjweZjs4zuR%2BHiUoT1dFRF8jvA2AxH2w3HblWtay99X%2F%2BPRHX82Vun0cIQCw9F1lBeKEuf1UYO5PrYsdwGQ7vYXpUZLs3cLcBC7dFtlqF8hY8Dn8AwMZP%2FfNTP8gAAAAbUExURQAAADg0KoB6bZqSfq6mlLyynMa8pdvQtOjo6H4FWGcAAAAJdFJOUwD%2F%2F%2F%2F%2F%2F%2F%2F%2F%2FzcCm9QAAAAJcEhZcwAALiMAAC4jAXilP3YAAAXiaVRYdFhNTDpjb20uYWRvYmUueG1wAAAAAAA8P3hwYWNrZXQgYmVnaW49Iu%2B7vyIgaWQ9Ilc1TTBNcENlaGlIenJlU3pOVGN6a2M5ZCI%2FPiA8eDp4bXBtZXRhIHhtbG5zOng9ImFkb2JlOm5zOm1ldGEvIiB4OnhtcHRrPSJBZG9iZSBYTVAgQ29yZSA2LjAtYzAwMiA3OS4xNjQzNTIsIDIwMjAvMDEvMzAtMTU6NTA6MzggICAgICAgICI%2BIDxyZGY6UkRGIHhtbG5zOnJkZj0iaHR0cDovL3d3dy53My5vcmcvMTk5OS8wMi8yMi1yZGYtc3ludGF4LW5zIyI%2BIDxyZGY6RGVzY3JpcHRpb24gcmRmOmFib3V0PSIiIHhtbG5zOnhtcD0iaHR0cDovL25zLmFkb2JlLmNvbS94YXAvMS4wLyIgeG1sbnM6ZGM9Imh0dHA6Ly9wdXJsLm9yZy9kYy9lbGVtZW50cy8xLjEvIiB4bWxuczpwaG90b3Nob3A9Imh0dHA6Ly9ucy5hZG9iZS5jb20vcGhvdG9zaG9wLzEuMC8iIHhtbG5zOnhtcE1NPSJodHRwOi8vbnMuYWRvYmUuY29tL3hhcC8xLjAvbW0vIiB4bWxuczpzdEV2dD0iaHR0cDovL25zLmFkb2JlLmNvbS94YXAvMS4wL3NUeXBlL1Jlc291cmNlRXZlbnQjIiB4bXA6Q3JlYXRvclRvb2w9IkFkb2JlIFBob3Rvc2hvcCAyMS4xIChXaW5kb3dzKSIgeG1wOkNyZWF0ZURhdGU9IjIwMjAtMDYtMDFUMTc6MDY6MzkrMDM6MDAiIHhtcDpNb2RpZnlEYXRlPSIyMDIwLTA2LTAxVDE3OjE4OjQ1KzAzOjAwIiB4bXA6TWV0YWRhdGFEYXRlPSIyMDIwLTA2LTAxVDE3OjE4OjQ1KzAzOjAwIiBkYzpmb3JtYXQ9ImltYWdlL3BuZyIgcGhvdG9zaG9wOkNvbG9yTW9kZT0iMiIgcGhvdG9zaG9wOklDQ1Byb2ZpbGU9IkFkb2JlIFJHQiAoMTk5OCkiIHhtcE1NOkluc3RhbmNlSUQ9InhtcC5paWQ6ZmU5ZjBmZjctMjVlZS03MzRlLWJlMzQtY2EzYjI0MWM5OWQzIiB4bXBNTTpEb2N1bWVudElEPSJ4bXAuZGlkOmUyYmU0ZDAzLTYyYmItYTc0OC1hZjYzLWYwMDQxZDUwNmZkYiIgeG1wTU06T3JpZ2luYWxEb2N1bWVudElEPSJ4bXAuZGlkOmUyYmU0ZDAzLTYyYmItYTc0OC1hZjYzLWYwMDQxZDUwNmZkYiI%2BIDx4bXBNTTpIaXN0b3J5PiA8cmRmOlNlcT4gPHJkZjpsaSBzdEV2dDphY3Rpb249ImNyZWF0ZWQiIHN0RXZ0Omluc3RhbmNlSUQ9InhtcC5paWQ6ZTJiZTRkMDMtNjJiYi1hNzQ4LWFmNjMtZjAwNDFkNTA2ZmRiIiBzdEV2dDp3aGVuPSIyMDIwLTA2LTAxVDE3OjA2OjM5KzAzOjAwIiBzdEV2dDpzb2Z0d2FyZUFnZW50PSJBZG9iZSBQaG90b3Nob3AgMjEuMSAoV2luZG93cykiLz4gPHJkZjpsaSBzdEV2dDphY3Rpb249InNhdmVkIiBzdEV2dDppbnN0YW5jZUlEPSJ4bXAuaWlkOmZlOWYwZmY3LTI1ZWUtNzM0ZS1iZTM0LWNhM2IyNDFjOTlkMyIgc3RFdnQ6d2hlbj0iMjAyMC0wNi0wMVQxNzoxODo0NSswMzowMCIgc3RFdnQ6c29mdHdhcmVBZ2VudD0iQWRvYmUgUGhvdG9zaG9wIDIxLjEgKFdpbmRvd3MpIiBzdEV2dDpjaGFuZ2VkPSIvIi8%2BIDwvcmRmOlNlcT4gPC94bXBNTTpIaXN0b3J5PiA8L3JkZjpEZXNjcmlwdGlvbj4gPC9yZGY6UkRGPiA8L3g6eG1wbWV0YT4gPD94cGFja2V0IGVuZD0iciI%2FPh7Xp9cAAADhSURBVFiF7ZbNDoQgDIS%2Forvv%2F7yEyB7kdwXZA5smxF7AFsd2MsUKQxMA34uaMcC96QPIOPwGsNAmQr8EfYAfSHylc5Yrkfol6AP0SRQ4VWgB9vi1b0nql%2FAfDgRK%2FUBBhIlPflIGCwBcSEyXGHAylY9YgqJcjuuXMJWD1D61%2BWrn4kl%2FevRL0AfYw1rpx0dPCnFUb3lW6sY91LiVznoqylpzPL%2B2lkkgydBiM%2BvHwTNk9UyK1cRNJvLghr85GegDXK71pCiBdv9NzmABgOZ8YKBoxOVJ7A%2BawKj%2BKRksAPABKYwmfSXpKIIAAAAASUVORK5CYII%3D&logoColor=white)
  ![NeoForge](https://img.shields.io/badge/NeoForge-1.20.1-c96e2b?style=flat-square&logo=data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAIAAAACACAYAAADDPmHLAAAACXBIWXMAAAsSAAALEgHS3X78AAADgklEQVR42u2dPWhTYRSGk2qlZKia%2BBMNFuJQERehuFiogoNSJ7tIi4MKFkELInapVYOIS8SlCqKoiwji5OhStLQOlUz%2BYUFrC2li7I26hGCwEbXrOTec5OKN93nWw%2F363S8PJ7w9yU0oBAAAASWsFdPXb1Q5ouZn%2BOxp8XVu4XiCDQIgACAAIAAgAASQlVrU6%2B07aFp0bTTqq5v8WiwG%2FXWu0gEAAQABAAEAAQABYDkGWuPc5PiEWIttXCfWIpGIebP3L4%2Barjt28YpYK5VKYu3Rtaumv3f43IgnL5bzedF05nQAQABAAEAAQABAAKgxBr5781asbUl2mKKVVnOLXts6EmLt%2FXzWdADWWKrtRbuHeiKiNerRAQABAAEAAQABAAGgxhgY3xQXa%2Flc3hSt3CZ6WrwaOHlKrF0akePVhwuDttNp3WDay8NbN00RsZ6YqE0K6QCAAIAAgACAAIAAgABQy%2F8BtKyvoY18eyoF9dqJebn2aXrBtG4i9tN0Hz1OwbQXbTTtdv9WtFHx3Mc5OgAgACAAIAAgACAA1BID%2FwX97TmxVh4%2FL9a6OitibXW8zbSXeL4sR2RlL%2F3t8ppZZ4Un58Y4GBAAEAAQABAAEAB8GgPdJnPxWKUpDs6rfS7RAQABAAEAAQABAAGg%2BWJgy%2B0hc3zampoSa8XsrFj7dueIaa%2FflYmfxpoTD8RaNJGUL0x1q%2BvmlbNbGhyjAwACAAIAAgACAAJAvTGwkFts%2BKJaXNFizp8YqNT2HDoq1p70Nj7qhYx7eTX9XL5%2Fp1Vdt%2B3MXdN%2BHKWmvcZ0AN4CAAEAAQABAAEgcDHQeqH2XbQf9%2BQfatS%2Bw%2FcbbeLnfMkpV643Ry8JbXKp7UW7B7dpaEZ5rO2q46N0AEAAQABAAEAAQACok%2FDwcKoqFQ%2F07TfFQA0tIv6NieX%2F%2BsAzM%2FrjaryIei%2BnMnQAQABAAEAAQABAAFjGd08KtU7udo89E2ubk52mNRdmZ8Tai6G9dABAAEAAQABAAEAAQADg%2FwAuuI07sy7jYguvJ582fE3rL394Me6lAwACAAIAAgACAAJAg2Kg9slf7ZOmu7q7PIuJEo8H9vkqznqBF2dOB%2BAtABAAEAAQABAAiIE1smPndl%2FdiN%2BmbF7gxZnTAXgLAAQABAAEAASAoBHWitrzg6B5SKdTYToAIAAgACAAIAAgAAAAAECQ%2BQUUBtshXi5agwAAAABJRU5ErkJggg%3D%3D&logoColor=white)
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
