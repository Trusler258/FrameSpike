/*
 * 网页报告自检 —— 保证 resources/framespike/report.html 里那份自带 JS 解析器
 * 和 Java 侧 Report.buildJson 的口径一模一样（同一条日志，两边算出来的数必须一致）。
 *
 *   node test/parse_check.js <report.html> [日志] [Java导出的json]
 *   node test/parse_check.js <report.html> --emit <写样例日志到哪>
 *
 * 不传日志就用脚本内置的样例日志（覆盖：DETECT/END、SNAPSHOT 旧格式、两种 t+ 排版、
 * 跳过省略行、栈深超 24 被截、[CPU] 采样、RECORDING STARTED/STOPPED、>400 条触发截断）。
 */

var fs = require("fs");


/* ------------------------------------------------------------------ 样例日志 */

function sampleLog() {
  var L = [];
  L.push("=== FrameSpike v9.9.9  by Tester ===");
  L.push("03:00:00.000  RECORDING STARTED  threshold=60ms  chat=on(200ms)  log=frame-spikes.log  t0=1759700000000");
  L.push("");

  /* 正常一次：DETECT + timeline（两种排版）+ stack + END（带 gc 标注） */
  L.push("03:00:10.000  STALL-DETECT 61ms  since='hudForge'  frames=100  ticks=20  stall#1  reason=写文件  glFinishSkipped=0  ctx=dim=主世界 x=128 y=64 z=-256  t0=1759700010000");
  L.push("  timeline:");
  L.push("    runTick              t+   0.412ms");
  L.push("    hudForge             t+  33.221ms");
  L.push("    renderWorld          t+1234.567ms");
  L.push("  stack of \"Client thread\":");
  L.push("    java.io.FileOutputStream.writeBytes(Native Method)");
  L.push("    java.io.FileOutputStream.write(FileOutputStream.java:326)");
  L.push("    com.moonsworth.lunar.client.AAA.BBB(Unknown Source)");
  L.push("  STALL-END    total=372ms  detected@61ms  since='hudForge'  next='runGameLoop'  reason=写文件  gc=G1 Old Generation 212ms  t1=1759700010372");
  L.push("");

  /* 栈很深的：验证两边都在 24 层截断 */
  L.push("03:00:20.000  STALL-DETECT 62ms  since='runTick'  frames=200  ticks=40  stall#2  reason=类加载/remap  glFinishSkipped=0");
  L.push("  stack of \"Client thread\":");
  for (var d = 0; d < 30; d++) L.push("    a.b.C" + d + ".m" + d + "(Unknown Source)");
  L.push("    ...(5 层更深)");
  L.push("    <这是不该进栈的行>");
  L.push("  STALL-END    total=1589ms  detected@62ms  since='runTick'  next='runTick'  reason=类加载/remap");
  L.push("");

  /* 旧格式：只有 SNAPSHOT，没有 STALL-END。
     故意给个大值，保证它不会在 MAX_STALLS 截断时被挤掉（要找的是"没结算"这条路径） */
  L.push("03:01:00.000  SNAPSHOT 3000ms  since='runTick'  frames=50  ticks=10  stall#9  reason=窗口合成");
  L.push("  stack of \"Client thread\":");
  L.push("    org.lwjgl.opengl.Display.update(Native Method)");
  L.push("");

  /* 410 条，触发 MAX_STALLS 截断 + 降序排序 */
  for (var i = 1; i <= 410; i++) {
    var ms = 100 + i * 7;
    var reason = (i % 3 === 0) ? "GL驱动" : "类加载/remap";
    L.push("03:0" + (i % 10) + ":00.000  STALL-DETECT 60ms  since='runTick'  frames=" + (i * 3)
      + "  ticks=" + i + "  stall#" + (100 + i) + "  reason=" + reason
      + "  t0=" + (1759700100000 + i * 11));
    L.push("  stack of \"Client thread\":");
    L.push("    org.lwjgl.opengl.GL11.nglDeleteBuffers(ILjava/nio/IntBuffer;J)V");
    L.push("    net.minecraft.client.renderer.texture.TextureManager.av()V");
    L.push("  STALL-END    total=" + ms + "ms  detected@60ms  reason=" + reason + "  t1=" + (1759700100000 + i * 11 + ms));
  }
  L.push("");

  L.push("03:05:00.000  [CPU] 60.1s window, 8 cores, machine busy=8.2%  fps\u2248112.0  tps\u224820.0  frames=4513  ticks=903");
  L.push("03:06:00.000  [CPU] 60.2s window, 8 cores, machine busy=21.5%  fps\u224858.0  tps\u224819.5  frames=2337  ticks=879");
  L.push("03:10:00.000  RECORDING STOPPED  total=902  shown=902  t1=1759700600000");
  L.push("03:10:01.000  === JVM EXIT ===  uptime=1h52m6s  pendingStall=true  recording=true  lastCheckpoint='hudForge'  88ms 前");

  /* 崩溃块：crash-reports 解析出来的 */
  L.push("03:11:00.000  === CRASH ===  上次会话  file=crash-2026-10-06_01.02.30-client.txt");
  L.push("  time=2026-10-06 01:02:30");
  L.push("  desc=java.lang.NullPointerException: Ticking entity");
  L.push("  reason=类加载/remap");
  L.push("--- 崩溃栈前 2 帧 ---");
  L.push("  at net.minecraft.world.World.func_72939_s(World.java:100)");
  L.push("  at net.minecraft.client.Minecraft.func_71407_l(Minecraft.java:2000)");
  return L.join("\n") + "\n";
}

/* ------------------------------------------------------------------ 小工具 */

var pass = 0, fail = 0;
function check(ok, label) {
  if (ok) { pass++; console.log("  PASS  " + label); }
  else { fail++; console.log("  FAIL  " + label); }
}
function near(a, b, eps) {
  return Math.abs((+a || 0) - (+b || 0)) <= (eps === undefined ? 0.05 : eps);
}

/* ------------------------------------------------------------------ 主流程 */

var args = process.argv.slice(2);
var htmlPath = args[0];
if (!htmlPath) {
  console.log("用法: node test/parse_check.js <report.html> [日志] [java.json]");
  process.exit(2);
}
var rest = args.slice(1);
var emitIdx = rest.indexOf("--emit");
if (emitIdx >= 0) {
  var out = rest[emitIdx + 1];
  if (!out) { console.log("--emit 要跟一个输出路径"); process.exit(2); }
  fs.mkdirSync(require("path").dirname(out), { recursive: true });
  fs.writeFileSync(out, sampleLog(), "utf8");
  console.log("样例日志: " + out + "  " + fs.statSync(out).size + " bytes");
  rest = rest.slice(0, emitIdx);
}
var logPath = rest[0];
var jsonPath = rest[1];

var html = fs.readFileSync(htmlPath, "utf8");

/* --- 1. 模板占位符：必须刚好是 Java 侧会替换的那 7 个 --- */
console.log("== 1. 模板占位符 ==");
var known = ["TITLE", "SOURCE", "GENERATED", "VERSION", "MCVER", "AUTHOR", "DATA"];
var found = {};
(html.match(/__[A-Z][A-Z0-9]*__/g) || []).forEach(function (m) {
  found[m.replace(/__/g, "")] = (found[m.replace(/__/g, "")] || 0) + 1;
});
var keys = Object.keys(found).sort();
var bad = keys.filter(function (k) { return known.indexOf(k) < 0; });
check(bad.length === 0, "没有多余的占位符" + (bad.length ? "（多了 " + bad.join(",") + "）" : ""));
var missing = known.filter(function (k) { return !found[k]; });
check(missing.length === 0, "7 个占位符都在" + (missing.length ? "（缺 " + missing.join(",") + "）" : ""));
check(found["DATA"] === 1, "__DATA__ 只出现 1 次（出现 " + found["DATA"] + " 次）");

/* --- 2. 导入入口齐全 --- */
console.log("== 2. 导入入口 / 圆体字 ==");
["blog", "bfont", "breset", "flog", "ffont", "drop", "status"].forEach(function (id) {
  check(html.indexOf('id="' + id + '"') >= 0, "有 #" + id);
});
check(html.indexOf("FontFace") >= 0 && html.indexOf("document.fonts.add") >= 0, "字体导入走 FontFace");
check(html.indexOf("readAsArrayBuffer") >= 0 && html.indexOf("readAsText") >= 0, "字体读 buffer、日志读文本");
check(html.indexOf("dragenter") >= 0 && html.indexOf("dragover") >= 0 && html.indexOf("dragleave") >= 0, "拖放三件套齐全");
check(html.indexOf("--stack-ui") >= 0 && html.indexOf("--font-ui") >= 0, "有圆体字体栈变量");
["MiSans", "HarmonyOS Sans SC", "OPPO Sans", "Nunito", "Quicksand"].forEach(function (f) {
  check(html.indexOf(f) >= 0, "字体栈里有 " + f);
});
check(html.indexOf("far-") < 0 && html.indexOf("ui-monospace") >= 0, "等宽字还是等宽（栈/代码别跟着变圆）");

/* --- 3. 整段脚本语法检查 --- */
console.log("== 3. 脚本语法 ==");
var script = (html.match(/<script>([\s\S]*?)<\/script>/) || [])[1];
check(!!script, "找到内联脚本");
var compiled = false;
try { new Function("document", "window", script.replace("__DATA__", "{}")); compiled = true; } catch (e) { }
check(compiled, "整段脚本能编译（占位符替换成 {} 之后）");

/* --- 4. JS 解析器本身 --- */
console.log("== 4. JS 解析器 ==");
var block = (html.match(/\/\*==PARSE-BEGIN==\*\/([\s\S]*?)\/\*==PARSE-END==\*\//) || [])[1];
check(!!block, "找到 PARSE-BEGIN/END 区块");
var mod = { exports: {} };
new Function("module", "exports", block)(mod, mod.exports);
check(typeof mod.exports.parseLog === "function", "区块导出 parseLog（命令行也能直接用）");
check(mod.exports.FS_MAX_STALLS === 400, "JS 侧 MAX_STALLS = 400（和 Report.java 对齐）");

var logText = logPath ? fs.readFileSync(logPath, "utf8") : sampleLog();
var d = mod.exports.parseLog(logText);
var where = logPath ? require("path").basename(logPath) : "内置样例日志";
console.log("  日志: " + where + "  " + logText.length + " 字符 -> " + d.summary.stalls
  + " 条停顿 / 可测 " + d.summary.measured + " / 累计 " + d.summary.totalMs + "ms / 最长 "
  + d.summary.maxMs + "ms / 分档 " + d.summary.buckets.join(","));
check(d.summary.stalls > 0, "解析出停顿记录");
check(d.summary.measured > 0, "有真实时长（STALL-END 结算到了）");
check(d.summary.measured < d.summary.stalls, "旧格式那几条没被算进统计（可测 " + d.summary.measured + " < " + d.summary.stalls + "）");
check(d.stalls.some(function (s) { return s.est; }), "旧格式记录被标成 est");
check(d.flame && d.flame.kids.length > 0, "火焰树有内容");
check(d.cpu.length > 0 && d.summary.cpuSamples === d.cpu.length, "[CPU] 采样解析出来了（" + d.cpu.length + " 条）");
/* 崩溃/gc 是条件检查：内置样例一定有，真实日志没有也正常 */
check(Array.isArray(d.crashes) && d.summary.crashes === d.crashes.length,
  "崩溃块口径一致（" + d.crashes.length + " 条）");
if (d.crashes.length){
  check(d.crashes[0].desc.length > 0 && d.crashes[0].time.length > 0, "崩溃的 Description/Time 解析正确");
  check(d.crashes[0].reason.length > 0 && d.crashes[0].file.length > 0, "崩溃的归因与文件名在");
}
check(d.stalls.every(function (s) { return s.gc === undefined || typeof s.gc === "string"; }),
  "gc 字段形状一致");
/* 栈不再截断：样例里那条 30 层的栈必须原样在，且任何地方都不该出现截断标记 */
var deep = d.stalls.filter(function (s) { return (s.stack || []).length > 24; }).length;
check(deep >= 1, "超过 24 层的栈原样保留（" + deep + " 条）");
var mk = d.stalls.filter(function (s) {
  return (s.stack || []).some(function (t) { return t.indexOf("层更深") >= 0; });
}).length;
check(mk === 0, "报告里没有「N 层更深」截断标记");
var mxStack = Math.max.apply(null, [0].concat(d.stalls.map(function (s) { return (s.stack || []).length; })));
check(mxStack >= 30, "最深的一条栈完整写出（" + mxStack + " 层）");
/* t0/t1/ctx/会话：样例日志里是我写死的值，真实日志（可能已经在用新版本录）只断言结构合理 */
var isSample = logText.indexOf("v9.9.9") >= 0;
var withT0 = d.stalls.filter(function (s) { return s.t0 > 0; }).length;
if (isSample) {
  check(d.stalls.filter(function (s) { return s.t0 === 1759700010000; }).length === 1,
    "样例停顿的原生 t0/t1 解析正确");
  var s1 = d.stalls.filter(function (s) { return s.t0 === 1759700010000; })[0];
  check(!!s1 && s1.t1 === 1759700010372, "样例 t1 正确（" + (s1 ? s1.t1 : "-") + "）");
  check(!!s1 && String(s1.ctx || "").indexOf("主世界") >= 0, "样例世界上下文解析正确");
  check(withT0 >= 350, "样例里绝大多数停顿带原生 t0（" + withT0 + "/" + d.stalls.length + "）");
  check(d.summary.sessT0 === 1759700000000 && d.summary.sessT1 === 1759700600000,
    "样例会话起止正确（" + d.summary.sessT0 + " / " + d.summary.sessT1 + "）");
  check(d.summary.exitUptime === "1h52m6s" && d.summary.exitPending === true,
    "样例 JVM EXIT 正确（" + d.summary.exitUptime + " / pending=" + d.summary.exitPending + "）");
} else {
  /* 手动 dump（SNAPSHOT）只有 t0 没有 t1，所以 t1 缺失也算合理 */
  check(d.stalls.every(function (s) {
    return !s.t0 || (s.t0 > 1600000000000 && (!s.t1 || s.t1 >= s.t0));
  }), "真实日志里 t0/t1 形状合理（" + withT0 + " 条带原生时间戳）");
  check(!withT0 || d.stalls.every(function (s) { return !s.t0 || !s.ctx || typeof s.ctx === "string"; }),
    "ctx 是字符串或缺失");
  check(!d.summary.sessT0 || d.summary.sessT0 > 1600000000000, "会话起点是合理的 epoch ms");
  check(!d.summary.exitUptime || /^[\d:hm]/.test(d.summary.exitUptime),
    "JVM EXIT 的 uptime 形状合理（" + d.summary.exitUptime + "）");
  if (!withT0) check(d.stalls.every(function (s) { return !s.t0 && !s.t1 && !s.ctx; }),
    "老日志里解析不凭空造值");
}
if (d.stalls.length && d.stalls[0].gc){
  check(d.stalls[0].gc.indexOf("G1 Old Generation") >= 0, "STALL-END 的 gc= 标注解析出来了");
}
/* 渲染层用 x.fps / x.tps / x.cpu 取值，这里要是数组就会静默画成一条直线 —— 钉死形状 */
check(d.cpu.length > 0 && typeof d.cpu[0].fps === "number" && typeof d.cpu[0].tps === "number"
  && typeof d.cpu[0].cpu === "number" && typeof d.cpu[0].t === "string",
  "采样的形状和 Java JSON 一致（{t,cpu,fps,tps} 对象，不是数组）");
check(d.stalls.length > 0 && typeof d.stalls[0].timeline !== "undefined"
  && typeof d.stalls[0].stack !== "undefined" && typeof d.stalls[0].est === "boolean",
  "停顿的形状和 Java JSON 一致（timeline/stack/est 都在）");

if (!jsonPath) {
  console.log();
  console.log("== 结果 ==");
  console.log("PASS " + pass + "  FAIL " + fail);
  console.log(fail === 0 ? "ALL REPORT CHECKS PASSED" : fail + " CHECK(S) FAILED");
  process.exit(fail === 0 ? 0 : 1);
}

/* --- 5. 和 Java 侧逐字段对 --- */
console.log("== 5. 和 Java 侧 Report.buildJson 对口径 ==");
var j = JSON.parse(fs.readFileSync(jsonPath, "utf8"));
var a = j.summary, b = d.summary;
check(a.stalls === b.stalls, "停顿条数 " + a.stalls + " / " + b.stalls);
check(a.measured === b.measured, "可测条数 " + a.measured + " / " + b.measured);
check(a.allStalls === b.allStalls, "日志总条数 " + a.allStalls + " / " + b.allStalls);
check(a.totalMs === b.totalMs, "累计时长 " + a.totalMs + " / " + b.totalMs);
check(a.maxMs === b.maxMs, "最长一次 " + a.maxMs + " / " + b.maxMs);
check(JSON.stringify(a.buckets) === JSON.stringify(b.buckets), "分档 " + a.buckets.join(",") + " / " + b.buckets.join(","));
check(!!a.truncated === !!b.truncated, "截断标记 " + a.truncated + " / " + b.truncated);
check(near(a.fps, b.fps), "峰值 fps " + a.fps + " / " + b.fps);
check(near(a.tps, b.tps), "峰值 tps " + a.tps + " / " + b.tps);
check(a.durText === b.durText, "采集时长 " + a.durText + " / " + b.durText);
check(a.crashes === b.crashes, "崩溃条数 " + a.crashes + " / " + b.crashes);
check(a.sessT0 === b.sessT0 && a.sessT1 === b.sessT1,
  "会话起止 " + a.sessT0 + "/" + b.sessT0 + " · " + a.sessT1 + "/" + b.sessT1);
check(a.exitUptime === b.exitUptime && !!a.exitPending === !!b.exitPending,
  "退出信息 " + a.exitUptime + " / " + b.exitUptime);
var jcr = j.crashes || [], dcr = d.crashes || [], crm = 0;
for (var ci = 0; ci < Math.max(jcr.length, dcr.length); ci++) {
  var cx = jcr[ci] || {}, cy = dcr[ci] || {};
  if ((cx.t || "") !== (cy.t || "") || (cx.file || "") !== (cy.file || "")
      || (cx.time || "") !== (cy.time || "") || (cx.desc || "") !== (cy.desc || "")
      || (cx.reason || "") !== (cy.reason || "")) crm++;
}
check(crm === 0, "崩溃块与 Java 侧逐字段一致（" + jcr.length + " 条）");

check(j.stalls.length === d.stalls.length, "明细条数一致（" + j.stalls.length + "）");
var mism = 0, firstBad = "";
for (var i = 0; i < Math.min(j.stalls.length, d.stalls.length); i++) {
  var x = j.stalls[i], y = d.stalls[i], why = [];
  if (x.t !== y.t) why.push("t");
  if (x.detect !== y.detect) why.push("detect");
  if (x.total !== y.total) why.push("total");
  if (x.since !== y.since) why.push("since");
  if (x.reason !== y.reason) why.push("reason");
  if ((x.gc || "") !== (y.gc || "")) why.push("gc");
  if ((x.ctx || "") !== (y.ctx || "")) why.push("ctx");
  if ((+x.t0 || 0) !== (+y.t0 || 0)) why.push("t0");
  if ((+x.t1 || 0) !== (+y.t1 || 0)) why.push("t1");
  if (!!x.est !== !!y.est) why.push("est");
  var jt = x.timeline || [], yt = y.timeline || [];
  if (jt.length !== yt.length) why.push("timeline条数");
  else for (var k = 0; k < jt.length; k++) {
    if (jt[k][0] !== yt[k][0] || !near(jt[k][1], yt[k][1])) why.push("timeline值");
  }
  var js0 = x.stack || [], ys0 = y.stack || [];
  if (js0.length !== ys0.length) why.push("stack条数(" + js0.length + "vs" + ys0.length + ")");
  else for (var m = 0; m < js0.length; m++) if (js0[m] !== ys0[m]) why.push("stack第" + m + "层");
  if (why.length) { mism++; if (!firstBad) firstBad = "第 " + i + " 条: " + why.join("/"); }
}
check(mism === 0, "每条停顿时长/原因/时间线/调用栈都一致"
  + (mism ? "（" + mism + " 条不一致，第一处 " + firstBad + "）" : ""));

check(j.cpu.length === d.cpu.length, "CPU 采样条数一致（" + j.cpu.length + "）");
var cm = 0;
for (var c = 0; c < Math.min(j.cpu.length, d.cpu.length); c++) {
  if (j.cpu[c].t !== d.cpu[c].t || !near(j.cpu[c].fps, d.cpu[c].fps)
      || !near(j.cpu[c].tps, d.cpu[c].tps) || !near(j.cpu[c].cpu, d.cpu[c].cpu)) cm++;
}
check(cm === 0, "CPU 采样值一致" + (cm ? "（" + cm + " 条不一致）" : ""));

var nodes = 0, fbad = "";
function cmpTree(u, v, path) {
  nodes++;
  if (u.name !== v.name || u.ms !== v.ms || u.count !== v.count) {
    if (!fbad) fbad = path + "." + u.name;
    return;
  }
  if ((u.color || null) !== (v.color || null)) { if (!fbad) fbad = path + "." + u.name + "(颜色)"; return; }
  if ((u.kids || []).length !== (v.kids || []).length) { if (!fbad) fbad = path + "." + u.name + "(子节点数)"; return; }
  for (var i2 = 0; i2 < (u.kids || []).length; i2++) cmpTree(u.kids[i2], v.kids[i2], path + "." + u.name);
}
cmpTree(j.flame, d.flame, "");
check(!fbad, "火焰树逐节点一致（" + nodes + " 个节点）" + (fbad ? "，第一处 " + fbad : ""));

console.log();
console.log("== 结果 ==");
console.log("PASS " + pass + "  FAIL " + fail);
console.log(fail === 0 ? "ALL REPORT CHECKS PASSED" : fail + " CHECK(S) FAILED");
process.exit(fail === 0 ? 0 : 1);
