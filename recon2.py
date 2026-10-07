import os, re

OUT = r"C:\Users\Huang\WorkBuddy\2026-10-05-03-06-50\recon\recon2.txt"
R = []

# 1) ichor-boot.log —— 里面通常有完整 classpath / 外部文件列表
p = r"C:\Users\Huang\.lunarclient\profiles\1.8\logs\ichor-boot.log"
R.append("### ichor-boot.log (full, %s)" % ("exists" if os.path.exists(p) else "MISSING"))
if os.path.exists(p):
    txt = open(p, "rb").read().decode("utf-8", "replace")
    R.append(txt[:12000])

# 2) 全域找 jar
roots = [r"C:\Users\Huang\.lunarclient",
         os.path.expandvars(r"%LOCALAPPDATA%\Programs\Lunar Client"),
         os.path.expandvars(r"%APPDATA%\.minecraft"),
         os.path.expandvars(r"%APPDATA%\lunarclient"),
         r"C:\Users\Huang\.minecraft"]
R.append("")
R.append("### ALL JARS UNDER LUNAR / MINECRAFT ROOTS")
seen = 0
for base in roots:
    if not os.path.isdir(base):
        R.append("  (missing) %s" % base)
        continue
    for root, d, fs in os.walk(base):
        for f in fs:
            if f.lower().endswith(".jar"):
                fp = os.path.join(root, f)
                try:
                    sz = os.path.getsize(fp)
                except Exception:
                    sz = -1
                R.append("  %10.2f MB  %s" % (sz/1048576.0, fp))
                seen += 1
R.append("  total jars: %d" % seen)

# 3) 找可能的原版版本 jar（按名字特征）
R.append("")
R.append("### VERSION-LIKE JARS (1.8.9 / client / minecraft / obf)")
for base in roots:
    if not os.path.isdir(base):
        continue
    for root, d, fs in os.walk(base):
        for f in fs:
            low = f.lower()
            if low.endswith(".jar") and re.search(r"(1\.8|minecraft|client|version|obf|notch|srg)", low):
                R.append("  %s" % os.path.join(root, f))

# 4) 找 optifine 打完补丁的 MC 类（EntityRenderer 里应含 glFinish）
R.append("")
R.append("### SCAN: which jar contains a class referencing GL11 + glFinish")
GL11 = b"org/lwjgl/opengl/GL11"
FIN = b"glFinish"
import zipfile
for base in roots:
    if not os.path.isdir(base):
        continue
    for root, d, fs in os.walk(base):
        for f in fs:
            if not f.lower().endswith(".jar"):
                continue
            fp = os.path.join(root, f)
            try:
                z = zipfile.ZipFile(fp)
            except Exception:
                continue
            for n in z.namelist():
                if not n.endswith(".class"):
                    continue
                try:
                    b = z.read(n)
                except Exception:
                    continue
                if FIN in b and GL11 in b:
                    R.append("  %-50s %-60s cnt=%d size=%d" % (os.path.basename(fp), n, b.count(FIN), len(b)))

os.makedirs(os.path.dirname(OUT), exist_ok=True)
open(OUT, "w", encoding="utf-8").write("\n".join(R))
print("ok")
