import os, zipfile, time, io, re

R = []
def add(s=""):
    R.append(str(s))

def ts(p):
    try:
        return time.strftime("%Y-%m-%d %H:%M:%S", time.localtime(os.path.getmtime(p)))
    except Exception:
        return "?"

LC = r"C:\Users\Huang\.lunarclient\profiles\1.8"
MODS = os.path.join(LC, "mods", "forge-1.8.9")
ICHOR = os.path.join(LC, "mods", "ichor-1.8.9")
FSDIR = os.path.join(LC, "framespike")
LOCAL = os.path.expandvars(r"%LOCALAPPDATA%\Programs\Lunar Client")
SRC_JAR = r"C:\Users\Huang\WorkBuddy\2026-10-05-03-06-50\dist\FrameSpike-0.1.0.jar"

add("现在时间: " + time.strftime("%Y-%m-%d %H:%M:%S"))
add()
add("### 1. mods/forge-1.8.9 内容")
for d in (MODS, ICHOR):
    add("--- " + d)
    if os.path.isdir(d):
        for f in sorted(os.listdir(d)):
            p = os.path.join(d, f)
            add("   %10d  %s  %s" % (os.path.getsize(p), ts(p), f))
    else:
        add("   (目录不存在)")

add()
add("### 2. 有没有 framespike 数据目录（coremod 只要跑起来就会建）")
if os.path.isdir(FSDIR):
    for f in sorted(os.listdir(FSDIR)):
        p = os.path.join(FSDIR, f)
        add("   %10d  %s  %s" % (os.path.getsize(p), ts(p), f))
else:
    add("   (不存在) -> coremod 没跑过")

add()
add("### 3. 源 jar 与已安装 jar 的对比")
add("   src  : %s  %s" % (ts(SRC_JAR) if os.path.exists(SRC_JAR) else "缺失",
                          os.path.getsize(SRC_JAR) if os.path.exists(SRC_JAR) else ""))
cands = []
for d in (MODS, ICHOR, LC, os.path.join(LC, "mods")):
    if not os.path.isdir(d):
        continue
    for f in os.listdir(d):
        if "framespike" in f.lower() or "FrameSpike" in f:
            p = os.path.join(d, f)
            cands.append(p)
            add("   found: %s  size=%d  mtime=%s" % (p, os.path.getsize(p), ts(p)))
if not cands:
    add("   >>> 游戏目录里找不到 FrameSpike 的 jar：没装（或装错目录）")

add()
add("### 4. 我们做出来的 jar 的 MANIFEST（确认 FMLCorePlugin 写对了）")
if os.path.exists(SRC_JAR):
    z = zipfile.ZipFile(SRC_JAR)
    names = z.namelist()
    add("   条目数 %d，前 12 个: %s" % (len(names), names[:12]))
    try:
        add("   ---- MANIFEST.MF ----")
        mf = z.read("META-INF/MANIFEST.MF").decode("utf-8", "replace")
        for ln in mf.splitlines():
            add("   | " + ln)
    except Exception as e:
        add("   读 MANIFEST 失败: %s" % e)

add()
add("### 5. latest.log 里的关键行（coremod / 启动 / 报错）")
LOG = os.path.join(LC, "logs", "latest.log")
add("   latest.log mtime = %s" % (ts(LOG) if os.path.exists(LOG) else "缺失"))
if os.path.exists(LOG):
    txt = open(LOG, "rb").read().decode("utf-8", "replace")
    lines = txt.splitlines()
    add("   总行数 %d" % len(lines))
    pats = ["framespike", "FrameSpike", "CoreMod", "coremod", "Loading plugin", "FMLCorePlugin",
            "Setting user", "Forge Mod Loader", "Unexpected error", "Exception", "Caused by"]
    hit = {}
    for i, ln in enumerate(lines):
        for p in pats:
            if p in ln:
                hit.setdefault(p, []).append((i + 1, ln[:220]))
    for p in pats:
        v = hit.get(p, [])
        if p in ("Setting user", "Exception", "Caused by"):
            add("   [%s] %d 条，前 6:" % (p, len(v)))
            for i, ln in v[:6]:
                add("      %5d %s" % (i, ln))
        else:
            add("   [%s] %d 条" % (p, len(v)))
            for i, ln in v[:8]:
                add("      %5d %s" % (i, ln))
    add()
    add("   最后 12 行:")
    for ln in lines[-12:]:
        add("      " + ln[:220])

add()
add("### 6. ichor-boot.log 的头 40 行（看这次启动加载了哪些 jar）")
B = os.path.join(LC, "logs", "ichor-boot.log")
if os.path.exists(B):
    add("   mtime = %s" % ts(B))
    t = open(B, "rb").read().decode("utf-8", "replace").splitlines()
    for ln in t[:40]:
        add("   " + ln[:220])
else:
    add("   (缺失)")

add()
add("### 7. 游戏进程")
try:
    import subprocess
    p = subprocess.run(["powershell", "-NoProfile", "-Command",
                        "Get-Process | Where-Object { $_.ProcessName -match 'javaw|Lunar|Minecraft' } | "
                        "ForEach-Object { $_.ProcessName + ' pid=' + $_.Id + ' start=' + $_.StartTime }"],
                       capture_output=True, text=True, timeout=40)
    add(p.stdout.strip() or "   (没有相关进程)")
except Exception as e:
    add("   进程查询失败: %s" % e)

open(r"C:\Users\Huang\WorkBuddy\2026-10-05-03-06-50\diag_load.txt", "w", encoding="utf-8").write("\n".join(R))
print("ok")
