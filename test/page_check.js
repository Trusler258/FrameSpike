/*
 * 报告页面整页自检 —— 不开浏览器，用一个最小的假 DOM 把页面脚本真跑一遍：
 *   冷启动渲染 -> 拖入日志换数据 -> 拖入坏文件 -> 拖入字体 -> 字体坏文件 -> 重置 -> 火焰图聚焦
 *
 *   node test/page_check.js <report.html>
 *
 * 断言只看"外面看得见的东西"（innerHTML / textContent / CSS 变量 / class），
 * 页面内部怎么改都不会假通过 —— 假 DOM 只实现浏览器里真实存在的那些 API。
 */

var fs = require("fs");

var pass = 0, fail = 0;
function check(ok, label) {
  if (ok) { pass++; console.log("  PASS  " + label); }
  else { fail++; console.log("  FAIL  " + label); }
}
function countOf(s, sub) { return String(s).split(sub).length - 1; }

var htmlPath = process.argv[2];
if (!htmlPath) { console.log("用法: node test/page_check.js <report.html>"); process.exit(2); }
var html = fs.readFileSync(htmlPath, "utf8");

/* ------------------------------------------------------------------ 两份测试日志 */

function logA() {
  return [
    "=== FrameSpike v9.9.9  by Tester ===",
    "03:00:00.000  STALL-DETECT 61ms  since='hudForge'  frames=1  ticks=1  stall#1  reason=写文件",
    "  timeline:",
    "    runTick              t+   0.412ms",
    "    hudForge             t+  33.221ms",
    "  stack of \"Client thread\":",
    "    java.io.FileOutputStream.writeBytes(Native Method)",
    "    java.io.FileOutputStream.write(FileOutputStream.java:326)",
    "    com.moonsworth.lunar.client.AAA.BBB(Unknown Source)",
    "  STALL-END    total=372ms  detected@61ms  reason=写文件",
    "03:01:00.000  STALL-DETECT 60ms  since='runTick'  frames=2  ticks=2  stall#2  reason=窗口合成",
    "  stack of \"Client thread\":",
    "    org.lwjgl.opengl.Display.update(Native Method)",
    "  STALL-END    total=800ms  detected@60ms  reason=窗口合成",
    "03:02:00.000  STALL-DETECT 60ms  since='runTick'  frames=3  ticks=3  stall#3  reason=写文件",
    "  stack of \"Client thread\":",
    "    java.io.FileOutputStream.writeBytes(Native Method)",
    "    java.io.FileOutputStream.write(FileOutputStream.java:326)",
    "    com.moonsworth.lunar.client.CCC.DDD(Unknown Source)",
    "    com.moonsworth.lunar.client.EEE.FFF(Unknown Source)",
    "  STALL-END    total=28ms  detected@60ms  reason=写文件",
    "03:03:00.000  [CPU] 60.1s window, 8 cores, machine busy=8.2%  fps\u2248112.0  tps\u224820.0  frames=4513  ticks=903",
    "03:04:00.000  === CRASH ===  上次会话  file=crash-test-client.txt",
    "  time=2026-10-06 01:02:30",
    "  desc=java.lang.NullPointerException: Ticking entity",
    "  reason=GL驱动",
    "--- 崩溃栈前 2 帧 ---",
    "  at net.minecraft.world.World.func_72939_s(World.java:100)"
  ].join("\n") + "\n";
}
function logB() {
  return [
    "=== FrameSpike v9.9.9  by Tester ===",
    "04:00:00.000  STALL-DETECT 70ms  since='renderWorld'  frames=1  ticks=1  stall#1  reason=GL驱动",
    "  stack of \"Client thread\":",
    "    org.lwjgl.opengl.GL11.nglDeleteBuffers(ILjava/nio/IntBuffer;J)V",
    "  STALL-END    total=2500ms  detected@70ms  reason=GL驱动",
    "04:01:00.000  [CPU] 60.1s window, 8 cores, machine busy=44.0%  fps\u224855.0  tps\u224819.0  frames=10  ticks=9"
  ].join("\n") + "\n";
}

/* ------------------------------------------------------------------ 假 DOM */

var els = {};
var docListeners = {}, winListeners = {};
var cssVars = {};
var addedFaces = [];

function styleStub() {
  return {
    props: cssVars,
    setProperty: function (k, v) { cssVars[k] = v; },
    removeProperty: function (k) { delete cssVars[k]; }
  };
}
function mkEl(id) {
  if (els[id]) return els[id];
  var e = {
    id: id, innerHTML: "", textContent: "", value: "", files: null, dataset: {},
    style: styleStub(), onclick: null, onchange: null, listeners: {},
    classList: {
      set: {},
      add: function (c) { this.set[c] = 1; },
      remove: function (c) { delete this.set[c]; },
      toggle: function (c) { if (this.set[c]) { delete this.set[c]; return false; } this.set[c] = 1; return true; },
      contains: function (c) { return !!this.set[c]; }
    },
    addEventListener: function (t, f) { (this.listeners[t] = this.listeners[t] || []).push(f); },
    querySelector: function () { return mkEl(id + "-sub"); },
    click: function () { if (this.onclick) this.onclick(); },
    fire: function (t, ev) { (this.listeners[t] || []).forEach(function (f) { f(ev); }); }
  };
  els[id] = e;
  return e;
}

var documentStub = {
  documentElement: { style: styleStub() },
  fonts: {
    add: function (f) { if (addedFaces.indexOf(f.name) < 0) addedFaces.push(f.name); },
    "delete": function (f) { addedFaces = addedFaces.filter(function (n) { return n !== f.name; }); }
  },
  getElementById: mkEl,
  addEventListener: function (t, f) { (docListeners[t] = docListeners[t] || []).push(f); }
};
var windowStub = {
  addEventListener: function (t, f) { (winListeners[t] = winListeners[t] || []).push(f); }
};
function FileReaderStub() {
  var self = this;
  this.result = null;
  this.readAsText = function (file) { self.result = file._text; if (self.onload) self.onload(); };
  this.readAsArrayBuffer = function (file) { self.result = file._buf; if (self.onload) self.onload(); };
}
function FontFaceStub(name, src) {
  this.name = name; this.src = src;
  var self = this;
  this.load = function () {
    if (FontFaceStub.fail) {
      return { then: function () { return { "catch": function (f) { f({ message: "bad font" }); } }; } };
    }
    return { then: function (f) { f(self); return { "catch": function () { } }; } };
  };
}
FontFaceStub.fail = false;

global.document = documentStub;
global.window = windowStub;
global.FileReader = FileReaderStub;
global.FontFace = FontFaceStub;
global.innerWidth = 1280;

/* ------------------------------------------------------------------ 页面脚本 */

var block = (html.match(/\/\*==PARSE-BEGIN==\*\/([\s\S]*?)\/\*==PARSE-END==\*\//) || [])[1];
if (!block) { console.log("FAIL 模板里找不到 PARSE 区块"); process.exit(1); }
var mod = { exports: {} };
new Function("module", "exports", block)(mod, mod.exports);
var parseLog = mod.exports.parseLog;

var embedded = parseLog(logA());
function subst(s) {
  return s.replace("__DATA__", JSON.stringify(embedded))
          .replace("__TITLE__", "FrameSpike 卡顿报告")
          .replace("__SOURCE__", "out\\cmdtest\\frame-spikes.log")
          .replace("__GENERATED__", "2026-10-05 19:11:05")
          .replace("__VERSION__", "0.4.0")
          .replace("__AUTHOR__", "Trusler");
}
var pageHtml = subst(html);
var script = (pageHtml.match(/<script>([\s\S]*?)<\/script>/) || [])[1];
var leftover = script.match(/__[A-Z][A-Z0-9]*__/g);
if (leftover) { console.log("FAIL 脚本里还有没替换的占位符: " + leftover.join(",")); process.exit(1); }

/* 身子里的初始文本也要照着真实 HTML 摆好（假 DOM 不解析 HTML） */
var body = pageHtml.replace(/<script>[\s\S]*?<\/script>/, "");
var SKIP = { script: 1, style: 1 };
["src", "gen", "status", "statusbar", "kpis", "buckets", "bucketlg", "reasons",
 "reasonlg", "crumbs", "flame", "cpupanel", "rows", "tip", "drop"].forEach(function (id) {
  var m = new RegExp('<[a-z]+[^>]*id="' + id + '"[^>]*>([\\s\\S]*?)</[a-z]+>').exec(body);
  if (m) mkEl(id).textContent = m[1].replace(/<[^>]*>/g, "").replace(/^\s+|\s+$/g, "");
});

/* ------------------------------------------------------------------ 开跑 */

console.log("== 1. 冷启动（内嵌数据） ==");
try { new Function(script)(); }
catch (e) { console.log("  FAIL 页面脚本执行崩了: " + (e && e.stack ? e.stack.split("\n")[0] : e)); fail++; }

var src0 = els["src"].textContent;
check(src0.indexOf("frame-spikes.log") >= 0, "头部显示数据来源（" + src0 + "）");
check(els["gen"].textContent.indexOf("19:11:05") >= 0, "头部显示生成时间");
check(els["kpis"].innerHTML.indexOf("3 次") >= 0, "KPI 出内嵌日志的停顿次数（3 次）");
/* 归因成员必须按「类.方法」聚合：logA 里两条停顿的栈顶都是 FileOutputStream.writeBytes */
check(els["reasonlg"].innerHTML.indexOf("×2") >= 0, "同一方法的多条停顿合并成一行（×2）");
check(countOf(els["reasonlg"].innerHTML, 'class="rm"') === 2, "成员行数按方法聚合后是 2 行（不是每停顿一行）");
check(els["reasonlg"].innerHTML.indexOf('class="rmt"') >= 0, "成员/大类都带时间列");
check(/\d\d:\d\d:\d\d/.test(els["reasonlg"].innerHTML), "时间列里是 HH:MM:SS");
check(els["kpis"].innerHTML.indexOf("1.20s") >= 0, "KPI 累计时长 1.20s（>1000ms 换成 s）");
check(els["kpis"].innerHTML.indexOf("112 fps") >= 0, "KPI 平均帧率取的是采样均值（112 fps）");
check(els["buckets"].innerHTML.indexOf("background:#3f6f8f") >= 0
  && els["buckets"].innerHTML.indexOf("background:#ff5d5d") >= 0, "分档条真的画了（有颜色块）");
check(countOf(els["buckets"].innerHTML, "<i ") === 3, "次数为 0 的那一档不占位（3 档有数据）");
check(els["bucketlg"].innerHTML.indexOf("100-199ms") >= 0 && els["bucketlg"].innerHTML.indexOf(">=500ms") >= 0,
  "分档图例四档齐全");
check(els["reasons"].innerHTML.indexOf("background:#8b6dff") >= 0, "归因条按原因配色");
check(els["reasonlg"].innerHTML.indexOf("写文件") >= 0 && els["reasonlg"].innerHTML.indexOf("窗口合成") >= 0,
  "归因图例带原因名和占比");
check(els["crumbs"].innerHTML.indexOf("Client thread") >= 0, "火焰图面包屑有根节点");
check(els["flame"].innerHTML.indexOf("fnode") >= 0 && els["flame"].innerHTML.indexOf("writeBytes") >= 0,
  "火焰图画出帧节点");
check(els["flame"].innerHTML.indexOf("Display.update") >= 0, "第二支栈顶也在火焰图里");
check(countOf(els["flame"].innerHTML, 'class="fz"') >= 2, "火焰图分出多层（" + countOf(els["flame"].innerHTML, 'class="fz"') + " 层）");
check(els["cpupanel"].innerHTML.indexOf("polyline") >= 0 && els["cpupanel"].innerHTML.indexOf("fps") >= 0,
  "CPU 面板画出折线");
check(html.indexOf('id="p-crash"') >= 0 && html.indexOf('id="crashrows"') >= 0, "模板里有崩溃页与崩溃表");
check(html.indexOf('data-key="p-crash"') >= 0 && html.indexOf('data-cnt="crashes"') >= 0, "资源树里有崩溃入口与计数");
check(html.indexOf('id="p-cmp"') >= 0 && html.indexOf('id="cmppanel"') >= 0, "模板里有对比页");
check(html.indexOf('id="bcmp"') >= 0 && html.indexOf('id="bcclear"') >= 0 && html.indexOf('id="fcmp"') >= 0,
  "对比按钮与文件选择框齐全");
check(els["rows"].innerHTML.indexOf("writeBytes") >= 0 && els["rows"].innerHTML.indexOf("栈") >= 0,
  "停顿明细带可展开的栈");
check(els["rows"].innerHTML.indexOf("1.20s") < 0 && els["rows"].innerHTML.indexOf("800ms") >= 0, "明细里是真实时长");
check(els["status"].textContent.indexOf("3 条停顿") >= 0, "状态条报告条数");
check(els["status"].textContent.indexOf("拖 .log") >= 0, "状态条给出导入提示");
check(!els["statusbar"].classList.contains("warn"), "正常数据不报警告色");

console.log("== 2. 拖入一份别的日志 ==");
var ev = { preventDefault: function () { }, dataTransfer: { files: [{ name: "other-session.log", size: 4096, _text: logB() }] } };
(winListeners["dragenter"] || []).forEach(function (f) { f(ev); });
check(els["drop"].classList.contains("on"), "拖进来时全屏浮层亮起");
(winListeners["dragleave"] || []).forEach(function (f) { f(ev); });
check(!els["drop"].classList.contains("on"), "拖出去浮层收掉");
(winListeners["dragenter"] || []).forEach(function (f) { f(ev); });
(winListeners["drop"] || []).forEach(function (f) { f(ev); });
check(!els["drop"].classList.contains("on"), "松手后浮层收掉");
check(els["src"].textContent.indexOf("other-session.log") >= 0, "头部换成新文件名");
check(els["src"].textContent.indexOf("4 KB") >= 0, "头部带上文件大小");
check(els["kpis"].innerHTML.indexOf("1 次") >= 0, "KPI 变成新日志的条数（1 次）");
check(els["kpis"].innerHTML.indexOf("2.50s") >= 0, "KPI 时长换成新日志的（2.50s）");
check(els["reasonlg"].innerHTML.indexOf("GL驱动") >= 0, "归因换成新日志的原因");
check(els["reasonlg"].innerHTML.indexOf("写文件") < 0, "旧日志的原因不留残影");
check(els["flame"].innerHTML.indexOf("nglDeleteBuffers") >= 0, "火焰图换成新日志的栈");
check(els["rows"].innerHTML.indexOf("nglDeleteBuffers") >= 0, "明细换成新日志的栈");
check(els["cpupanel"].innerHTML.indexOf("polyline") >= 0, "CPU 面板照样画得出来（导入数据的形状对了）");
check(els["status"].textContent.indexOf("已导入") >= 0, "状态条报告已导入");
check(!els["statusbar"].classList.contains("warn"), "正常日志不报警告色");

console.log("== 3. 拖入一个不是日志的文件 ==");
var evBad = { preventDefault: function () { }, dataTransfer: { files: [{ name: "not-a-log.txt", size: 12, _text: "hello world\n" }] } };
(winListeners["drop"] || []).forEach(function (f) { f(evBad); });
check(els["status"].textContent.indexOf("一条 STALL 都没有") >= 0, "明确说“一条 STALL 都没有”");
check(els["statusbar"].classList.contains("warn"), "这种情况状态条转警告色");
check(els["rows"].innerHTML.indexOf("没有记录到停顿") >= 0, "空数据走空状态而不是崩掉");
check(els["cpupanel"].innerHTML.indexOf("没有 CPU 采样行") >= 0, "没有采样时 CPU 面板给说明");

console.log("== 4. 拖入字体 ==");
var srcBeforeFont = els["src"].textContent;
var evFont = { preventDefault: function () { }, dataTransfer: { files: [{ name: "MyRound.ttf", size: 2048, _buf: new ArrayBuffer(16) }] } };
(winListeners["drop"] || []).forEach(function (f) { f(evFont); });
check(String(cssVars["--font-ui"]).indexOf("'FSUser'") >= 0, "UI 字体变量前置了导入的字体");
check(String(cssVars["--font-num"]).indexOf("'FSUser'") >= 0, "数字字体也跟着换");
check(String(cssVars["--font-ui"]).indexOf("var(--stack-ui)") >= 0, "导入字体后面还留着兜底字体栈");
check(addedFaces.indexOf("FSUser") >= 0, "字体真的注册进 document.fonts");
check(els["status"].textContent.indexOf("MyRound.ttf") >= 0, "状态条说明字体已换");
check(els["src"].textContent === srcBeforeFont, "换字体不动数据");

console.log("== 5. 字体文件坏掉 / 按钮走 file input ==");
FontFaceStub.fail = true;
els["ffont"].files = [{ name: "broken.ttc", size: 9, _buf: new ArrayBuffer(4) }];
els["ffont"].onchange();
check(els["status"].textContent.indexOf("没吃进去") >= 0, "字体加载失败有明确提示");
check(els["statusbar"].classList.contains("warn"), "失败时状态条转警告色");
FontFaceStub.fail = false;
var logClicked = false, fontClicked = false;
els["flog"].click = function () { logClicked = true; };
els["ffont"].click = function () { fontClicked = true; };
els["blog"].onclick();
els["bfont"].onclick();
check(logClicked && fontClicked, "两个导入按钮都会去点隐藏的 file input");

console.log("== 6. 重置 ==");
els["breset"].onclick();
check(cssVars["--font-ui"] === undefined && cssVars["--font-num"] === undefined, "重置把字体变量摘掉");
check(addedFaces.indexOf("FSUser") < 0, "重置把注册的字体也删了");
check(els["src"].textContent === src0, "重置回到内嵌数据的来源");
check(els["gen"].textContent.indexOf("19:11:05") >= 0, "重置把生成时间也还原");
check(els["kpis"].innerHTML.indexOf("3 次") >= 0, "重置回到内嵌数据（3 次）");
check(els["reasonlg"].innerHTML.indexOf("写文件") >= 0, "重置后归因也回来了");
check(els["status"].textContent.indexOf("已回到内嵌数据") >= 0, "状态条说明已重置");

console.log("== 7. 火焰图聚焦 / Esc 回退 / 悬停提示 ==");
var crumbs1 = countOf(els["crumbs"].innerHTML, "<u ");
check(crumbs1 === 1, "一开始面包屑只有根节点");
function nodeEvent(name) {
  return { target: { closest: function () { return { dataset: { name: name } }; } } };
}
els["flame"].fire("click", nodeEvent("java.io.FileOutputStream.writeBytes(Native Method)"));
check(countOf(els["crumbs"].innerHTML, "<u ") === 2, "点有子节点的帧会聚焦进去");
check(els["crumbs"].innerHTML.indexOf("writeBytes") >= 0, "面包屑记下了聚焦的那一帧");
els["flame"].fire("click", nodeEvent("org.lwjgl.opengl.Display.update(Native Method)"));
check(countOf(els["crumbs"].innerHTML, "<u ") === 2, "点当前层之外的帧不动（只认子节点）");
(docListeners["keydown"] || []).forEach(function (f) { f({ key: "Escape" }); });
check(countOf(els["crumbs"].innerHTML, "<u ") === 1, "Esc 回退到上层");
els["flame"].fire("mousemove", { clientX: 100, clientY: 100, target: { closest: function () { return { dataset: { name: "x.y.z", ms: "372", count: "2" } }; } } });
check(els["tip"].style.display === "block" && els["tip"].textContent.indexOf("累计") >= 0, "悬停出提示框带累计时长");
els["flame"].fire("mouseleave", {});
check(els["tip"].style.display === "none", "移开鼠标提示框收掉");

console.log("== 8. 查看器骨架（静态检查：第二个脚本在真浏览器里跑） ==");
["tabs", "tree", "stallgroups", "p-overview", "p-timeline", "p-flame", "p-calltree", "p-cpu",
 "p-rows", "p-crash", "p-cmp", "stallgroups", "tl-main", "tl-mini", "ctree", "tl-sum", "sessinfo"].forEach(function (id) {
  check(html.indexOf('id="' + id + '"') >= 0, "有 #" + id);
});
check(html.indexOf('data-key="p-timeline"') >= 0 && html.indexOf('data-key="p-calltree"') >= 0,
  "资源树里有时间轴与调用树入口");
check(html.indexOf('"p-crash": "崩溃"') >= 0 && html.indexOf('"p-cmp": "对比"') >= 0, "VIEWS 注册了崩溃与对比");
check(html.indexOf("STALL_KEY") >= 0 && html.indexOf("stallIdx") >= 0, "停顿改用单预览标签（不会点一次多一个页）");
check(html.indexOf("tod(s.t0)") >= 0, "时间轴优先用模组原生的 t0/t1");
check(html.indexOf("层更深") < 0 && html.indexOf("FS_MAX_FRAMES") < 0, "模板里没有栈截断（全部写出）");
check(html.indexOf("#reasonlg{display:flex;flex-direction:column") >= 0, "归因列表竖排（不再横向铺多列）");
check(html.indexOf("class=\"rr\"") >= 0 || html.indexOf('class="rr"') >= 0, "归因大类是可点开的一行");
check(html.indexOf("rsub") >= 0 && html.indexOf("function renderReasons") >= 0, "大类展开出成员列表（栈顶帧分组）");
check(html.indexOf("#reasonlg .rgrp{display:block") >= 0, "展开是树状竖排（不被老 .lg div 规则拉成横排）");
check(html.indexOf("stackDepth <= 0 ? st.length") < 0, "模板不掺模组代码");
check(html.indexOf("opacity:'") < 0, "火焰图不再叠 opacity（颜色不再发灰）");

console.log();
console.log("== 结果 ==");
console.log("PASS " + pass + "  FAIL " + fail);
console.log(fail === 0 ? "ALL PAGE CHECKS PASSED" : fail + " CHECK(S) FAILED");
process.exit(fail === 0 ? 0 : 1);
