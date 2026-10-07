import os, subprocess, sys, glob, shutil

JDKBIN = r"C:\Program Files\Eclipse Adoptium\jdk-17.0.20.8-hotspot\bin"
JAVAC = os.path.join(JDKBIN, "javac.exe")
JAVA = os.path.join(JDKBIN, "java.exe")
JAR = os.path.join(JDKBIN, "jar.exe")
NODE = r"C:\Users\Huang\.workbuddy\binaries\node\versions\22.22.2-3\node.exe"
# 项目根 = 本文件所在目录（G 盘仓库或 C 盘工作副本都能跑，不写死）
ROOT = os.path.dirname(os.path.abspath(__file__))
SEP = os.pathsep

VER = "0.7.0"

# 产物命名：FrameSpike-<loader>-<游戏版本>-<mod 版本>.jar（跟 fabric/neoforge 两个模块一致）
LOADER = "Forge"
MCVERS = "1.8.9-1.12.2"
MODS = os.path.expandvars(r"%USERPROFILE%\.lunarclient\profiles\1.8\mods\forge-1.8.9")
REAL_LOG = os.path.expandvars(r"%USERPROFILE%\.lunarclient\profiles\1.8\framespike\frame-spikes.log")

def run(cmd, cwd=ROOT):
    print("> " + " ".join('"%s"' % c if " " in c else c for c in cmd))
    p = subprocess.run(cmd, cwd=cwd, capture_output=True, text=True, encoding="utf-8", errors="replace")
    if p.stdout and p.stdout.strip():
        print(p.stdout.strip())
    if p.stderr and p.stderr.strip():
        print(p.stderr.strip())
    return p.returncode

def clear(*dirs):
    for d in dirs:
        t = os.path.join(ROOT, d)
        if os.path.isdir(t):
            shutil.rmtree(t, ignore_errors=True)

clear("out/main", "out/stub", "out/test", "out/selftest", "out/jscheck")
for d in ("out/main", "out/stub", "out/test", "out/jscheck", "dist"):
    os.makedirs(os.path.join(ROOT, d), exist_ok=True)

print("===== 1/8 compile main (release 8, UTF-8) =====")
rc = run([JAVAC, "--release", "8", "-encoding", "UTF-8", "-nowarn",
          "-cp", os.path.join(ROOT, "lib", "stubapi"),
          "-d", os.path.join(ROOT, "out", "main")]
         + sorted(glob.glob(os.path.join(ROOT, "src", "framespike", "*.java")))
         + sorted(glob.glob(os.path.join(ROOT, "src", "forge189", "framespike", "*.java"))))
if rc: sys.exit("main compile FAILED")

print("===== 2/8 compile stubs =====")
stubs = []
for r, d, f in os.walk(os.path.join(ROOT, "stubsrc")):
    for x in f:
        if x.endswith(".java"):
            stubs.append(os.path.join(r, x))
rc = run([JAVAC, "--release", "8", "-encoding", "UTF-8", "-nowarn",
          "-d", os.path.join(ROOT, "out", "stub")] + sorted(stubs))
if rc: sys.exit("stub compile FAILED")

print("===== 3/8 compile tests =====")
rc = run([JAVAC, "--release", "8", "-encoding", "UTF-8", "-nowarn",
          "-cp", os.path.join(ROOT, "lib", "stubapi") + SEP + os.path.join(ROOT, "out", "main"),
          "-d", os.path.join(ROOT, "out", "test")]
         + sorted(glob.glob(os.path.join(ROOT, "test", "*.java"))))
if rc: sys.exit("test compile FAILED")

CP = (os.path.join(ROOT, "lib", "stubapi") + SEP + os.path.join(ROOT, "out", "main") + SEP
      + os.path.join(ROOT, "out", "test") + SEP + os.path.join(ROOT, "resources"))
STUB = os.path.join(ROOT, "out", "stub")

print("===== 4/8 SelfTest（故意不把 out/stub 放上 classpath，")
print("            这样 URLClassLoader 才会去装补丁后的类而不是原版存根） =====")
rc_self = run([JAVA, "-cp", CP, "SelfTest"])

print("===== 5/8 CmdTest（需要 out/stub 提供 GL11 和假 MC 类） =====")
rc_cmd = run([JAVA, "-cp", CP + SEP + STUB, "CmdTest"])

print("===== 6/8 RoundTrip（真类往返无损） =====")
VANILLA = os.path.expandvars(r"%APPDATA%\.minecraft\versions\1.8.9\1.8.9.jar")
if os.path.isfile(VANILLA):
    rc_rt = run([JAVA, "-cp", CP, "RoundTrip", VANILLA, "40"])
else:
    print("  [SKIP] 找不到原版 jar: %s" % VANILLA)
    rc_rt = 0

print("===== 7/8 网页报告自检（模板占位符 / 导入入口 / JS 解析器 vs Java 口径 / 整页假 DOM） =====")
TPL = os.path.join(ROOT, "resources", "framespike", "report.html")
CHECK = os.path.join(ROOT, "test", "parse_check.js")
PAGE = os.path.join(ROOT, "test", "page_check.js")
SAMPLE = os.path.join(ROOT, "out", "jscheck", "sample.log")
SAMPLE_JSON = os.path.join(ROOT, "out", "jscheck", "sample.json")
run([NODE, CHECK, TPL, "--emit", SAMPLE])
run([JAVA, "-cp", CP, "JsonDump", SAMPLE, SAMPLE_JSON])
rc_js = run([NODE, CHECK, TPL, SAMPLE, SAMPLE_JSON])
if os.path.isfile(REAL_LOG):
    REAL_JSON = os.path.join(ROOT, "out", "jscheck", "real.json")
    print("  （再来一遍真实日志：%s）" % REAL_LOG)
    run([JAVA, "-cp", CP, "JsonDump", REAL_LOG, REAL_JSON])
    rc_real = run([NODE, CHECK, TPL, REAL_LOG, REAL_JSON])
    rc_js = rc_js or rc_real
else:
    print("  [SKIP] 没有真实日志: %s" % REAL_LOG)
    rc_real = 0
print("  --- 整页假 DOM（渲染 / 导入日志 / 导入字体 / 重置） ---")
rc_page = run([NODE, PAGE, TPL])
rc_js = rc_js or rc_page

print("===== 8/8 package jar =====")

# mcmod.info：Forge 的 mod 元数据（名字 / 描述 / 图标 / 版本），版本跟着 VER 走，别手改
try:
    import json
    mcmod = [{
        "modid": "framespike",
        "name": "FrameSpike 帧刺",
        "description": "Minecraft 帧时间尖峰与卡顿分析 mod（Forge coremod，1.8.9 / 1.12.2）。"
                       "在主线程冻住的那一刻抓调用栈："
                       "真实停顿时长、原因归因、GC 关联、"
                       "崩溃现场，并生成可导入其他日志的分析网页。"
                       "纯客户端，不向外部发送任何数据。",
        "version": VER,
        # 1.8.9 的 FML 拿 mcversion 做版本范围匹配（ModMetadata.acceptableMinecraftVersion），
        # 写单个版本会让 1.12.2 判定"不是给这个版本的"，所以写成闭区间
        "mcversion": "[1.8.9,1.12.2]",
        "authorList": ["Trusler"],
        "logoFile": "framespike/logo.png",
        "url": "https://github.com/Trusler258",
        "updateUrl": "",
        "parent": "",
        "screenshots": []
    }]
    path = os.path.join(ROOT, "resources", "mcmod.info")
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write(json.dumps(mcmod, ensure_ascii=False, indent=2))
        f.write("\n")
    print("mcmod.info 已生成（Forge 元数据：名称/描述/图标）")
except Exception as e:
    print("mcmod.info 生成失败（不影响打包）:", e)

mf = os.path.join(ROOT, "MANIFEST.MF")
# MANIFEST 里只用 ASCII（JAR 规范按 72 字节折行，塞中文会被折坏）；中文署名走代码里的 SIGN 常量
open(mf, "w", encoding="ascii", newline="\n").write(
    "Manifest-Version: 1.0\n"
    "FMLCorePlugin: framespike.FrameSpikePlugin\n"
    "Implementation-Title: FrameSpike\n"
    "Implementation-Version: %s\n" % VER +
    "Implementation-Vendor: Trusler\n"
    "Implementation-Vendor-Id: trusler\n"
    "Built-For: Minecraft 1.8.9, 1.12.2\n"
    "\n")
jarp = os.path.join(ROOT, "dist", "FrameSpike-%s-%s-%s.jar" % (LOADER, MCVERS, VER))
if os.path.exists(jarp):
    os.remove(jarp)
rc2 = run([JAR, "cfm", jarp, mf, "-C", os.path.join(ROOT, "out", "main"), ".",
           "-C", os.path.join(ROOT, "resources"), "."])
if rc2: sys.exit("jar FAILED")
print("jar size: %d bytes" % os.path.getsize(jarp))

# 顺手把 mods 目录里的旧版列出来，方便决定要不要 python install_fix.py
if os.path.isdir(MODS):
    old = [f for f in sorted(os.listdir(MODS))
           if f.startswith("FrameSpike-") and f.endswith(".jar")]
    print("mods 里现存的 FrameSpike jar:", old if old else "(无)")

print()
ok = (rc_self == 0 and rc_cmd == 0 and rc_rt == 0 and rc_js == 0)
print("SelfTest     :", "PASS" if rc_self == 0 else "FAIL")
print("CmdTest      :", "PASS" if rc_cmd == 0 else "FAIL")
print("RoundTrip    :", "PASS" if rc_rt == 0 else "FAIL")
print("ReportCheck  :", "PASS" if rc_js == 0 else "FAIL", "（内含整页假 DOM 检查）")
print()
print("SELFTEST:", "PASS" if ok else "FAIL")
sys.exit(0 if ok else 1)
